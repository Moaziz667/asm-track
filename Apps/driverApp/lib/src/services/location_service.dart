import 'package:geolocator/geolocator.dart';

class LocationPoint {
  const LocationPoint({required this.lat, required this.lng});

  final double lat;
  final double lng;
}

class LocationService {
  Future<LocationPoint?> currentPosition() async {
    final permission = await _ensurePermission();
    if (!permission) return null;
    final position = await Geolocator.getCurrentPosition(desiredAccuracy: LocationAccuracy.best);
    return LocationPoint(lat: position.latitude, lng: position.longitude);
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
