import 'package:flutter/material.dart';

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
        return 'Unscheduled';
      case DeliveryStatus.scheduled:
        return 'Scheduled';
      case DeliveryStatus.pickedUp:
        return 'Picked up';
      case DeliveryStatus.inTransit:
        return 'In transit';
      case DeliveryStatus.delivered:
        return 'Delivered';
      case DeliveryStatus.partially_delivered:
        return 'Partially delivered';
      case DeliveryStatus.failed:
        return 'Failed';
      case DeliveryStatus.cancelled:
        return 'Cancelled';
    }
  }

  Color get badgeColor {
    switch (this) {
      case DeliveryStatus.unscheduled:
        return const Color(0xFF8EA1C0);
      case DeliveryStatus.scheduled:
        return const Color(0xFF6B8CFF);
      case DeliveryStatus.pickedUp:
        return const Color(0xFF1BD6A0);
      case DeliveryStatus.inTransit:
        return const Color(0xFF2E5BFF);
      case DeliveryStatus.delivered:
        return const Color(0xFF50E3C2);
      case DeliveryStatus.partially_delivered:
        return const Color(0xFF00B8D4);
      case DeliveryStatus.failed:
        return const Color(0xFFFF5F6D);
      case DeliveryStatus.cancelled:
        return const Color(0xFFF5A524);
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
        return 'Client refused';
      case FailureReason.wrongAddress:
        return 'Wrong address';
      case FailureReason.damaged:
        return 'Damaged package';
      case FailureReason.other:
        return 'Other';
    }
  }
}

class FailureCode {
  const FailureCode(this.value);
  final String value;
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
      timestamps: timestamps,
    );
  }

  final String id;
  final String? orderId;
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
  final Map<String, DateTime?> timestamps;

  bool get isTerminal => status == DeliveryStatus.delivered || status == DeliveryStatus.failed || status == DeliveryStatus.cancelled;
}

class PartialDeliveryItem {
  PartialDeliveryItem({
    required this.sku,
    required this.quantityDone,
  });

  final String sku;
  final int quantityDone;

  Map<String, dynamic> toJson() {
    return {
      'sku': sku,
      'quantityDone': quantityDone,
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

