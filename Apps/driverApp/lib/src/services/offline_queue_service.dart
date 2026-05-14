import 'dart:async';
import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:hive/hive.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../app_providers.dart';
import 'api_client.dart';
import 'connectivity_service.dart';

final offlineQueueProvider = StateNotifierProvider<OfflineQueueService, int>((ref) {
  final apiClient = ref.read(apiClientProvider);
  final connectivity = ref.read(connectivityServiceProvider);
  return OfflineQueueService(apiClient, connectivity);
});

class OfflineQueueService extends StateNotifier<int> {
  final ApiClient _apiClient;
  final ConnectivityService _connectivityService;
  static const String _boxName = 'offline_queue';
  static const int _maxRetries = 3;
  static const int _ttlHours = 24;

  StreamSubscription<bool>? _connectivitySub;
  bool _processing = false;

  OfflineQueueService(this._apiClient, this._connectivityService) : super(0) {
    _init();
  }

  void _init() {
    state = _box.length;
    _connectivitySub = _connectivityService.onlineStream.listen((isOnline) {
      if (isOnline && _box.isNotEmpty) processQueue();
    });
  }

  @override
  void dispose() {
    _connectivitySub?.cancel();
    super.dispose();
  }

  Box<Map<dynamic, dynamic>> get _box => Hive.box<Map<dynamic, dynamic>>(_boxName);

  Future<void> enqueueRequest({
    required String path,
    required String method,
    Map<String, dynamic>? data,
    String? idempotencyKey,
  }) async {
    final key = idempotencyKey ?? '${method.toUpperCase()}-$path';

    // Dedup: skip if same idempotency key already queued
    for (final existing in _box.values) {
      if ((existing['idempotencyKey'] as String?) == key) return;
    }

    final entry = {
      'id': DateTime.now().millisecondsSinceEpoch.toString(),
      'path': path,
      'method': method,
      'data': data != null ? jsonEncode(data) : null,
      'timestamp': DateTime.now().toIso8601String(),
      'idempotencyKey': key,
      'retryCount': 0,
    };
    await _box.add(entry);
    state = _box.length;
  }

  Future<void> processQueue() async {
    if (_processing || _box.isEmpty) return;
    _processing = true;
    try {
      for (final key in _box.keys.toList()) {
        final entry = _box.get(key);
        if (entry == null) continue;

        // TTL check
        final timestampStr = entry['timestamp'] as String?;
        if (timestampStr != null) {
          final enqueuedAt = DateTime.tryParse(timestampStr);
          if (enqueuedAt != null && DateTime.now().difference(enqueuedAt).inHours >= _ttlHours) {
            await _box.delete(key);
            state = _box.length;
            continue;
          }
        }

        final path = entry['path'] as String;
        final method = entry['method'] as String;
        final dataStr = entry['data'] as String?;
        final data = dataStr != null ? jsonDecode(dataStr) : null;
        final idempotencyKey = entry['idempotencyKey'] as String?;
        final retryCount = (entry['retryCount'] as int?) ?? 0;

        final options = Options(
          headers: idempotencyKey != null ? {'X-Idempotency-Key': idempotencyKey} : null,
        );

        try {
          switch (method.toUpperCase()) {
            case 'POST':
              await _apiClient.dio.post(path, data: data, options: options);
            case 'PUT':
              await _apiClient.dio.put(path, data: data, options: options);
            case 'PATCH':
              await _apiClient.dio.patch(path, data: data, options: options);
          }
          await _box.delete(key);
          state = _box.length;
        } on DioException catch (e) {
          final status = e.response?.statusCode;
          // 4xx = permanent failure (bad request, conflict, etc.) — remove
          if (status != null && status >= 400 && status < 500) {
            await _box.delete(key);
            state = _box.length;
            continue;
          }
          // Network errors shouldn't penalize the retry count. Rely on the 24h TTL instead.
          if (e.type == DioExceptionType.connectionError ||
              e.type == DioExceptionType.connectionTimeout ||
              e.type == DioExceptionType.sendTimeout ||
              e.type == DioExceptionType.receiveTimeout ||
              e.type == DioExceptionType.unknown) {
            continue; // Leave it in the queue, do not increment retryCount
          }

          // Max retries exhausted for 5xx errors — remove to unblock queue
          if (retryCount >= _maxRetries) {
            await _box.delete(key);
            state = _box.length;
            continue;
          }
          // Transient server error (5xx) — increment retry
          final updated = Map<dynamic, dynamic>.from(entry)
            ..['retryCount'] = retryCount + 1;
          await _box.put(key, updated);
        } catch (_) {
          // Unknown error — skip item, keep processing rest
        }
      }
    } finally {
      _processing = false;
      state = _box.length;
    }
  }
}
