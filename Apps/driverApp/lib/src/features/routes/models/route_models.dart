enum DriverRouteStatus {
  draft,
  validated,
  inProgress,
  closed,
}

extension DriverRouteStatusX on DriverRouteStatus {
  static DriverRouteStatus fromApi(String? value) {
    switch (value) {
      case 'DRAFT':
        return DriverRouteStatus.draft;
      case 'IN_PROGRESS':
        return DriverRouteStatus.inProgress;
      case 'CLOSED':
        return DriverRouteStatus.closed;
      case 'VALIDATED':
      default:
        return DriverRouteStatus.validated;
    }
  }

  String get label {
    switch (this) {
      case DriverRouteStatus.draft:
        return 'Draft';
      case DriverRouteStatus.validated:
        return 'Validated';
      case DriverRouteStatus.inProgress:
        return 'In progress';
      case DriverRouteStatus.closed:
        return 'Closed';
    }
  }
}

enum DriverRouteStopStatus {
  pending,
  arrived,
  completed,
  failed,
  partial,
}

extension DriverRouteStopStatusX on DriverRouteStopStatus {
  static DriverRouteStopStatus fromApi(String? value) {
    switch (value) {
      case 'ARRIVED':
        return DriverRouteStopStatus.arrived;
      case 'COMPLETED':
        return DriverRouteStopStatus.completed;
      case 'FAILED':
        return DriverRouteStopStatus.failed;
      case 'PARTIAL':
        return DriverRouteStopStatus.partial;
      case 'PENDING':
      default:
        return DriverRouteStopStatus.pending;
    }
  }

  String get label {
    switch (this) {
      case DriverRouteStopStatus.pending:
        return 'Pending';
      case DriverRouteStopStatus.arrived:
        return 'Arrived';
      case DriverRouteStopStatus.completed:
        return 'Completed';
      case DriverRouteStopStatus.failed:
        return 'Failed';
      case DriverRouteStopStatus.partial:
        return 'Partial';
    }
  }
}

class DriverRouteStop {
  const DriverRouteStop({
    required this.id,
    required this.deliveryId,
    required this.stopOrder,
    required this.status,
    this.lat,
    this.lng,
    this.address,
    this.city,
  });

  factory DriverRouteStop.fromJson(Map<String, dynamic> json) {
    return DriverRouteStop(
      id: (json['id'] ?? '').toString(),
      deliveryId: (json['deliveryId'] ?? '').toString(),
      stopOrder: (json['stopOrder'] as num?)?.toInt() ?? 0,
      status: DriverRouteStopStatusX.fromApi(json['status'] as String?),
      lat: (json['dropoffLat'] as num?)?.toDouble(),
      lng: (json['dropoffLng'] as num?)?.toDouble(),
      address: json['deliveryAddress'] as String?,
      city: json['deliveryCity'] as String?,
    );
  }

  final String id;
  final String deliveryId;
  final int stopOrder;
  final DriverRouteStopStatus status;
  final double? lat;
  final double? lng;
  final String? address;
  final String? city;

  bool get hasPinned => lat != null && lng != null;
}

class DriverRoute {
  const DriverRoute({
    required this.id,
    required this.name,
    required this.status,
    required this.stops,
    this.zone,
    this.startedAt,
  });

  factory DriverRoute.fromJson(Map<String, dynamic> json) {
    return DriverRoute(
      id: (json['id'] ?? '').toString(),
      name: (json['name'] as String?)?.trim().isNotEmpty == true
          ? json['name'] as String
          : 'Today route',
      status: DriverRouteStatusX.fromApi(json['status'] as String?),
      zone: json['zone'] as String?,
      startedAt: json['startedAt'] != null
          ? DateTime.tryParse(json['startedAt'] as String)
          : null,
      stops: (json['stops'] as List<dynamic>? ?? [])
          .map((e) => DriverRouteStop.fromJson(e as Map<String, dynamic>))
          .toList()
        ..sort((a, b) => a.stopOrder.compareTo(b.stopOrder)),
    );
  }

  final String id;
  final String name;
  final DriverRouteStatus status;
  final String? zone;
  final DateTime? startedAt;
  final List<DriverRouteStop> stops;
}
