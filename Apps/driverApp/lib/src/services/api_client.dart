import 'dart:async';

import 'package:dio/dio.dart';

import '../config/app_config.dart';
import '../models/auth_tokens.dart';
import 'token_storage.dart';

/// Why an authenticated session was terminated. Lets the UI show an accurate,
/// professional message instead of a generic "please log in again".
enum SessionEndReason {
  /// Access/refresh tokens are no longer valid (expired, revoked, etc.).
  expired,

  /// The driver account was suspended or removed by an administrator.
  /// Refreshing tokens cannot recover this — the driver must be signed out.
  accountDisabled,
}

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
          // A session-end is already in progress: swallow the trailing burst of
          // 401s from in-flight requests instead of re-triggering logout/refresh.
          if (_endingSession) {
            handler.next(error);
            return;
          }

          // An administrator suspended/removed this account. Refreshing the token
          // cannot fix this — the server will keep returning 401. Sign the driver
          // out immediately and route them to login. This is what stops the
          // previous infinite refresh→retry→401 loop (the "401 flood").
          if (_isAccountDisabled(error)) {
            _endSession(SessionEndReason.accountDisabled);
            handler.next(error);
            return;
          }

          if (_shouldAttemptRefresh(error)) {
            try {
              final response = await _refreshAndRetry(error);
              return handler.resolve(response);
            } catch (_) {
              _endSession(SessionEndReason.expired);
            }
          }
          handler.next(error);
        },
      ),
    );
  }

  final AppConfig config;
  final TokenStorage tokenStorage;

  /// Invoked once when the session ends. Carries the [SessionEndReason] so the
  /// UI can present an accurate message.
  void Function(SessionEndReason reason)? onSessionExpired;
  late final Dio dio;

  Completer<void>? _refreshCompleter;

  /// Guards against re-entrancy: once a session-end is triggered, every queued
  /// 401 from concurrent in-flight requests is ignored until the next sign-in.
  bool _endingSession = false;

  /// Re-arms session handling after a fresh sign-in. Must be called by the auth
  /// layer when new tokens are persisted, otherwise a later expiry/suspension
  /// would be silently swallowed.
  void resetSession() => _endingSession = false;

  void _endSession(SessionEndReason reason) {
    if (_endingSession) return;
    _endingSession = true;
    onSessionExpired?.call(reason);
  }

  /// True when the backend explicitly signalled the account is no longer usable.
  /// DriverService's JwtAuthFilter returns these markers in the 401 body.
  bool _isAccountDisabled(DioException error) {
    if (error.response?.statusCode != 401) return false;
    final data = error.response?.data;
    String? message;
    if (data is Map) {
      message = data['message']?.toString();
    } else if (data is String) {
      message = data;
    }
    if (message == null) return false;
    return message.contains('DRIVER_ACCOUNT_DISABLED') ||
        message.contains('INVALID_DRIVER_ID');
  }

  bool _shouldAttemptRefresh(DioException error) {
    final status = error.response?.statusCode;
    final path = error.requestOptions.path;
    if (status != 401) return false;
    // Never refresh twice for the same request — a 401 on the retried request
    // means refreshing did not help, so stop instead of looping.
    if (error.requestOptions.extra['__retried'] == true) return false;
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
      // Mark the retry so a second 401 won't kick off another refresh cycle.
      extra: {...request.extra, '__retried': true},
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
