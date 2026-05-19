import 'package:geolocator/geolocator.dart';

class LocationPoint {
  const LocationPoint({
    required this.lat,
    required this.lng,
    required this.accuracy,
  });

  final double lat;
  final double lng;
  final double accuracy;
}

class LocationService {
  Future<LocationPoint?> currentPosition() async {
    final permission = await _ensurePermission();
    if (!permission) return null;

    try {
      final position = await Geolocator.getCurrentPosition(
        desiredAccuracy: LocationAccuracy.high,
        timeLimit: const Duration(seconds: 10),
      );
      return LocationPoint(
        lat: position.latitude,
        lng: position.longitude,
        accuracy: position.accuracy,
      );
    } catch (_) {
      // GPS fix timed out or unavailable — try last known position.
      // getLastKnownPosition is unsupported on web, so catch that too.
      try {
        final last = await Geolocator.getLastKnownPosition();
        if (last == null) return null;
        return LocationPoint(
          lat: last.latitude,
          lng: last.longitude,
          accuracy: last.accuracy,
        );
      } catch (_) {
        return null;
      }
    }
  }

  double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
    return Geolocator.distanceBetween(lat1, lng1, lat2, lng2);
  }

  Future<bool> _ensurePermission() async {
    bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) {
      serviceEnabled = await Geolocator.openLocationSettings();
      if (!serviceEnabled) return false;
    }
    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }
    if (permission == LocationPermission.deniedForever) {
      return false;
    }
    return permission == LocationPermission.whileInUse || permission == LocationPermission.always;
  }
}
