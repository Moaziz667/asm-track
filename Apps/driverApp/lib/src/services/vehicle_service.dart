import 'api_client.dart';

class VehicleService {
  final ApiClient _api;

  VehicleService(this._api);

  Future<Map<String, dynamic>> getMyVehicle() async {
    final response = await _api.dio.get('/api/driver/vehicles/my-vehicle');
    return response.data;
  }

  Future<void> submitInspection({
    required String vehicleId,
    required double odometer,
    required double fuelLevel,
    required bool tiresOk,
    required bool brakesOk,
    required bool lightsOk,
    String? comments,
  }) async {
    await _api.dio.post('/api/driver/vehicles/inspection', data: {
      'vehicleId': vehicleId,
      'odometer': odometer,
      'fuelLevel': fuelLevel,
      'tiresOk': tiresOk,
      'brakesOk': brakesOk,
      'lightsOk': lightsOk,
      'comments': comments,
    });
  }
}
