import 'dart:async';

import 'package:dio/dio.dart';

import '../config/app_config.dart';
import '../models/auth_tokens.dart';
import 'token_storage.dart';

class ApiClient {
  ApiClient({required this.config, required this.tokenStorage}) {
    dio = Dio(
      BaseOptions(
        baseUrl: config.apiBaseUrl,
        connectTimeout: const Duration(seconds: 15),
        receiveTimeout: const Duration(seconds: 25),
        sendTimeout: const Duration(seconds: 15),
        contentType: 'application/json',
      ),
    );

    dio.interceptors.add(
      QueuedInterceptorsWrapper(
        onRequest: (options, handler) async {
          final token = await tokenStorage.readAccessToken();
          if (token != null && token.isNotEmpty) {
            options.headers['Authorization'] = 'Bearer $token';
          }
          handler.next(options);
        },
        onError: (error, handler) async {
          if (_shouldAttemptRefresh(error)) {
            try {
              final response = await _refreshAndRetry(error);
              return handler.resolve(response);
            } catch (_) {
              await tokenStorage.clear();
            }
          }
          handler.next(error);
        },
      ),
    );
  }

  final AppConfig config;
  final TokenStorage tokenStorage;
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
      final tokens = AuthTokens.fromMap(data);
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
