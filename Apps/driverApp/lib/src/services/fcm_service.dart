import 'package:dio/dio.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

import 'api_client.dart';

// ── Background handler (top-level, required by Firebase) ─────────────────────

@pragma('vm:entry-point')
Future<void> _firebaseBackgroundHandler(RemoteMessage message) async {
  // OS shows the notification automatically — nothing to do here.
  debugPrint('[FCM] Background: ${message.notification?.title}');
}

// ── Android notification channel ──────────────────────────────────────────────

const _channel = AndroidNotificationChannel(
  'asmtrack_high',
  'ASMTrack — Notifications',
  description: 'Notifications opérationnelles ASMTrack Driver',
  importance: Importance.high,
  enableVibration: true,
  playSound: true,
);

final _localNotifications = FlutterLocalNotificationsPlugin();

// ── Service ───────────────────────────────────────────────────────────────────

class FcmService {
  FcmService(this._client);

  final ApiClient _client;

  Future<void> init() async {
    // 1. Register background handler
    FirebaseMessaging.onBackgroundMessage(_firebaseBackgroundHandler);

    // 2. Request permission (required on iOS + Android 13+)
    final settings = await FirebaseMessaging.instance.requestPermission(
      alert: true,
      badge: true,
      sound: true,
    );
    debugPrint('[FCM] Permission: ${settings.authorizationStatus}');

    // 3. iOS: show notification banner even when app is in foreground
    await FirebaseMessaging.instance.setForegroundNotificationPresentationOptions(
      alert: true,
      badge: true,
      sound: true,
    );

    // 4. Android: create high-priority notification channel + init local notifications
    await _initLocalNotifications();

    // 5. Register FCM token with backend
    await _registerToken();
    FirebaseMessaging.instance.onTokenRefresh.listen(_sendTokenToBackend);

    // 6. Android foreground messages → show via local notifications
    FirebaseMessaging.onMessage.listen(_handleForegroundMessage);
  }

  Future<void> _initLocalNotifications() async {
    const androidInit = AndroidInitializationSettings('@mipmap/ic_launcher');
    const iosInit = DarwinInitializationSettings();
    await _localNotifications.initialize(
      const InitializationSettings(android: androidInit, iOS: iosInit),
    );

    // Create the channel on Android (no-op on iOS)
    final androidPlugin = _localNotifications
        .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>();
    await androidPlugin?.createNotificationChannel(_channel);
  }

  Future<void> _handleForegroundMessage(RemoteMessage message) async {
    final notification = message.notification;
    if (notification == null) return;

    debugPrint('[FCM] Foreground: ${notification.title} — ${notification.body}');

    // On iOS, setForegroundNotificationPresentationOptions handles this.
    // On Android, we must show it ourselves via local notifications.
    if (defaultTargetPlatform == TargetPlatform.android) {
      await _localNotifications.show(
        notification.hashCode,
        notification.title,
        notification.body,
        NotificationDetails(
          android: AndroidNotificationDetails(
            _channel.id,
            _channel.name,
            channelDescription: _channel.description,
            importance: Importance.high,
            priority: Priority.high,
            icon: '@mipmap/ic_launcher',
          ),
        ),
      );
    }
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
    } on DioException catch (e) {
      // 403 is expected before the driver logs in — the token is retried on login
      if (e.response?.statusCode == 403) return;
      debugPrint('[FCM] Failed to send token to backend: $e');
    } catch (e) {
      debugPrint('[FCM] Failed to send token to backend: $e');
    }
  }
}
