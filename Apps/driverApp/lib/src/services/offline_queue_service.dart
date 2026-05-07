import 'dart:async';
import 'dart:convert';
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
  StreamSubscription<bool>? _connectivitySub;

  OfflineQueueService(this._apiClient, this._connectivityService) : super(0) {
    _init();
  }

  void _init() {
    state = _box.length;

    // Auto-replay queued requests when connectivity is restored
    _connectivitySub = _connectivityService.onlineStream.listen((isOnline) {
      if (isOnline && _box.isNotEmpty) {
        processQueue();
      }
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
  }) async {
    final entry = {
      'id': DateTime.now().millisecondsSinceEpoch.toString(),
      'path': path,
      'method': method,
      'data': data != null ? jsonEncode(data) : null,
      'timestamp': DateTime.now().toIso8601String(),
    };
    await _box.add(entry);
    state = _box.length;
  }

  Future<void> processQueue() async {
    if (_box.isEmpty) return;

    final keys = _box.keys.toList();
    for (var key in keys) {
      final entry = _box.get(key);
      if (entry == null) continue;

      try {
        final path = entry['path'] as String;
        final method = entry['method'] as String;
        final dataStr = entry['data'] as String?;
        final data = dataStr != null ? jsonDecode(dataStr) : null;

        if (method.toUpperCase() == 'POST') {
          await _apiClient.dio.post(path, data: data);
        } else if (method.toUpperCase() == 'PUT') {
          await _apiClient.dio.put(path, data: data);
        } else if (method.toUpperCase() == 'PATCH') {
          await _apiClient.dio.patch(path, data: data);
        }
        
        await _box.delete(key);
        state = _box.length;
      } catch (e) {
        // Stop processing on first failure to maintain order
        break;
      }
    }
  }
}
