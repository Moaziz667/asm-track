import '../../../models/auth_tokens.dart';

enum AuthStatus { unknown, authenticated, unauthenticated }

class DriverIdentity {
  const DriverIdentity({
    required this.id,
    required this.name,
    required this.phone,
  });

  factory DriverIdentity.fromJson(Map<String, dynamic> json) {
    return DriverIdentity(
      id: json['id'] as String? ?? '',
      name: json['name'] as String? ?? '',
      phone: json['phone'] as String? ?? '',
    );
  }

  final String id;
  final String name;
  final String phone;
}

class AuthState {
  const AuthState({
    required this.status,
    this.driver,
    this.isLoading = false,
    this.error,
  });

  factory AuthState.unknown() => const AuthState(status: AuthStatus.unknown);

  final AuthStatus status;
  final DriverIdentity? driver;
  final bool isLoading;
  final String? error;

  AuthState copyWith({
    AuthStatus? status,
    DriverIdentity? driver,
    bool? isLoading,
    String? error,
  }) {
    return AuthState(
      status: status ?? this.status,
      driver: driver ?? this.driver,
      isLoading: isLoading ?? this.isLoading,
      error: error,
    );
  }
}

class AuthPayload {
  const AuthPayload({required this.tokens, required this.driver});

  final AuthTokens tokens;
  final DriverIdentity driver;
}
