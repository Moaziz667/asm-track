import 'dart:convert';

import 'package:stomp_dart_client/stomp.dart';
import 'package:stomp_dart_client/stomp_config.dart';

import 'token_storage.dart';

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
  String? _wsBaseUrl;
  String? _driverId;
  void Function(RouteWsEvent event)? _onEvent;
  TokenStorage? _tokenStorage;
  bool _isConnecting = false;

  void connect({
    required String wsBaseUrl,
    required String driverId,
    required TokenStorage tokenStorage,
    required void Function(RouteWsEvent event) onEvent,
  }) {
    _wsBaseUrl = wsBaseUrl;
    _driverId = driverId;
    _tokenStorage = tokenStorage;
    _onEvent = onEvent;

    _establishConnection();
  }

  Future<void> _establishConnection() async {
    if (_isConnecting || _client != null) return;
    _isConnecting = true;
    try {
      final token = await _tokenStorage?.readAccessToken();
      if (token == null || token.isEmpty) {
        _isConnecting = false;
        return;
      }

      _client = StompClient(
        config: StompConfig.SockJS(
          url: '$_wsBaseUrl/ws',
          onConnect: (frame) {
            _client?.subscribe(
              destination: '/topic/driver.$_driverId',
              callback: (frame) {
                final body = frame.body;
                if (body == null || body.isEmpty) return;
                try {
                  final map = jsonDecode(body) as Map<String, dynamic>;
                  final data = map.containsKey('data') ? map['data'] as Map<String, dynamic> : map;
                  data['event'] = map['type'] ?? data['event'];
                  _onEvent?.call(RouteWsEvent.fromJson(data));
                } catch (_) {}
              },
            );
          },
          stompConnectHeaders: {'Authorization': 'Bearer $token'},
          webSocketConnectHeaders: {'Authorization': 'Bearer $token'},
          onStompError: (frame) {
            _handleReconnectError();
          },
          onDisconnect: (_) {},
          onWebSocketError: (error) {
            _handleReconnectError();
          },
          reconnectDelay: const Duration(seconds: 10),
        ),
      );
      _client?.activate();
    } catch (_) {
      // Connection initialization failed, will retry on next check
    } finally {
      _isConnecting = false;
    }
  }

  void _handleReconnectError() async {
    // If the socket connection fails (e.g. 401 Unauthorized because JWT expired),
    // we deactivate the client, pull a fresh token, and re-establish a handshake.
    if (_client != null) {
      disconnect();
      await Future.delayed(const Duration(seconds: 5));
      _establishConnection();
    }
  }

  void disconnect() {
    _client?.deactivate();
    _client = null;
  }
}
