import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../deliveries/models/delivery_models.dart';
import '../../../../theme/app_theme.dart';

class DeliveryCard extends StatelessWidget {
  const DeliveryCard({
    super.key,
    required this.delivery,
    this.onTap,
    this.onPrimary,
    this.primaryLabel = 'Accept',
  });

  final DriverDelivery delivery;
  final VoidCallback? onTap;
  final VoidCallback? onPrimary;
  final String primaryLabel;

  @override
  Widget build(BuildContext context) {
    final statusColor = delivery.status.badgeColor;

    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: AppColors.border),
          boxShadow: [
            BoxShadow(
              color: const Color(0xFF101014).withValues(alpha: 0.08),
              blurRadius: 18,
              offset: const Offset(0, 6),
            ),
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // ── Color strip ────────────────────────────────────────────────
            Container(
              height: 3,
              decoration: BoxDecoration(
                color: statusColor,
                borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
              ),
            ),

            Padding(
              padding: const EdgeInsets.all(18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Status + priority row
                  Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                        decoration: BoxDecoration(
                          color: statusColor.withValues(alpha: 0.1),
                          borderRadius: BorderRadius.circular(6),
                          border: Border.all(color: statusColor.withValues(alpha: 0.2)),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Container(width: 5, height: 5, decoration: BoxDecoration(color: statusColor, shape: BoxShape.circle)),
                            const SizedBox(width: 5),
                            Text(
                              delivery.status.label.toUpperCase(),
                              style: GoogleFonts.manrope(fontSize: 10, fontWeight: FontWeight.w800, color: statusColor, letterSpacing: 0.35),
                            ),
                          ],
                        ),
                      ),
                      const Spacer(),
                      if (delivery.priority != null)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                          decoration: BoxDecoration(
                            color: AppColors.warningSubtle,
                            borderRadius: BorderRadius.circular(6),
                            border: Border.all(color: AppColors.warningBorder),
                          ),
                          child: Text(
                            delivery.priority!.toUpperCase(),
                            style: GoogleFonts.manrope(fontSize: 10, fontWeight: FontWeight.w800, color: AppColors.warning),
                          ),
                        ),
                    ],
                  ),

                  const SizedBox(height: 12),

                  // Address
                  Text(
                    delivery.address ?? 'No address provided',
                    style: GoogleFonts.sora(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.textPrimary, letterSpacing: -0.2),
                  ),
                  if (delivery.city != null) ...[
                    const SizedBox(height: 3),
                    Row(
                      children: [
                        const Icon(PhosphorIconsRegular.mapPin, size: 12, color: AppColors.muted),
                        const SizedBox(width: 3),
                        Text(delivery.city!, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                      ],
                    ),
                  ],

                  const SizedBox(height: 14),
                  const Divider(color: AppColors.border, height: 1),
                  const SizedBox(height: 12),

                  // Footer metadata
                  Row(
                    children: [
                      _Meta(label: 'ORDER', value: delivery.orderId ?? 'N/A'),
                      const SizedBox(width: 16),
                      _Meta(label: 'ITEMS', value: '${delivery.items.length}'),
                      const Spacer(),
                      const Icon(PhosphorIconsBold.caretRight, size: 12, color: AppColors.muted),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Meta extends StatelessWidget {
  const _Meta({required this.label, required this.value, this.valueColor});
  final String label;
  final String value;
  final Color? valueColor;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: GoogleFonts.manrope(fontSize: 9, fontWeight: FontWeight.w800, color: AppColors.muted, letterSpacing: 0.8)),
          const SizedBox(height: 2),
          Text(value, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: valueColor ?? AppColors.textPrimary)),
        ],
      );
}
