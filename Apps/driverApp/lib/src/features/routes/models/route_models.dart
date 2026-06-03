import 'package:latlong2/latlong.dart';
import '../../../features/deliveries/models/delivery_models.dart';

enum DriverRouteStatus {
  draft,
  validated,
  inProgress,
  closed,
  cancelled,
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
      case 'CANCELLED':
        return DriverRouteStatus.cancelled;
      case 'VALIDATED':
      default:
        return DriverRouteStatus.validated;
    }
  }

  String get label {
    switch (this) {
      case DriverRouteStatus.draft:
        return 'Brouillon';
      case DriverRouteStatus.validated:
        return 'Validée';
      case DriverRouteStatus.inProgress:
        return 'En cours';
      case DriverRouteStatus.closed:
        return 'Clôturée';
      case DriverRouteStatus.cancelled:
        return 'Annulée';
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
        return 'En attente';
      case DriverRouteStopStatus.arrived:
        return 'Arrivé';
      case DriverRouteStopStatus.completed:
        return 'Mission terminée';
      case DriverRouteStopStatus.failed:
        return 'Échec';
      case DriverRouteStopStatus.partial:
        return 'Partiel';
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
    this.deliveryStatus,
    this.clientName,
    this.clientPhone,
    this.totalAmount,
    this.orderRef,
    this.etaAt,
    this.slaDeadline,
    this.stopType = 'DELIVERY',
    this.sourceDepotId,
    this.sourceDepotName,
    this.sourceDepotLat,
    this.sourceDepotLng,
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
      deliveryStatus: json['deliveryStatus'] as String?,
      clientName: json['clientName'] as String?,
      clientPhone: json['clientPhone'] as String?,
      totalAmount: (json['totalAmount'] as num?)?.toDouble(),
      orderRef: json['orderRef'] as String?,
      etaAt: json['etaAt'] as String?,
      slaDeadline: json['slaDeadline'] as String?,
      stopType: (json['stopType'] as String?) ?? 'DELIVERY',
      sourceDepotId: json['sourceDepotId'] as String?,
      sourceDepotName: json['sourceDepotName'] as String?,
      sourceDepotLat: (json['sourceDepotLat'] as num?)?.toDouble(),
      sourceDepotLng: (json['sourceDepotLng'] as num?)?.toDouble(),
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
  final String? deliveryStatus;
  final String? clientName;
  final String? clientPhone;
  final double? totalAmount;
  final String? orderRef;
  final String? etaAt;
  final String? slaDeadline;

  // Multi-depot (slice 6)
  final String stopType;
  final String? sourceDepotId;
  final String? sourceDepotName;
  final double? sourceDepotLat;
  final double? sourceDepotLng;

  bool get isPickup => stopType == 'PICKUP';

  bool get hasPinned => lat != null && lng != null;

  DeliveryStatus get parsedDeliveryStatus =>
      DeliveryStatusX.fromApi(deliveryStatus);

  String? get formattedEta => _formatTime(etaAt);
  String? get formattedSla => _formatTime(slaDeadline);

  static String? _formatTime(String? iso) {
    if (iso == null) return null;
    final dt = DateTime.tryParse(iso)?.toLocal();
    if (dt == null) return null;
    return '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
  }
}

class DriverRoute {
  const DriverRoute({
    required this.id,
    required this.name,
    required this.status,
    required this.stops,
    this.zone,
    this.startedAt,
    this.date,
    this.city,
    this.totalStops,
    this.completedStops,
    this.progressPercent,
    this.plannedStart,
    this.plannedEnd,
    this.routeGeometry,
    this.depotName,
    this.depotAddress,
    this.fromCache = false,
  });

  factory DriverRoute.fromJson(Map<String, dynamic> json) {
    final stops = (json['stops'] as List<dynamic>? ?? [])
        .map((e) => DriverRouteStop.fromJson(e as Map<String, dynamic>))
        .toList()
      ..sort((a, b) => a.stopOrder.compareTo(b.stopOrder));
    return DriverRoute(
      id: (json['id'] ?? '').toString(),
      name: (json['name'] as String?)?.trim().isNotEmpty == true
          ? json['name'] as String
          : 'Tournée',
      status: DriverRouteStatusX.fromApi(json['status'] as String?),
      zone: json['detectedZoneLabel'] as String? ?? json['zone'] as String?,
      startedAt: json['startedAt'] != null
          ? DateTime.tryParse(json['startedAt'] as String)
          : null,
      date: json['date'] != null ? DateTime.tryParse(json['date'] as String) : null,
      city: json['city'] as String?,
      totalStops: (json['totalStops'] as num?)?.toInt() ?? stops.length,
      completedStops: (json['completedStops'] as num?)?.toInt() ?? 0,
      progressPercent: (json['progressPercent'] as num?)?.toDouble() ?? 0.0,
      plannedStart: json['plannedStartTime'] as String?,
      plannedEnd: json['plannedEndTime'] as String?,
      routeGeometry: json['routeGeometry'] as String?,
      depotName: json['depotName'] as String?,
      depotAddress: json['depotAddress'] as String?,
      stops: stops,
    );
  }

  final String id;
  final String name;
  final DriverRouteStatus status;
  final String? zone;
  final DateTime? startedAt;
  final List<DriverRouteStop> stops;

  // Scheduling & calendar fields
  final DateTime? date;
  final String? city;
  final int? totalStops;
  final int? completedStops;
  final double? progressPercent;
  final String? plannedStart;
  final String? plannedEnd;
  final String? routeGeometry;
  final String? depotName;
  final String? depotAddress;

  /// True when this route was loaded from the local SharedPreferences cache
  /// because the network was unavailable.
  final bool fromCache;

  bool get isToday {
    if (date == null) return false;
    final now = DateTime.now();
    return date!.year == now.year && date!.month == now.month && date!.day == now.day;
  }

  // ── Multi-depot helpers (slice 6) ──────────────────────────────────────────

  /// Delivery stops (non-pickup) loaded from the given source depot.
  List<DriverRouteStop> deliveriesForDepot(String? depotId) => stops
      .where((s) => !s.isPickup && s.sourceDepotId != null && s.sourceDepotId == depotId)
      .toList();

  /// Number of parcels (delivery stops) a pickup stop loads.
  int pickupParcelCount(DriverRouteStop pickup) => deliveriesForDepot(pickup.sourceDepotId).length;

  /// True when the depot pickup for a delivery stop has been confirmed (or none is required —
  /// i.e. a home-depot delivery with no matching PICKUP stop on the route).
  bool isDepotPicked(DriverRouteStop deliveryStop) {
    final pickup = stops.where((s) => s.isPickup && s.sourceDepotId == deliveryStop.sourceDepotId).toList();
    if (pickup.isEmpty) return true; // home depot — loaded at start
    return pickup.every((p) => p.status == DriverRouteStopStatus.completed);
  }

  static List<LatLng> decodePolyline(String encoded) {
    List<LatLng> points = [];
    int index = 0, len = encoded.length;
    int lat = 0, lng = 0;

    while (index < len) {
      int b, shift = 0, result = 0;
      do {
        if (index >= len) return points;
        b = encoded.codeUnitAt(index++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20);
      int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
      lat += dlat;

      shift = 0;
      result = 0;
      do {
        if (index >= len) return points;
        b = encoded.codeUnitAt(index++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20);
      int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
      lng += dlng;

      points.add(LatLng(lat / 1E5, lng / 1E5));
    }
    return points;
  }
}
