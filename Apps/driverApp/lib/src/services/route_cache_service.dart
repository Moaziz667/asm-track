import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../features/routes/models/route_models.dart';

class RouteCacheService {
  static const _key = 'cached_today_route';

  Future<void> save(DriverRoute route) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final encoded = jsonEncode(_routeToJson(route));
      await prefs.setString(_key, encoded);
    } catch (_) {
      // Cache write failures are non-fatal
    }
  }

  Future<DriverRoute?> load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_key);
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
        fromCache: true,
      );
    } catch (_) {
      return null;
    }
  }

  Future<void> clear() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_key);
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
