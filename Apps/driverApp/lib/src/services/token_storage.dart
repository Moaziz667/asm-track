import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../models/auth_tokens.dart';

class TokenStorage {
  TokenStorage() : _secureStorage = const FlutterSecureStorage();

  final FlutterSecureStorage _secureStorage;
  AuthTokens? _cached;

  static const _kAccessTokenKey = 'access_token';
  static const _kRefreshTokenKey = 'refresh_token';
  static const _kIdTokenKey = 'id_token';
  static const _kTokenTypeKey = 'token_type';
  static const _kExpiresAtKey = 'expires_at';
  static const _kApiBaseUrlKey = 'api_base_url';
  static const _kDbEncryptionKey = 'db_encryption_key';

  Future<AuthTokens?> readTokens() async {
    if (_cached != null) {
      return _cached;
    }
    final access = await _secureStorage.read(key: _kAccessTokenKey);
    final refresh = await _secureStorage.read(key: _kRefreshTokenKey);
    if (access == null || refresh == null) {
      return null;
    }
    final idToken = await _secureStorage.read(key: _kIdTokenKey);
    final tokenType = await _secureStorage.read(key: _kTokenTypeKey) ?? 'Bearer';
    final expiresIso = await _secureStorage.read(key: _kExpiresAtKey);
    final expiresAt = expiresIso != null ? DateTime.tryParse(expiresIso) : null;
    _cached = AuthTokens(
      accessToken: access,
      refreshToken: refresh,
      idToken: idToken,
      tokenType: tokenType,
      expiresAt: expiresAt,
    );
    return _cached;
  }

  Future<void> saveTokens(AuthTokens tokens) async {
    _cached = tokens;
    await _secureStorage.write(key: _kAccessTokenKey, value: tokens.accessToken);
    await _secureStorage.write(key: _kRefreshTokenKey, value: tokens.refreshToken);
    await _secureStorage.write(key: _kIdTokenKey, value: tokens.idToken ?? '');
    await _secureStorage.write(key: _kTokenTypeKey, value: tokens.tokenType);
    await _secureStorage.write(key: _kExpiresAtKey, value: tokens.expiresAt?.toIso8601String());
  }

  Future<void> clear() async {
    _cached = null;
    await _secureStorage.delete(key: _kAccessTokenKey);
    await _secureStorage.delete(key: _kRefreshTokenKey);
    await _secureStorage.delete(key: _kIdTokenKey);
    await _secureStorage.delete(key: _kTokenTypeKey);
    await _secureStorage.delete(key: _kExpiresAtKey);
  }

  Future<String?> readAccessToken() async => (await readTokens())?.accessToken;
  Future<String?> readRefreshToken() async => (await readTokens())?.refreshToken;
  Future<String?> readIdToken() async => (await readTokens())?.idToken;

  Future<String?> readApiBaseUrl() async {
    return await _secureStorage.read(key: _kApiBaseUrlKey);
  }

  Future<void> saveApiBaseUrl(String url) async {
    await _secureStorage.write(key: _kApiBaseUrlKey, value: url);
  }

  Future<List<int>> getOrCreateDbKey() async {
    final base64Key = await _secureStorage.read(key: _kDbEncryptionKey);
    if (base64Key != null && base64Key.isNotEmpty) {
      try {
        final decoded = base64Decode(base64Key);
        if (decoded.length == 32) {
          return decoded;
        } else if (decoded.length > 32) {
          return decoded.sublist(0, 32);
        }
      } catch (_) {
        // Fall through and generate fresh key
      }
    }
    final random = Random.secure();
    final freshKey = Uint8List.fromList(List<int>.generate(32, (_) => random.nextInt(256)));
    await _secureStorage.write(key: _kDbEncryptionKey, value: base64Encode(freshKey));
    return freshKey;
  }
}

