import 'package:connectivity_plus/connectivity_plus.dart';

class ConnectivityService {
  final _conn = Connectivity();

  Stream<bool> get onlineStream => _conn.onConnectivityChanged
      .map((list) => list.any((r) => r != ConnectivityResult.none));

  Future<bool> get isOnline async {
    final result = await _conn.checkConnectivity();
    return result.any((r) => r != ConnectivityResult.none);
  }
}
