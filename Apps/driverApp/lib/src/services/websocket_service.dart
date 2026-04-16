import 'dart:convert';

import 'package:stomp_dart_client/stomp_dart_client.dart';

class RouteWsEvent {
  const RouteWsEvent({
    required this.event,
    required this.routeId,
    required this.routeName,
  });

  factory RouteWsEvent.fromJson(Map<String, dynamic> json) {
    return RouteWsEvent(
      event: json['event'] as String? ?? '',
      routeId: json['routeId'] as String? ?? '',
      routeName: json['routeName'] as String? ?? '',
    );
  }

  final String event;
  final String routeId;
  final String routeName;
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
                onEvent(RouteWsEvent.fromJson(map));
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
