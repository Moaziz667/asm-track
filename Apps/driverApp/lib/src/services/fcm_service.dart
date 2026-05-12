import 'package:dio/dio.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' show VoidCallback;
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

  void Function(String title, String body, String type)? _onNotificationReceived;
  VoidCallback? _onNotificationTap;

  void setHandlers({
    required void Function(String title, String body, String type) onReceived,
    required VoidCallback onTap,
  }) {
    _onNotificationReceived = onReceived;
    _onNotificationTap = onTap;
  }

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

    // 6. Foreground messages → show banner + store
    FirebaseMessaging.onMessage.listen(_handleForegroundMessage);

    // 7. Background tap → app was in background, user tapped notification
    FirebaseMessaging.onMessageOpenedApp.listen((message) {
      _storeMessage(message);
      _onNotificationTap?.call();
    });

    // 8. Terminated tap → app was killed, user tapped notification
    final initial = await FirebaseMessaging.instance.getInitialMessage();
    if (initial != null) {
      _storeMessage(initial);
      Future.delayed(const Duration(milliseconds: 600), () => _onNotificationTap?.call());
    }
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
    _storeMessage(message);

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

  void _storeMessage(RemoteMessage message) {
    final title = message.notification?.title ?? message.data['title'] as String? ?? '';
    final body = message.notification?.body ?? message.data['body'] as String? ?? '';
    final type = message.data['type'] as String? ?? 'GENERAL';
    if (title.isNotEmpty) {
      _onNotificationReceived?.call(title, body, type);
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
