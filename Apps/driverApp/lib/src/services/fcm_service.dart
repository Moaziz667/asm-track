import 'package:dio/dio.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' show VoidCallback;
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api_client.dart';

// ── Localized Notification Templates ──────────────────────────────────────────

const Map<String, Map<String, Map<String, String>>> notificationTemplates = {
  'fr': {
    'DELIVERY_ASSIGNED': {
      'title': '📦 Nouvelle livraison',
      'body': 'La commande {ref} est prête à être récupérée.',
    },
    'DELIVERY_REMOVED': {
      'title': '🚫 Livraison retirée',
      'body': 'La commande {ref} a été retirée de votre tournée.',
    },
    'HANDOFF_REQUIRED': {
      'title': '🔄 Passation requise',
      'body': 'Veuillez remettre le colis {ref} au nouveau livreur.',
    },
    'ROUTE_VALIDATED': {
      'title': '✅ Tournée validée',
      'body': 'Votre tournée {routeName} est prête. Consultez votre itinéraire.',
    },
    'ROUTE_SCHEDULE_CHANGED': {
      'title': '📅 Horaire mis à jour',
      'body': 'Les horaires de votre tournée {routeName} ont été modifiés.',
    },
    'ROUTE_STOP_ADDED': {
      'title': '📍 Arrêt ajouté',
      'body': '{clientName} a été ajouté à votre tournée {routeName}.',
    },
    'ROUTE_STOP_REMOVED': {
      'title': '❌ Arrêt retiré',
      'body': '{clientName} a été retiré de votre tournée {routeName}.',
    },
    'STOPS_TRANSFERRED_OUT': {
      'title': '🔄 Transfert sortant',
      'body': '{count} arrêts ont été retirés de votre tournée.',
    },
    'STOPS_TRANSFERRED_IN': {
      'title': '🔄 Transfert entrant',
      'body': '{count} arrêts ont été ajoutés à votre tournée.',
    },
    'ROUTE_UPDATED': {
      'title': '🔄 Tournée modifiée',
      'body': 'Votre tournée a été mise à jour.',
    },
  },
  'en': {
    'DELIVERY_ASSIGNED': {
      'title': '📦 New Delivery Assigned',
      'body': 'Order {ref} is ready for pickup.',
    },
    'DELIVERY_REMOVED': {
      'title': '🚫 Delivery Removed',
      'body': 'Order {ref} has been removed from your route.',
    },
    'HANDOFF_REQUIRED': {
      'title': '🔄 Handover Required',
      'body': 'Please hand over package {ref} to the new driver.',
    },
    'ROUTE_VALIDATED': {
      'title': '✅ Route Validated',
      'body': 'Your route {routeName} is validated and ready.',
    },
    'ROUTE_SCHEDULE_CHANGED': {
      'title': '📅 Schedule Updated',
      'body': 'The schedule for route {routeName} has changed.',
    },
    'ROUTE_STOP_ADDED': {
      'title': '📍 Stop Added',
      'body': '{clientName} has been added to your route {routeName}.',
    },
    'ROUTE_STOP_REMOVED': {
      'title': '❌ Stop Removed',
      'body': '{clientName} has been removed from route {routeName}.',
    },
    'STOPS_TRANSFERRED_OUT': {
      'title': '🔄 Stops Transferred Out',
      'body': '{count} stops have been removed from your route.',
    },
    'STOPS_TRANSFERRED_IN': {
      'title': '🔄 Stops Transferred In',
      'body': '{count} stops have been added to your route.',
    },
    'ROUTE_UPDATED': {
      'title': '🔄 Route Updated',
      'body': 'Your route has been updated.',
    },
  },
  'ar': {
    'DELIVERY_ASSIGNED': {
      'title': '📦 شحنة جديدة معينة',
      'body': 'الطلب {ref} جاهز للاستلام.',
    },
    'DELIVERY_REMOVED': {
      'title': '🚫 إزالة شحنة',
      'body': 'تمت إزالة الطلب {ref} من مسار رحلتك.',
    },
    'HANDOFF_REQUIRED': {
      'title': '🔄 تسليم مطلوب',
      'body': 'يرجى تسليم الطرد {ref} إلى السائق الجديد.',
    },
    'ROUTE_VALIDATED': {
      'title': '✅ تم تأكيد الرحلة',
      'body': 'رحلتك {routeName} جاهزة ومؤكدة.',
    },
    'ROUTE_SCHEDULE_CHANGED': {
      'title': '📅 تحديث الجدول الزمني',
      'body': 'تم تعديل مواعيد رحلتك {routeName}.',
    },
    'ROUTE_STOP_ADDED': {
      'title': '📍 إضافة محطة',
      'body': 'تم إضافة {clientName} إلى رحلتك {routeName}.',
    },
    'ROUTE_STOP_REMOVED': {
      'title': '❌ إزالة محطة',
      'body': 'تم إزالة {clientName} من رحلتك {routeName}.',
    },
    'STOPS_TRANSFERRED_OUT': {
      'title': '🔄 نقل محطات للخارج',
      'body': 'تم إزالة {count} محطات من رحلتك.',
    },
    'STOPS_TRANSFERRED_IN': {
      'title': '🔄 استلام محطات',
      'body': 'تم إضافة {count} محطات إلى رحلتك.',
    },
    'ROUTE_UPDATED': {
      'title': '🔄 تحديث الرحلة',
      'body': 'تم تحديث مسار رحلتك.',
    },
  },
};

Map<String, String> getLocalizedNotificationPayload(Map<String, dynamic> data, String locale) {
  final eventType = data['event_type'] as String? ?? data['event'] as String? ?? 'ROUTE_UPDATED';
  final templates = notificationTemplates[locale] ?? notificationTemplates['fr']!;
  final template = templates[eventType] ?? {
    'title': '🔔 ASMTrack Driver',
    'body': 'Mise à jour opérationnelle',
  };

  var title = template['title']!;
  var body = template['body']!;

  data.forEach((key, val) {
    title = title.replaceAll('{$key}', val?.toString() ?? '');
    body = body.replaceAll('{$key}', val?.toString() ?? '');
  });

  return {
    'title': title,
    'body': body,
    'type': eventType,
  };
}

// ── Background handler (top-level, required by Firebase) ─────────────────────

@pragma('vm:entry-point')
Future<void> _firebaseBackgroundHandler(RemoteMessage message) async {
  debugPrint('[FCM] Background message received: ${message.data}');
  final data = message.data;
  if (data.isEmpty) return;

  final prefs = await SharedPreferences.getInstance();
  final locale = prefs.getString('driver_locale') ?? 'fr';

  final localized = getLocalizedNotificationPayload(data, locale);

  await _localNotifications.show(
    message.hashCode,
    localized['title'],
    localized['body'],
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
    FirebaseMessaging.onMessageOpenedApp.listen((message) async {
      await _storeMessage(message);
      _onNotificationTap?.call();
    });

    // 8. Terminated tap → app was killed, user tapped notification
    final initial = await FirebaseMessaging.instance.getInitialMessage();
    if (initial != null) {
      await _storeMessage(initial);
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
    debugPrint('[FCM] Foreground message received: ${message.data}');
    final data = message.data;
    if (data.isEmpty) return;

    final prefs = await SharedPreferences.getInstance();
    final locale = prefs.getString('driver_locale') ?? 'fr';
    final localized = getLocalizedNotificationPayload(data, locale);

    await _storeMessage(message);

    if (defaultTargetPlatform == TargetPlatform.android) {
      await _localNotifications.show(
        message.hashCode,
        localized['title'],
        localized['body'],
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

  Future<void> _storeMessage(RemoteMessage message) async {
    final data = message.data;
    if (data.isEmpty) return;

    final prefs = await SharedPreferences.getInstance();
    final locale = prefs.getString('driver_locale') ?? 'fr';
    final localized = getLocalizedNotificationPayload(data, locale);

    _onNotificationReceived?.call(
      localized['title']!,
      localized['body']!,
      localized['type']!,
    );
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

  /// Call on logout: removes the FCM token from Firebase and clears it on the
  /// backend so the server stops sending push notifications to this device.
  Future<void> deregister() async {
    // 1. Tell the backend to clear the stored token first (while we still have
    //    a valid JWT). Send an empty token to signal "clear this device".
    try {
      await _client.dio.delete('/api/driver/fcm-token');
      debugPrint('[FCM] Token cleared on backend');
    } catch (e) {
      // Non-fatal — the backend token will expire on its own or be overwritten
      // the next time the driver logs in on any device.
      debugPrint('[FCM] Could not clear token on backend: $e');
    }

    // 2. Delete the token from Firebase so this installation stops receiving
    //    notifications immediately, regardless of backend state.
    try {
      await FirebaseMessaging.instance.deleteToken();
      debugPrint('[FCM] Firebase token deleted');
    } catch (e) {
      debugPrint('[FCM] Could not delete Firebase token: $e');
    }
  }
}
