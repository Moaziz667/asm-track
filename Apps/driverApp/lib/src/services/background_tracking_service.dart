import 'dart:async';
import 'package:geolocator/geolocator.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../app_providers.dart';
import 'api_client.dart';

final backgroundTrackingProvider = Provider<BackgroundTrackingService>((ref) {
  final apiClient = ref.read(apiClientProvider);
  return BackgroundTrackingService(apiClient);
});

class BackgroundTrackingService {
  final ApiClient _apiClient;
  StreamSubscription<Position>? _positionSub;
  
  BackgroundTrackingService(this._apiClient);

  void startTracking() {
    if (_positionSub != null) return;

    // P1: Background GPS Persistence
    // Configure foreground notification for Android to survive process death
    final LocationSettings locationSettings = AndroidSettings(
      accuracy: LocationAccuracy.high,
      distanceFilter: 50, // Only update every 50m to save battery
      forceLocationManager: false,
      intervalDuration: const Duration(seconds: 30),
      // Foreground notification keeps service alive
      foregroundNotificationConfig: const ForegroundNotificationConfig(
        notificationText: "Suivi de livraison actif en arrière-plan",
        notificationTitle: "asmDrive - En service",
        enableWakeLock: true,
      ),
    );

    _positionSub = Geolocator.getPositionStream(locationSettings: locationSettings).listen(
      (Position position) {
        _syncLocation(position);
      },
      onError: (e) {
        // Handle background errors (e.g. GPS lost)
      },
    );
  }

  void stopTracking() {
    _positionSub?.cancel();
    _positionSub = null;
  }

  Future<void> _syncLocation(Position pos) async {
    try {
      await _apiClient.dio.post('/api/driver/deliveries/location', data: {
        'lat': pos.latitude,
        'lng': pos.longitude,
        'accuracy': pos.accuracy,
        'timestamp': DateTime.now().toIso8601String(),
      });
    } catch (_) {
      // P1: Queue unsent location updates if offline (handled by ApiClient + OfflineQueue)
    }
  }
}
