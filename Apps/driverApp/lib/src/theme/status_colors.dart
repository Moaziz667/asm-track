import 'package:flutter/material.dart';

class StatusColors extends ThemeExtension<StatusColors> {
  const StatusColors({
    required this.unscheduledText,
    required this.unscheduledPrimary,
    required this.scheduledText,
    required this.scheduledPrimary,
    required this.pickedUpText,
    required this.pickedUpPrimary,
    required this.inTransitText,
    required this.inTransitPrimary,
    required this.deliveredText,
    required this.deliveredPrimary,
    required this.partiallyDeliveredText,
    required this.partiallyDeliveredPrimary,
    required this.failedText,
    required this.failedPrimary,
    required this.cancelledText,
    required this.cancelledPrimary,
    required this.online,
    required this.onBreak,
    required this.offline,
  });

  final Color unscheduledText;
  final Color unscheduledPrimary;
  final Color scheduledText;
  final Color scheduledPrimary;
  final Color pickedUpText;
  final Color pickedUpPrimary;
  final Color inTransitText;
  final Color inTransitPrimary;
  final Color deliveredText;
  final Color deliveredPrimary;
  final Color partiallyDeliveredText;
  final Color partiallyDeliveredPrimary;
  final Color failedText;
  final Color failedPrimary;
  final Color cancelledText;
  final Color cancelledPrimary;
  
  // Driver online statuses
  final Color online;
  final Color onBreak;
  final Color offline;

  @override
  StatusColors copyWith({
    Color? unscheduledText,
    Color? unscheduledPrimary,
    Color? scheduledText,
    Color? scheduledPrimary,
    Color? pickedUpText,
    Color? pickedUpPrimary,
    Color? inTransitText,
    Color? inTransitPrimary,
    Color? deliveredText,
    Color? deliveredPrimary,
    Color? partiallyDeliveredText,
    Color? partiallyDeliveredPrimary,
    Color? failedText,
    Color? failedPrimary,
    Color? cancelledText,
    Color? cancelledPrimary,
    Color? online,
    Color? onBreak,
    Color? offline,
  }) {
    return StatusColors(
      unscheduledText: unscheduledText ?? this.unscheduledText,
      unscheduledPrimary: unscheduledPrimary ?? this.unscheduledPrimary,
      scheduledText: scheduledText ?? this.scheduledText,
      scheduledPrimary: scheduledPrimary ?? this.scheduledPrimary,
      pickedUpText: pickedUpText ?? this.pickedUpText,
      pickedUpPrimary: pickedUpPrimary ?? this.pickedUpPrimary,
      inTransitText: inTransitText ?? this.inTransitText,
      inTransitPrimary: inTransitPrimary ?? this.inTransitPrimary,
      deliveredText: deliveredText ?? this.deliveredText,
      deliveredPrimary: deliveredPrimary ?? this.deliveredPrimary,
      partiallyDeliveredText: partiallyDeliveredText ?? this.partiallyDeliveredText,
      partiallyDeliveredPrimary: partiallyDeliveredPrimary ?? this.partiallyDeliveredPrimary,
      failedText: failedText ?? this.failedText,
      failedPrimary: failedPrimary ?? this.failedPrimary,
      cancelledText: cancelledText ?? this.cancelledText,
      cancelledPrimary: cancelledPrimary ?? this.cancelledPrimary,
      online: online ?? this.online,
      onBreak: onBreak ?? this.onBreak,
      offline: offline ?? this.offline,
    );
  }

  @override
  StatusColors lerp(ThemeExtension<StatusColors>? other, double t) {
    if (other is! StatusColors) return this;
    return StatusColors(
      unscheduledText: Color.lerp(unscheduledText, other.unscheduledText, t)!,
      unscheduledPrimary: Color.lerp(unscheduledPrimary, other.unscheduledPrimary, t)!,
      scheduledText: Color.lerp(scheduledText, other.scheduledText, t)!,
      scheduledPrimary: Color.lerp(scheduledPrimary, other.scheduledPrimary, t)!,
      pickedUpText: Color.lerp(pickedUpText, other.pickedUpText, t)!,
      pickedUpPrimary: Color.lerp(pickedUpPrimary, other.pickedUpPrimary, t)!,
      inTransitText: Color.lerp(inTransitText, other.inTransitText, t)!,
      inTransitPrimary: Color.lerp(inTransitPrimary, other.inTransitPrimary, t)!,
      deliveredText: Color.lerp(deliveredText, other.deliveredText, t)!,
      deliveredPrimary: Color.lerp(deliveredPrimary, other.deliveredPrimary, t)!,
      partiallyDeliveredText: Color.lerp(partiallyDeliveredText, other.partiallyDeliveredText, t)!,
      partiallyDeliveredPrimary: Color.lerp(partiallyDeliveredPrimary, other.partiallyDeliveredPrimary, t)!,
      failedText: Color.lerp(failedText, other.failedText, t)!,
      failedPrimary: Color.lerp(failedPrimary, other.failedPrimary, t)!,
      cancelledText: Color.lerp(cancelledText, other.cancelledText, t)!,
      cancelledPrimary: Color.lerp(cancelledPrimary, other.cancelledPrimary, t)!,
      online: Color.lerp(online, other.online, t)!,
      onBreak: Color.lerp(onBreak, other.onBreak, t)!,
      offline: Color.lerp(offline, other.offline, t)!,
    );
  }
}
