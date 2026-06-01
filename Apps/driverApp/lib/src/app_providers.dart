import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'config/app_config.dart';
import 'features/auth/data/auth_controller.dart';
import 'features/auth/data/auth_repository.dart';
import 'features/auth/models/auth_models.dart';
import 'features/deliveries/data/delivery_repository.dart';
import 'features/deliveries/models/delivery_models.dart';
import 'features/profile/data/profile_repository.dart';
import 'features/profile/models/profile_models.dart';
import 'features/routes/data/route_repository.dart';
import 'features/routes/models/route_models.dart';
import 'services/api_client.dart';
import 'services/connectivity_service.dart';
import 'services/fcm_service.dart';
import 'services/pdf_service.dart';
import 'services/route_cache_service.dart';
import 'services/offline_queue_service.dart';
import 'services/token_storage.dart';
import 'services/vehicle_service.dart';

final appConfigProvider = StateProvider<AppConfig>((ref) => AppConfig.fromEnvironment());

final tokenStorageProvider = Provider<TokenStorage>((ref) => TokenStorage());

final connectivityServiceProvider = Provider<ConnectivityService>(
  (ref) => ConnectivityService(),
);

final apiClientProvider = Provider<ApiClient>((Ref ref) {
  final config = ref.watch(appConfigProvider);
  final storage = ref.watch(tokenStorageProvider);
  return ApiClient(
    config: config,
    tokenStorage: storage,
  );
});

final authRepositoryProvider = Provider<AuthRepository>((Ref ref) {
  final client = ref.watch(apiClientProvider);
  return AuthRepository(client);
});

final authControllerProvider =
    StateNotifierProvider<AuthController, AuthState>((Ref ref) {
  final repo = ref.watch(authRepositoryProvider);
  final storage = ref.watch(tokenStorageProvider);
  final fcm = ref.watch(fcmServiceProvider);

  final controller = AuthController(repo, storage, fcm, ref);

  // Wire up session expiration callback to break circularity in provider definitions.
  // The reason drives the message the driver sees on the login screen.
  ref.read(apiClientProvider).onSessionExpired = controller.endSession;

  return controller;
});

final deliveryRepositoryProvider = Provider<DeliveryRepository>((Ref ref) {
  final client = ref.watch(apiClientProvider);
  final queue = ref.watch(offlineQueueProvider.notifier);
  final connectivity = ref.watch(connectivityServiceProvider);
  return DeliveryRepository(client, queue, connectivity);
});

final profileRepositoryProvider = Provider<ProfileRepository>((ref) {
  final client = ref.watch(apiClientProvider);
  return ProfileRepository(client);
});

final routeCacheServiceProvider = Provider<RouteCacheService>((ref) => RouteCacheService());

final routeRepositoryProvider = Provider<RouteRepository>((ref) {
  final client = ref.watch(apiClientProvider);
  final cache = ref.watch(routeCacheServiceProvider);
  final queue = ref.watch(offlineQueueProvider.notifier);
  final connectivity = ref.watch(connectivityServiceProvider);
  return RouteRepository(client, cache, queue, connectivity);
});

final vehicleServiceProvider = Provider<VehicleService>((ref) {
  final client = ref.watch(apiClientProvider);
  return VehicleService(client);
});

final pdfServiceProvider = Provider<PdfService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PdfService(client);
});

final fcmServiceProvider = Provider<FcmService>((ref) {
  final client = ref.watch(apiClientProvider);
  return FcmService(client);
});

final myVehicleProvider = FutureProvider<Map<String, dynamic>>((ref) {
  return ref.watch(vehicleServiceProvider).getMyVehicle();
});

final activeDeliveriesProvider = FutureProvider<List<DriverDelivery>>((ref) {
  final repo = ref.watch(deliveryRepositoryProvider);
  return repo.fetchActive();
});

final driverHistoryProvider = FutureProvider<List<DriverDelivery>>((ref) {
  final repo = ref.watch(deliveryRepositoryProvider);
  return repo.fetchHistory();
});

final driverProfileProvider = FutureProvider<DriverProfile>((ref) {
  final repo = ref.watch(profileRepositoryProvider);
  return repo.fetchProfile();
});

final driverStatsProvider = FutureProvider<DriverStats>((ref) {
  final repo = ref.watch(profileRepositoryProvider);
  return repo.fetchStats();
});

final todayRouteProvider = FutureProvider<DriverRoute?>((ref) {
  final repo = ref.watch(routeRepositoryProvider);
  return repo.fetchToday();
});

final deliveryDetailProvider = FutureProvider.family<DriverDelivery, String>((ref, id) {
  final repo = ref.watch(deliveryRepositoryProvider);
  return repo.fetchById(id);
});

// ─── Calendar providers ───────────────────────────────────────────────────────

/// Tracks the Monday of the currently displayed calendar week.
final calendarWeekProvider = StateProvider<DateTime>((ref) {
  final now = DateTime.now();
  return now.subtract(Duration(days: now.weekday - 1));
});

/// Fetches all routes for a 7-day window starting from [weekStart].
final weekRoutesProvider = FutureProvider.family<List<DriverRoute>, DateTime>((ref, weekStart) {
  final repo = ref.watch(routeRepositoryProvider);
  final weekEnd = weekStart.add(const Duration(days: 6));
  return repo.fetchRange(weekStart, weekEnd);
});
