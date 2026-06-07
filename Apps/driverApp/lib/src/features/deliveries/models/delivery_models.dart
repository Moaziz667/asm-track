enum DeliveryStatus {
  unscheduled,
  scheduled,
  pickedUp,
  inTransit,
  delivered,
  partially_delivered,
  failed,
  cancelled,
}

extension DeliveryStatusX on DeliveryStatus {
  static DeliveryStatus fromApi(String? value) {
    switch (value) {
      case 'SCHEDULED':
        return DeliveryStatus.scheduled;
      case 'UNSCHEDULED':
        return DeliveryStatus.unscheduled;
      case 'PICKED_UP':
        return DeliveryStatus.pickedUp;
      case 'IN_TRANSIT':
        return DeliveryStatus.inTransit;
      case 'DELIVERED':
        return DeliveryStatus.delivered;
      case 'PARTIALLY_DELIVERED':
        return DeliveryStatus.partially_delivered;
      case 'FAILED':
        return DeliveryStatus.failed;
      case 'CANCELLED':
        return DeliveryStatus.cancelled;
      default:
        return DeliveryStatus.unscheduled;
    }
  }

  String get label {
    switch (this) {
      case DeliveryStatus.unscheduled:
        return 'Non planifié';
      case DeliveryStatus.scheduled:
        return 'Planifié';
      case DeliveryStatus.pickedUp:
        return 'Chargé';
      case DeliveryStatus.inTransit:
        return 'En transit';
      case DeliveryStatus.delivered:
        return 'Livré';
      case DeliveryStatus.partially_delivered:
        return 'Livré partiel';
      case DeliveryStatus.failed:
        return 'Échec';
      case DeliveryStatus.cancelled:
        return 'Annulé';
    }
  }
}

enum FailureReason { clientAbsent, refused, wrongAddress, damaged, other }

extension FailureReasonX on FailureReason {
  FailureCode get apiCode {
    switch (this) {
      case FailureReason.clientAbsent:
        return FailureCode('CLIENT_ABSENT');
      case FailureReason.refused:
        return FailureCode('REFUSED');
      case FailureReason.wrongAddress:
        return FailureCode('WRONG_ADDRESS');
      case FailureReason.damaged:
        return FailureCode('DAMAGED');
      case FailureReason.other:
        return FailureCode('OTHER');
    }
  }

  String get label {
    switch (this) {
      case FailureReason.clientAbsent:
        return 'Client absent';
      case FailureReason.refused:
        return 'Refusé par le client';
      case FailureReason.wrongAddress:
        return 'Mauvaise adresse';
      case FailureReason.damaged:
        return 'Colis endommagé';
      case FailureReason.other:
        return 'Autre';
    }
  }
}

class FailureCode {
  const FailureCode(this.value);
  final String value;
}

/// Configurable failure reason fetched from the backend referential.
/// Falls back to the static [FailureReason] enum when offline.
class FailureReasonOption {
  const FailureReasonOption({required this.code, required this.label, this.category});
  final String code;
  final String label;
  final String? category;

  factory FailureReasonOption.fromJson(Map<String, dynamic> json) => FailureReasonOption(
        code: json['code'] as String? ?? 'OTHER',
        label: json['label'] as String? ?? (json['code'] as String? ?? 'Autre'),
        category: json['category'] as String?,
      );

  /// Static fallback derived from the legacy enum (used when the API is unreachable).
  static List<FailureReasonOption> get fallback => FailureReason.values
      .map((r) => FailureReasonOption(code: r.apiCode.value, label: r.label, category: r.apiCode.value))
      .toList();
}

class OrderItemModel {
  const OrderItemModel({
    required this.name,
    required this.quantity,
    this.sku,
  });

  factory OrderItemModel.fromJson(Map<String, dynamic> json) {
    return OrderItemModel(
      name: json['name'] as String? ?? 'Item',
      quantity: (json['quantity'] as num?)?.toInt() ?? 0,
      sku: json['sku'] as String?,
    );
  }

  final String name;
  final int quantity;
  final String? sku;
}

class DriverDelivery {
  const DriverDelivery({
    required this.id,
    required this.status,
    this.orderId,
    this.orderRef,
    this.clientName,
    this.clientPhone,
    this.address,
    this.city,
    this.instructions,
    this.totalAmount,
    this.currency,
    this.items = const [],
    this.priority,
    this.scheduledAt,
    this.routeGeometry,
    this.routeDistanceKm,
    this.routeDurationMinutes,
    this.transitSlaMinutesComputed,
    this.routeEtaAt,
    this.routeProvider,
    this.lat,
    this.lng,
    this.requiresHandoff = false,
    this.handoffConfirmedAt,
    this.handoffToDriverId,
    this.handoffFromDriverId,
    this.timestamps = const {},
  });

  factory DriverDelivery.fromJson(Map<String, dynamic> json) {
    final timestamps = <String, DateTime?>{};
    for (final key in [
      'scheduledAt',
      'pickedUpAt',
      'inTransitAt',
      'completedAt',
      'failedAt',
      'cancelledAt',
      'createdAt',
    ]) {
      timestamps[key] = json[key] != null ? DateTime.tryParse(json[key] as String) : null;
    }
    return DriverDelivery(
      id: (json['deliveryId'] ?? json['id']).toString(),
      orderId: (json['orderId'])?.toString(),
      orderRef: json['orderRef'] as String?,
      clientName: json['clientName'] as String?,
      clientPhone: json['clientPhone'] as String?,
      status: DeliveryStatusX.fromApi(json['status'] as String?),
      address: json['dropoffAddress'] as String?,
      city: json['dropoffCity'] as String?,
      instructions: json['deliveryInstructions'] as String?,
      totalAmount: (json['totalAmount'] as num?)?.toDouble(),
      currency: json['currency'] as String?,
      items: (json['items'] as List<dynamic>? ?? [])
          .map((item) => OrderItemModel.fromJson(item as Map<String, dynamic>))
          .toList(),
      priority: json['priority'] as String?,
      scheduledAt: json['scheduledAt'] != null ? DateTime.tryParse(json['scheduledAt'] as String) : null,
      routeGeometry: json['routeGeometry'] as String?,
      routeDistanceKm: (json['routeDistanceKm'] as num?)?.toDouble(),
      routeDurationMinutes: (json['routeDurationMinutes'] as num?)?.toInt(),
      transitSlaMinutesComputed: (json['transitSlaMinutesComputed'] as num?)?.toInt(),
      routeEtaAt: json['routeEtaAt'] != null ? DateTime.tryParse(json['routeEtaAt'] as String) : null,
      routeProvider: json['routeProvider'] as String?,
      lat: (json['dropoffLat'] as num?)?.toDouble() ?? (json['lat'] as num?)?.toDouble(),
      lng: (json['dropoffLng'] as num?)?.toDouble() ?? (json['lng'] as num?)?.toDouble(),
      requiresHandoff: json['requiresHandoff'] as bool? ?? false,
      handoffConfirmedAt: json['handoffConfirmedAt'] != null ? DateTime.tryParse(json['handoffConfirmedAt'] as String) : null,
      handoffToDriverId: json['handoffToDriverId'] as String?,
      handoffFromDriverId: json['handoffFromDriverId'] as String?,
      timestamps: timestamps,
    );
  }

  final String id;
  final String? orderId;
  final String? orderRef;
  final String? clientName;
  final String? clientPhone;
  final DeliveryStatus status;
  final String? address;
  final String? city;
  final String? instructions;
  final double? totalAmount;
  final String? currency;
  final List<OrderItemModel> items;
  final String? priority;
  final DateTime? scheduledAt;
  final String? routeGeometry;
  final double? routeDistanceKm;
  final int? routeDurationMinutes;
  final int? transitSlaMinutesComputed;
  final DateTime? routeEtaAt;
  final String? routeProvider;
  final double? lat;
  final double? lng;
  final bool requiresHandoff;
  final DateTime? handoffConfirmedAt;
  final String? handoffToDriverId;
  final String? handoffFromDriverId;
  final Map<String, DateTime?> timestamps;

  bool get isTerminal => status == DeliveryStatus.delivered || status == DeliveryStatus.failed || status == DeliveryStatus.cancelled;
}

class PartialDeliveryItem {
  PartialDeliveryItem({
    required this.sku,
    required this.quantityDone,
    this.outcome,
    this.reason,
    this.comment,
  });

  final String sku;
  final int quantityDone;

  /// DELIVERED, REFUSED, or DAMAGED — explicit per-item outcome.
  final String? outcome;

  /// Reason code when outcome is REFUSED or DAMAGED.
  final String? reason;

  /// Optional per-item comment from the driver.
  final String? comment;

  Map<String, dynamic> toJson() {
    return {
      'sku': sku,
      'quantityDone': quantityDone,
      if (outcome != null) 'outcome': outcome,
      if (reason != null) 'reason': reason,
      if (comment != null && comment!.isNotEmpty) 'comment': comment,
    };
  }
}

class PodPayload {
  PodPayload({
    required this.bonLivraisonPhotoBase64,
    required this.packagePhotoBase64,
    this.comment,
    this.lat,
    this.lng,
    this.isPartial = false,
    this.itemsDone,
  });

  final String bonLivraisonPhotoBase64;
  final String packagePhotoBase64;
  final String? comment;
  final double? lat;
  final double? lng;
  final bool isPartial;
  final List<PartialDeliveryItem>? itemsDone;

  Map<String, dynamic> toJson() {
    return {
      'bonLivraisonPhotoBase64': bonLivraisonPhotoBase64,
      'packagePhotoBase64': packagePhotoBase64,
      'comment': comment,
      'lat': lat,
      'lng': lng,
      'isPartial': isPartial,
      'itemsDone': itemsDone?.map((e) => e.toJson()).toList(),
    }..removeWhere((key, value) => value == null || (value is String && value.isEmpty));
  }
}

/// A freshly generated one-time handoff code plus its server-authoritative expiry,
/// so the sender's sheet can show a live countdown and lock the code at expiry.
class HandoffTokenInfo {
  const HandoffTokenInfo({required this.token, this.expiresAt});

  final String token;
  final DateTime? expiresAt;
}

