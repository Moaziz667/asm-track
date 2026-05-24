class DriverProfile {
  const DriverProfile({
    required this.id,
    required this.name,
    required this.phone,
    this.city,
    this.currentLat,
    this.currentLng,
    this.lastLocationAt,
    this.onlineStatus = 'OFFLINE',
  });

  factory DriverProfile.fromJson(Map<String, dynamic> json) {
    return DriverProfile(
      id: json['id']?.toString() ?? '',
      name: json['name'] as String? ?? '',
      phone: json['phone'] as String? ?? '',
      city: json['city'] as String?,
      currentLat: (json['currentLat'] as num?)?.toDouble(),
      currentLng: (json['currentLng'] as num?)?.toDouble(),
      lastLocationAt: json['lastLocationAt'] != null ? DateTime.tryParse(json['lastLocationAt'] as String) : null,
      onlineStatus: json['onlineStatus'] as String? ?? 'OFFLINE',
    );
  }

  final String id;
  final String name;
  final String phone;
  final String? city;
  final double? currentLat;
  final double? currentLng;
  final DateTime? lastLocationAt;
  final String onlineStatus;

}

class DriverStats {
  const DriverStats({
    required this.totalDeliveries,
    required this.delivered,
    required this.failed,
    required this.cancelled,
  });

  factory DriverStats.fromJson(Map<String, dynamic> json) {
    return DriverStats(
      totalDeliveries: (json['totalDeliveries'] as num?)?.toInt() ?? 0,
      delivered: (json['delivered'] as num?)?.toInt() ?? 0,
      failed: (json['failed'] as num?)?.toInt() ?? 0,
      cancelled: (json['cancelled'] as num?)?.toInt() ?? 0,
    );
  }

  final int totalDeliveries;
  final int delivered;
  final int failed;
  final int cancelled;
}
