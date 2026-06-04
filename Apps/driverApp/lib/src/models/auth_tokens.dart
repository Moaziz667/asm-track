class AuthTokens {
  const AuthTokens({
    required this.accessToken,
    required this.refreshToken,
    this.idToken,
    this.expiresAt,
    this.tokenType = 'Bearer',
  });

  factory AuthTokens.fromMap(Map<String, dynamic> json, {String? currentRefreshToken}) {
    final expiresIn = (json['expiresIn'] as num?)?.toInt();
    final expiry = expiresIn != null ? DateTime.now().add(Duration(seconds: expiresIn)) : null;
    return AuthTokens(
      accessToken: (json['accessToken'] ?? json['token']) as String? ?? '',
      refreshToken: (json['refreshToken'] as String?) ?? currentRefreshToken ?? '',
      idToken: json['idToken'] as String?,
      tokenType: json['tokenType'] as String? ?? 'Bearer',
      expiresAt: expiry,
    );
  }

  final String accessToken;
  final String refreshToken;
  final String? idToken;
  final String tokenType;
  final DateTime? expiresAt;

  Map<String, dynamic> toMap() {
    return {
      'accessToken': accessToken,
      'refreshToken': refreshToken,
      'idToken': idToken,
      'tokenType': tokenType,
      'expiresAt': expiresAt?.toIso8601String(),
    };
  }

  AuthTokens copyWith({
    String? accessToken,
    String? refreshToken,
    String? idToken,
    String? tokenType,
    DateTime? expiresAt,
  }) {
    return AuthTokens(
      accessToken: accessToken ?? this.accessToken,
      refreshToken: refreshToken ?? this.refreshToken,
      idToken: idToken ?? this.idToken,
      tokenType: tokenType ?? this.tokenType,
      expiresAt: expiresAt ?? this.expiresAt,
    );
  }

  bool get isExpired => expiresAt != null && DateTime.now().isAfter(expiresAt!);
}
