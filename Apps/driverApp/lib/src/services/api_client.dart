import 'dart:async';

import 'package:dio/dio.dart';

import '../config/app_config.dart';
import '../models/auth_tokens.dart';
import 'token_storage.dart';

class ApiClient {
  ApiClient({required this.config, required this.tokenStorage, this.onSessionExpired}) {
    dio = Dio(
      BaseOptions(
        baseUrl: config.apiBaseUrl,
        connectTimeout: const Duration(seconds: 15),
        receiveTimeout: const Duration(seconds: 25),
        sendTimeout: const Duration(seconds: 15),
        contentType: 'application/json',
      ),
    );

    // P2: SSL Pinning Infrastructure
    // In production, you would add your server's .pem or .cer asset to the SecurityContext
    // This prevents MITM attacks by ensuring we only talk to the real server.
    /*
    (dio.httpClientAdapter as IOHttpClientAdapter).onHttpClientCreate = (client) {
      final sc = SecurityContext(withTrustedRoots: true);
      // sc.setTrustedCertificatesBytes(utf8.encode(serverCertContent));
      return HttpClient(context: sc);
    };
    */

    dio.interceptors.add(
      QueuedInterceptorsWrapper(
        onRequest: (options, handler) async {
          final token = await tokenStorage.readAccessToken();
          if (token != null && token.isNotEmpty) {
            options.headers['Authorization'] = 'Bearer $token';
          }

          // P1: Idempotency-Key Support
          final method = options.method.toUpperCase();
          if (method == 'POST' || method == 'PUT' || method == 'PATCH') {
            // Generate a unique key for the request if not already present.
            // Note: If the Repository provided a stable key (e.g., 'accept-123'), we MUST use it.
            if (!options.headers.containsKey('X-Idempotency-Key')) {
              options.headers['X-Idempotency-Key'] = 
                  'req-${DateTime.now().millisecondsSinceEpoch}-${options.path.hashCode}';
            }
          }
          // P2: Distributed Tracing & Correlation IDs
          final correlationId = 'trace-${DateTime.now().millisecondsSinceEpoch}-${options.path.hashCode}';
          options.headers['X-Correlation-ID'] = correlationId;

          handler.next(options);
        },
        onError: (error, handler) async {
          if (_shouldAttemptRefresh(error)) {
            try {
              final response = await _refreshAndRetry(error);
              return handler.resolve(response);
            } catch (_) {
              await tokenStorage.clear();
              onSessionExpired?.call();
            }
          }
          handler.next(error);
        },
      ),
    );
  }

  final AppConfig config;
  final TokenStorage tokenStorage;
  void Function()? onSessionExpired;
  late final Dio dio;

  Completer<void>? _refreshCompleter;

  bool _shouldAttemptRefresh(DioException error) {
    final status = error.response?.statusCode;
    final path = error.requestOptions.path;
    if (status != 401) return false;
    if (path.contains('/login') || path.contains('/register') || path.contains('/refresh-token')) {
      return false;
    }
    return true;
  }

  Future<Response<dynamic>> _refreshAndRetry(DioException error) async {
    await _refreshToken();
    final request = error.requestOptions;
    final options = Options(
      method: request.method,
      headers: request.headers,
      contentType: request.contentType,
      responseType: request.responseType,
      followRedirects: request.followRedirects,
      validateStatus: request.validateStatus,
      receiveDataWhenStatusError: request.receiveDataWhenStatusError,
    );
    return dio.request<dynamic>(
      request.path,
      data: request.data,
      queryParameters: request.queryParameters,
      options: options,
    );
  }

  Future<void> _refreshToken() async {
    if (_refreshCompleter != null) {
      return _refreshCompleter!.future;
    }
    final completer = Completer<void>();
    _refreshCompleter = completer;

    try {
      final refreshToken = await tokenStorage.readRefreshToken();
      if (refreshToken == null || refreshToken.isEmpty) {
        throw Exception('Missing refresh token');
      }
      final refreshDio = Dio(BaseOptions(baseUrl: config.apiBaseUrl));
      final response = await refreshDio.post<Map<String, dynamic>>(
        '/api/auth/driver/refresh-token',
        data: {'refreshToken': refreshToken},
      );
      final data = response.data ?? <String, dynamic>{};
      final tokens = AuthTokens.fromMap(data, currentRefreshToken: refreshToken);
      await tokenStorage.saveTokens(tokens);
      completer.complete();
    } catch (error, stackTrace) {
      completer.completeError(error, stackTrace);
      rethrow;
    } finally {
      _refreshCompleter = null;
    }
  }
}
