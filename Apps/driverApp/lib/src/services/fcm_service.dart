import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api_client.dart';

// ── Notification content builder ──────────────────────────────────────────────
// Notifications are assembled on-device from the rich data payload the backend
// sends (clientName, address, COD, ETA, stop counts…) and localized to the
// driver's app language. Each segment is appended only when its data is present,
// so a message never shows an empty reference or a dangling separator.

const Set<String> notificationLocales = {'fr', 'en', 'ar'};

String _pick(String locale, {required String fr, required String en, required String ar}) {
  switch (locale) {
    case 'en':
      return en;
    case 'ar':
      return ar;
    default:
      return fr;
  }
}

/// Order reference for display: the ERP order id when available, otherwise a
/// short slice of the delivery UUID. Returns '' when nothing usable is present.
String _orderRef(Map<String, dynamic> d) {
  final erp = (d['erpOrderId'] ?? '').toString().trim();
  if (erp.isNotEmpty) return erp;
  final id = (d['deliveryId'] ?? '').toString().trim();
  return id.length >= 8 ? id.substring(0, 8) : id;
}

/// "HH:mm" from an ISO datetime; null when unparseable.
String? _fmtTime(dynamic iso) {
  if (iso == null) return null;
  final dt = DateTime.tryParse(iso.toString());
  if (dt == null) return null;
  final l = dt.toLocal();
  return '${l.hour.toString().padLeft(2, '0')}:${l.minute.toString().padLeft(2, '0')}';
}

/// "HH:mm" from a backend LocalTime string ("HH:mm:ss"); null otherwise.
/// Guards against non-string (e.g. array) serializations by returning null.
String? _fmtClock(dynamic v) {
  if (v is! String) return null;
  final m = RegExp(r'^(\d{2}):(\d{2})').firstMatch(v);
  return m != null ? '${m.group(1)}:${m.group(2)}' : null;
}

String _stopsLabel(int n, String locale) {
  switch (locale) {
    case 'en':
      return '$n stop${n > 1 ? 's' : ''}';
    case 'ar':
      return '$n محطة';
    default:
      return '$n arrêt${n > 1 ? 's' : ''}';
  }
}

/// Builds the localized title/body for an event from its data payload.
Map<String, String> _buildNotification(
    String eventType, Map<String, dynamic> d, String locale) {
  final ref = _orderRef(d);
  final client = (d['clientName'] ?? '').toString().trim();
  final address = (d['dropoffAddress'] ?? '').toString().trim();
  final routeName = (d['routeName'] ?? '').toString().trim();
  final reason = (d['reason'] ?? '').toString().trim();
  final eta = _fmtTime(d['etaAt']);
  final startClock = _fmtClock(d['plannedStartTime']);
  final endClock = _fmtClock(d['plannedEndTime']);
  final stopCount =
      (d['stopCount'] is num) ? (d['stopCount'] as num).toInt() : int.tryParse('${d['stopCount']}');
  final count = (d['count'] is num) ? (d['count'] as num).toInt() : int.tryParse('${d['count']}');
  final fromDriverName = (d['fromDriverName'] ?? '').toString().trim();
  final toDriverName = (d['toDriverName'] ?? '').toString().trim();

  // "#ref · client", omitting whichever segment is missing.
  String identity() {
    final parts = <String>[];
    if (ref.isNotEmpty) parts.add('#$ref');
    if (client.isNotEmpty) parts.add(client);
    return parts.join(' · ');
  }

  String routeLabel() => routeName.isNotEmpty
      ? routeName
      : _pick(locale, fr: 'votre tournée', en: 'your route', ar: 'رحلتك');

  String joinLines(List<String?> lines) =>
      lines.where((l) => l != null && l.trim().isNotEmpty).cast<String>().join('\n');

  switch (eventType) {
    case 'DELIVERY_ASSIGNED':
      final id = identity();
      return {
        'title': _pick(locale, fr: '📦 Nouvelle livraison', en: '📦 New delivery', ar: '📦 شحنة جديدة'),
        'body': joinLines([
          id.isNotEmpty
              ? id
              : _pick(locale,
                  fr: 'Nouvelle livraison à récupérer.',
                  en: 'A new delivery to pick up.',
                  ar: 'شحنة جديدة للاستلام.'),
          address.isNotEmpty ? '📍 $address' : null,
          eta != null
              ? _pick(locale, fr: '🕒 ETA $eta', en: '🕒 ETA $eta', ar: '🕒 الوصول المتوقع $eta')
              : null,
        ]),
      };

    case 'DELIVERY_REMOVED':
      final id = identity();
      return {
        'title': _pick(locale, fr: '🚫 Livraison retirée', en: '🚫 Delivery removed', ar: '🚫 إزالة شحنة'),
        'body': id.isNotEmpty
            ? _pick(locale,
                fr: '$id — retirée de votre tournée.',
                en: '$id — removed from your route.',
                ar: '$id — أُزيلت من رحلتك.')
            : _pick(locale,
                fr: 'Une livraison a été retirée de votre tournée.',
                en: 'A delivery was removed from your route.',
                ar: 'أُزيلت إحدى الشحنات من رحلتك.'),
      };

    case 'HANDOFF_REQUIRED':
      final hasInfo = ref.isNotEmpty || client.isNotEmpty;
      final desc = StringBuffer();
      if (ref.isNotEmpty) desc.write(' #$ref');
      if (client.isNotEmpty) desc.write(' ($client)');
      return {
        'title': _pick(locale, fr: '🔄 Passation requise', en: '🔄 Handover required', ar: '🔄 تسليم مطلوب'),
        'body': hasInfo
            ? _pick(locale,
                fr: 'Remettez le colis$desc au nouveau livreur.',
                en: 'Hand over package$desc to the new driver.',
                ar: 'سلّم الطرد$desc إلى السائق الجديد.')
            : _pick(locale,
                fr: 'Un colis doit être remis au nouveau livreur.',
                en: 'A package must be handed over to the new driver.',
                ar: 'يجب تسليم طرد إلى السائق الجديد.'),
      };

    case 'ROUTE_VALIDATED':
      return {
        'title': _pick(locale, fr: '✅ Tournée validée', en: '✅ Route validated', ar: '✅ تم تأكيد الرحلة'),
        'body': joinLines([
          _pick(locale,
              fr: 'Tournée ${routeLabel()} validée.',
              en: 'Route ${routeLabel()} validated.',
              ar: 'تم تأكيد الرحلة ${routeLabel()}.'),
          (stopCount != null && stopCount > 0)
              ? _pick(locale,
                  fr: '${_stopsLabel(stopCount, locale)} à livrer',
                  en: '${_stopsLabel(stopCount, locale)} to deliver',
                  ar: '${_stopsLabel(stopCount, locale)} للتسليم')
              : null,
          startClock != null
              ? _pick(locale,
                  fr: '🕒 Départ prévu $startClock',
                  en: '🕒 Planned start $startClock',
                  ar: '🕒 الانطلاق المقرر $startClock')
              : null,
        ]),
      };

    case 'ROUTE_SCHEDULE_CHANGED':
      final window = (startClock != null && endClock != null) ? '$startClock–$endClock' : null;
      return {
        'title': _pick(locale, fr: '📅 Horaire mis à jour', en: '📅 Schedule updated', ar: '📅 تحديث الموعد'),
        'body': window != null
            ? _pick(locale,
                fr: 'Nouveaux horaires pour ${routeLabel()} : $window.',
                en: 'New schedule for ${routeLabel()}: $window.',
                ar: 'مواعيد جديدة لـ ${routeLabel()}: $window.')
            : _pick(locale,
                fr: 'Les horaires de ${routeLabel()} ont changé.',
                en: 'The schedule for ${routeLabel()} has changed.',
                ar: 'تغيّرت مواعيد ${routeLabel()}.'),
      };

    case 'PICKUP_OVERDUE':
      return {
        'title': _pick(locale, fr: '⏰ Chargement en retard', en: '⏰ Pickup overdue', ar: '⏰ تأخر التحميل'),
        'body': joinLines([
          _pick(locale,
              fr: 'Dépôt ${client.isNotEmpty ? client : routeLabel()}${reason.isNotEmpty ? ' ($reason)' : ''}',
              en: 'Depot ${client.isNotEmpty ? client : routeLabel()}${reason.isNotEmpty ? ' ($reason)' : ''}',
              ar: 'مستودع ${client.isNotEmpty ? client : routeLabel()}${reason.isNotEmpty ? ' ($reason)' : ''}'),
          _pick(locale,
              fr: 'Confirmez le chargement pour continuer.',
              en: 'Confirm the pickup to continue.',
              ar: 'أكّد التحميل للمتابعة.'),
        ]),
      };
    case 'ROUTE_STOP_ADDED':
      final who = client.isNotEmpty ? client : _pick(locale, fr: 'Un arrêt', en: 'A stop', ar: 'محطة');
      return {
        'title': _pick(locale, fr: '📍 Arrêt ajouté', en: '📍 Stop added', ar: '📍 إضافة محطة'),
        'body': joinLines([
          _pick(locale,
              fr: '$who ajouté à ${routeLabel()}.',
              en: '$who added to ${routeLabel()}.',
              ar: 'تمت إضافة $who إلى ${routeLabel()}.'),
          (stopCount != null && stopCount > 0)
              ? _pick(locale,
                  fr: 'Total : ${_stopsLabel(stopCount, locale)}',
                  en: 'Total: ${_stopsLabel(stopCount, locale)}',
                  ar: 'الإجمالي: ${_stopsLabel(stopCount, locale)}')
              : null,
        ]),
      };

    case 'ROUTE_STOP_REMOVED':
      final who = client.isNotEmpty ? client : _pick(locale, fr: 'Un arrêt', en: 'A stop', ar: 'محطة');
      return {
        'title': _pick(locale, fr: '❌ Arrêt retiré', en: '❌ Stop removed', ar: '❌ إزالة محطة'),
        'body': joinLines([
          _pick(locale,
              fr: '$who retiré de ${routeLabel()}.',
              en: '$who removed from ${routeLabel()}.',
              ar: 'أُزيل $who من ${routeLabel()}.'),
          reason.isNotEmpty
              ? _pick(locale, fr: 'Motif : $reason', en: 'Reason: $reason', ar: 'السبب: $reason')
              : null,
        ]),
      };

    case 'STOPS_TRANSFERRED_OUT':
      final n = count ?? 0;
      return {
        'title': _pick(locale, fr: '🔄 Transfert sortant', en: '🔄 Stops transferred out', ar: '🔄 نقل محطات'),
        'body': _pick(locale,
            fr: '${_stopsLabel(n, locale)} retiré${n > 1 ? 's' : ''} de votre tournée.',
            en: '${_stopsLabel(n, locale)} removed from your route.',
            ar: 'تمت إزالة ${_stopsLabel(n, locale)} من رحلتك.'),
      };

    case 'STOPS_TRANSFERRED_IN':
      final n = count ?? 0;
      return {
        'title': _pick(locale, fr: '🔄 Transfert entrant', en: '🔄 Stops transferred in', ar: '🔄 استلام محطات'),
        'body': _pick(locale,
            fr: '${_stopsLabel(n, locale)} ajouté${n > 1 ? 's' : ''} à votre tournée.',
            en: '${_stopsLabel(n, locale)} added to your route.',
            ar: 'تمت إضافة ${_stopsLabel(n, locale)} إلى رحلتك.'),
      };

    case 'HANDOFF_INCOMING':
      {
        final id = identity();
        final fromPart = fromDriverName.isNotEmpty
            ? _pick(locale, fr: ' de $fromDriverName', en: ' from $fromDriverName', ar: ' من $fromDriverName')
            : '';
        return {
          'title': _pick(locale, fr: '🤝 Réception de colis', en: '🤝 Incoming handover', ar: '🤝 استلام طرد'),
          'body': joinLines([
            id.isNotEmpty
                ? _pick(locale,
                    fr: 'Recevez le colis $id$fromPart.',
                    en: 'Receive parcel $id$fromPart.',
                    ar: 'استلم الطرد $id$fromPart.')
                : _pick(locale,
                    fr: 'Un colis doit vous être remis$fromPart.',
                    en: 'A parcel is being handed to you$fromPart.',
                    ar: 'سيتم تسليمك طردًا$fromPart.'),
            address.isNotEmpty ? '📍 $address' : null,
            _pick(locale,
                fr: 'Scannez le code du chauffeur pour confirmer.',
                en: "Scan the sender's code to confirm.",
                ar: 'امسح رمز السائق للتأكيد.'),
          ]),
        };
      }

    case 'HANDOFF_OUTGOING':
      {
        final id = identity();
        final toPart = toDriverName.isNotEmpty
            ? _pick(locale, fr: ' à $toDriverName', en: ' to $toDriverName', ar: ' إلى $toDriverName')
            : _pick(locale, fr: ' au nouveau livreur', en: ' to the new driver', ar: ' إلى السائق الجديد');
        return {
          'title': _pick(locale, fr: '🤝 Remise de colis', en: '🤝 Hand over parcel', ar: '🤝 تسليم طرد'),
          'body': joinLines([
            id.isNotEmpty
                ? _pick(locale,
                    fr: 'Remettez le colis $id$toPart.',
                    en: 'Hand over parcel $id$toPart.',
                    ar: 'سلّم الطرد $id$toPart.')
                : _pick(locale,
                    fr: 'Remettez le colis$toPart.',
                    en: 'Hand over the parcel$toPart.',
                    ar: 'سلّم الطرد$toPart.'),
            _pick(locale,
                fr: 'Affichez votre code de transfert.',
                en: 'Show your handover code.',
                ar: 'اعرض رمز التسليم الخاص بك.'),
          ]),
        };
      }

    case 'HANDOFF_CONFIRMED':
      {
        final id = identity();
        return {
          'title': _pick(locale, fr: '✅ Transfert confirmé', en: '✅ Handover confirmed', ar: '✅ تم تأكيد التسليم'),
          'body': id.isNotEmpty
              ? _pick(locale,
                  fr: 'Colis $id — transfert confirmé.',
                  en: 'Parcel $id — handover confirmed.',
                  ar: 'الطرد $id — تم تأكيد التسليم.')
              : _pick(locale,
                  fr: 'Le transfert du colis a été confirmé.',
                  en: 'The parcel handover was confirmed.',
                  ar: 'تم تأكيد تسليم الطرد.'),
        };
      }

    case 'HANDOFF_CANCELLED':
      {
        final id = identity();
        return {
          'title': _pick(locale, fr: '⚠️ Transfert annulé', en: '⚠️ Handover cancelled', ar: '⚠️ أُلغي التسليم'),
          'body': id.isNotEmpty
              ? _pick(locale,
                  fr: 'Le transfert du colis $id a été annulé.',
                  en: 'The handover of parcel $id was cancelled.',
                  ar: 'أُلغي تسليم الطرد $id.')
              : _pick(locale,
                  fr: 'Le transfert du colis a été annulé.',
                  en: 'The parcel handover was cancelled.',
                  ar: 'أُلغي تسليم الطرد.'),
        };
      }

    case 'ROUTE_UPDATED':
    default:
      return {
        'title': _pick(locale, fr: '🔄 Tournée modifiée', en: '🔄 Route updated', ar: '🔄 تحديث الرحلة'),
        'body': _pick(locale,
            fr: 'Votre tournée a été mise à jour.',
            en: 'Your route has been updated.',
            ar: 'تم تحديث رحلتك.'),
      };
  }
}

Map<String, String> getLocalizedNotificationPayload(Map<String, dynamic> rawData, String locale) {
  Map<String, dynamic> data = {};
  if (rawData.containsKey('payload')) {
    try {
      final cloudEvent = jsonDecode(rawData['payload']) as Map<String, dynamic>;
      data = cloudEvent['data'] as Map<String, dynamic>? ?? {};
    } catch (_) {}
  }

  final eventType = rawData['event_type'] as String? ?? data['event'] as String? ?? 'ROUTE_UPDATED';
  // Merge root-level fields (some events send flat data, not a nested payload).
  // Nested payload fields take precedence over root-level ones.
  final merged = <String, dynamic>{...rawData, ...data};
  final lang = notificationLocales.contains(locale) ? locale : 'fr';

  final content = _buildNotification(eventType, merged, lang);

  return {
    'title': content['title']!,
    'body': content['body']!,
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
        // Expand multi-line bodies so the full, enriched message is readable.
        styleInformation: BigTextStyleInformation(
          localized['body'] ?? '',
          contentTitle: localized['title'],
        ),
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
  void Function(String type, String? deliveryId)? _onNotificationTap;

  void setHandlers({
    required void Function(String title, String body, String type) onReceived,
    required void Function(String type, String? deliveryId) onTap,
  }) {
    _onNotificationReceived = onReceived;
    _onNotificationTap = onTap;
  }

  /// Parses the tapped message for deep-link routing (event type + deliveryId).
  void _notifyTap(Map<String, dynamic> rawData) {
    Map<String, dynamic> data = {};
    if (rawData.containsKey('payload')) {
      try {
        data = (jsonDecode(rawData['payload']) as Map<String, dynamic>)['data'] as Map<String, dynamic>? ?? {};
      } catch (_) {}
    }
    final type = rawData['event_type'] as String? ?? data['event'] as String? ?? '';
    final deliveryId = (data['deliveryId'] ?? rawData['deliveryId'])?.toString();
    _onNotificationTap?.call(type, deliveryId);
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
      _notifyTap(message.data);
    });

    // 8. Terminated tap → app was killed, user tapped notification
    final initial = await FirebaseMessaging.instance.getInitialMessage();
    if (initial != null) {
      await _storeMessage(initial);
      Future.delayed(const Duration(milliseconds: 600), () => _notifyTap(initial.data));
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
            // Expand multi-line bodies so the full, enriched message is readable.
            styleInformation: BigTextStyleInformation(
              localized['body'] ?? '',
              contentTitle: localized['title'],
            ),
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
