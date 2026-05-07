import 'package:connectivity_plus/connectivity_plus.dart';

/// Stream-based connectivity detection service.
///
/// Exposes a reactive [onlineStream] and a one-shot [isOnline] check
/// so that any screen or service can act on connectivity changes.
class ConnectivityService {
  final _conn = Connectivity();

  /// Emits `true` when any connectivity result other than [ConnectivityResult.none]
  /// is detected, `false` otherwise.
  Stream<bool> get onlineStream => _conn.onConnectivityChanged
      .map((list) => list.any((r) => r != ConnectivityResult.none));

  /// One-shot check of current connectivity.
  Future<bool> get isOnline async {
    final result = await _conn.checkConnectivity();
    return result.any((r) => r != ConnectivityResult.none);
  }
}
