import 'dart:convert';

import 'package:hive/hive.dart';

import '../features/routes/models/route_models.dart';

class RouteCacheService {
  static const _key = 'cached_today_route';

  Box get _box => Hive.box('domain_cache');

  Future<void> save(DriverRoute route) async {
    try {
      final encoded = jsonEncode(_routeToJson(route));
      await _box.put(_key, encoded);
    } catch (_) {
      // Cache write failures are non-fatal
    }
  }

  Future<DriverRoute?> load() async {
    try {
      final raw = _box.get(_key) as String?;
      if (raw == null) return null;
      final map = jsonDecode(raw) as Map<String, dynamic>;
      final route = DriverRoute.fromJson(map);
      return DriverRoute(
        id: route.id,
        name: route.name,
        status: route.status,
        stops: route.stops,
        zone: route.zone,
        startedAt: route.startedAt,
        date: route.date,
        city: route.city,
        totalStops: route.totalStops,
        completedStops: route.completedStops,
        progressPercent: route.progressPercent,
        plannedStart: route.plannedStart,
        plannedEnd: route.plannedEnd,
        depotName: route.depotName,
        depotAddress: route.depotAddress,
        fromCache: true,
      );
    } catch (_) {
      return null;
    }
  }

  Future<void> clear() async {
    try {
      await _box.delete(_key);
    } catch (_) {}
  }

  Map<String, dynamic> _routeToJson(DriverRoute route) {
    return {
      'id': route.id,
      'name': route.name,
      'status': _statusToApi(route.status),
      'detectedZoneLabel': route.zone,
      'startedAt': route.startedAt?.toIso8601String(),
      'date': route.date?.toIso8601String().substring(0, 10),
      'city': route.city,
      'totalStops': route.totalStops,
      'completedStops': route.completedStops,
      'progressPercent': route.progressPercent,
      'plannedStartTime': route.plannedStart,
      'plannedEndTime': route.plannedEnd,
      'routeGeometry': route.routeGeometry,
      'depotName': route.depotName,
      'depotAddress': route.depotAddress,
      'stops': route.stops.map(_stopToJson).toList(),
    };
  }

  Map<String, dynamic> _stopToJson(DriverRouteStop s) {
    return {
      'id': s.id,
      'deliveryId': s.deliveryId,
      'stopOrder': s.stopOrder,
      'status': s.status.name.toUpperCase(),
      'dropoffLat': s.lat,
      'dropoffLng': s.lng,
      'deliveryAddress': s.address,
      'deliveryCity': s.city,
      'deliveryStatus': s.deliveryStatus,
      'clientName': s.clientName,
      'clientPhone': s.clientPhone,
      'totalAmount': s.totalAmount,
      'orderRef': s.orderRef,
      'etaAt': s.etaAt,
      'slaDeadline': s.slaDeadline,
    };
  }

  static String _statusToApi(DriverRouteStatus s) {
    switch (s) {
      case DriverRouteStatus.draft:
        return 'DRAFT';
      case DriverRouteStatus.validated:
        return 'VALIDATED';
      case DriverRouteStatus.inProgress:
        return 'IN_PROGRESS';
      case DriverRouteStatus.closed:
        return 'CLOSED';
      case DriverRouteStatus.cancelled:
        return 'CANCELLED';
    }
  }
}
