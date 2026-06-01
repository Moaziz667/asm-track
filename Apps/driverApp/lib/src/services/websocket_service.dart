import 'dart:convert';

import 'package:stomp_dart_client/stomp.dart';
import 'package:stomp_dart_client/stomp_config.dart';

class RouteWsEvent {
  const RouteWsEvent({
    required this.event,
    required this.routeId,
    required this.routeName,
    this.clientName,
    this.erpOrderId,
    this.reason,
    this.deliveryId,
    this.fromDriverName,
    this.toDriverName,
  });

  factory RouteWsEvent.fromJson(Map<String, dynamic> json) {
    return RouteWsEvent(
      event: json['event'] as String? ?? '',
      routeId: json['routeId'] as String? ?? '',
      routeName: json['routeName'] as String? ?? '',
      clientName: json['clientName'] as String?,
      erpOrderId: json['erpOrderId'] as String?,
      reason: json['reason'] as String?,
      deliveryId: json['deliveryId'] as String?,
      fromDriverName: json['fromDriverName'] as String?,
      toDriverName: json['toDriverName'] as String?,
    );
  }

  final String event;
  final String routeId;
  final String routeName;
  final String? clientName;
  final String? erpOrderId;
  final String? reason;

  // Handoff events
  final String? deliveryId;
  final String? fromDriverName;
  final String? toDriverName;
}

class WebSocketService {
  StompClient? _client;

  void connect({
    required String wsBaseUrl,
    required String token,
    required String driverId,
    required void Function(RouteWsEvent event) onEvent,
  }) {
    _client = StompClient(
      config: StompConfig.SockJS(
        url: '$wsBaseUrl/ws',
        onConnect: (frame) {
          _client?.subscribe(
            destination: '/topic/driver.$driverId',
            callback: (frame) {
              final body = frame.body;
              if (body == null || body.isEmpty) return;
              try {
                final map = jsonDecode(body) as Map<String, dynamic>;
                final data = map.containsKey('data') ? map['data'] as Map<String, dynamic> : map;
                data['event'] = map['type'] ?? data['event'];
                onEvent(RouteWsEvent.fromJson(data));
              } catch (_) {}
            },
          );
        },
        stompConnectHeaders: {'Authorization': 'Bearer $token'},
        webSocketConnectHeaders: {'Authorization': 'Bearer $token'},
        onStompError: (frame) {
          // Errors are non-fatal — polling fallback handles missed events
        },
        onDisconnect: (_) {},
        onWebSocketError: (_) {},
        reconnectDelay: const Duration(seconds: 10),
      ),
    );
    _client?.activate();
  }

  void disconnect() {
    _client?.deactivate();
    _client = null;
  }
}
