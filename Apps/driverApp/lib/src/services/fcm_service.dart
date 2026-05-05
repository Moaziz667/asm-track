import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import 'api_client.dart';

// Top-level handler required by Firebase for background messages
@pragma('vm:entry-point')
Future<void> _firebaseBackgroundHandler(RemoteMessage message) async {
  // Background messages are shown automatically by the OS — nothing to do here
  debugPrint('[FCM] Background message: ${message.notification?.title}');
}

class FcmService {
  FcmService(this._client);

  final ApiClient _client;

  Future<void> init() async {
    FirebaseMessaging.onBackgroundMessage(_firebaseBackgroundHandler);

    // Request permission (required on iOS, harmless on Android 13+)
    final settings = await FirebaseMessaging.instance.requestPermission(
      alert: true,
      badge: true,
      sound: true,
    );
    debugPrint('[FCM] Permission: ${settings.authorizationStatus}');

    // Get token and register with backend
    await _registerToken();

    // Token can rotate — keep backend in sync
    FirebaseMessaging.instance.onTokenRefresh.listen(_sendTokenToBackend);

    // Foreground messages — show a simple debug log (OS handles background)
    FirebaseMessaging.onMessage.listen((message) {
      debugPrint('[FCM] Foreground: ${message.notification?.title} — ${message.notification?.body}');
    });
  }

  Future<void> _registerToken() async {
    try {
      final token = await FirebaseMessaging.instance.getToken();
      if (token != null) await _sendTokenToBackend(token);
    } catch (e) {
      debugPrint('[FCM] Token registration failed: $e');
    }
  }

  Future<void> _sendTokenToBackend(String token) async {
    try {
      await _client.dio.put('/api/driver/fcm-token', data: {'fcmToken': token});
      debugPrint('[FCM] Token registered with backend');
    } catch (e) {
      debugPrint('[FCM] Failed to send token to backend: $e');
    }
  }
}
