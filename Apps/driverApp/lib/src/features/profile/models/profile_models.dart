class DriverProfile {
  const DriverProfile({
    required this.id,
    required this.name,
    required this.phone,
    required this.available,
    this.city,
    this.currentLat,
    this.currentLng,
    this.lastLocationAt,
  });

  factory DriverProfile.fromJson(Map<String, dynamic> json) {
    return DriverProfile(
      id: json['id']?.toString() ?? '',
      name: json['name'] as String? ?? '',
      phone: json['phone'] as String? ?? '',
      available: json['available'] as bool? ?? false,
      city: json['city'] as String?,
      currentLat: (json['currentLat'] as num?)?.toDouble(),
      currentLng: (json['currentLng'] as num?)?.toDouble(),
      lastLocationAt: json['lastLocationAt'] != null ? DateTime.tryParse(json['lastLocationAt'] as String) : null,
    );
  }

  final String id;
  final String name;
  final String phone;
  final bool available;
  final String? city;
  final double? currentLat;
  final double? currentLng;
  final DateTime? lastLocationAt;

  DriverProfile copyWith({bool? available}) {
    return DriverProfile(
      id: id,
      name: name,
      phone: phone,
      available: available ?? this.available,
      city: city,
      currentLat: currentLat,
      currentLng: currentLng,
      lastLocationAt: lastLocationAt,
    );
  }
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
