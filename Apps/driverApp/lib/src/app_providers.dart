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
import 'services/token_storage.dart';

final appConfigProvider = Provider<AppConfig>((ref) => AppConfig.fromEnvironment());

final tokenStorageProvider = Provider<TokenStorage>((ref) => TokenStorage());

final apiClientProvider = Provider<ApiClient>((ref) {
  final config = ref.watch(appConfigProvider);
  final storage = ref.watch(tokenStorageProvider);
  return ApiClient(config: config, tokenStorage: storage);
});

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  final client = ref.watch(apiClientProvider);
  return AuthRepository(client);
});

final authControllerProvider =
    StateNotifierProvider<AuthController, AuthState>((ref) => AuthController(ref.watch(authRepositoryProvider), ref.watch(tokenStorageProvider)));

final deliveryRepositoryProvider = Provider<DeliveryRepository>((ref) {
  final client = ref.watch(apiClientProvider);
  return DeliveryRepository(client);
});

final profileRepositoryProvider = Provider<ProfileRepository>((ref) {
  final client = ref.watch(apiClientProvider);
  return ProfileRepository(client);
});

final routeRepositoryProvider = Provider<RouteRepository>((ref) {
  final client = ref.watch(apiClientProvider);
  return RouteRepository(client);
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
