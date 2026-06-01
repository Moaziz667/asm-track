import 'package:flutter/material.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../deliveries/models/delivery_models.dart';

class DeliveryCard extends StatelessWidget {
  const DeliveryCard({
    super.key,
    required this.delivery,
    this.onTap,
    this.onPrimary,
    this.primaryLabel = 'Accepter',
  });

  final DriverDelivery delivery;
  final VoidCallback? onTap;
  final VoidCallback? onPrimary;
  final String primaryLabel;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final statusColor = delivery.status.badgeColor;

    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: cs.surfaceContainerLow,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: cs.outlineVariant),
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
                              delivery.status.label,
                              style: Theme.of(context).textTheme.labelSmall?.copyWith(fontWeight: FontWeight.w800, color: statusColor),
                            ),
                          ],
                        ),
                      ),
                      const Spacer(),
                      if (delivery.priority != null)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                          decoration: BoxDecoration(
                            color: cs.secondary.withValues(alpha: 0.1),
                            borderRadius: BorderRadius.circular(6),
                            border: Border.all(color: cs.secondary.withValues(alpha: 0.2)),
                          ),
                          child: Text(
                            _titleCase(delivery.priority!),
                            style: Theme.of(context).textTheme.labelSmall?.copyWith(fontWeight: FontWeight.w800, color: cs.secondary),
                          ),
                        ),
                    ],
                  ),

                  const SizedBox(height: 12),

                  // Address
                  Text(
                    delivery.address ?? 'Aucune adresse fournie',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                      color: cs.onSurface,
                    ),
                  ),
                  if (delivery.city != null) ...[
                    const SizedBox(height: 3),
                    Row(
                      children: [
                        Icon(PhosphorIconsRegular.mapPin, size: 12, color: cs.onSurfaceVariant),
                        const SizedBox(width: 3),
                        Text(delivery.city!, style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
                      ],
                    ),
                  ],

                  const SizedBox(height: 14),
                  Divider(color: cs.outlineVariant, height: 1),
                  const SizedBox(height: 12),

                  // Footer metadata
                  Row(
                    children: [
                      _Meta(label: 'Commande', value: delivery.orderId ?? 'N/A'),
                      const SizedBox(width: 16),
                      _Meta(label: 'Articles', value: '${delivery.items.length}'),
                      const Spacer(),
                      Icon(PhosphorIconsBold.caretRight, size: 12, color: cs.onSurfaceVariant),
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

  static String _titleCase(String s) {
    if (s.isEmpty) return s;
    return s[0].toUpperCase() + s.substring(1).toLowerCase();
  }
}

class _Meta extends StatelessWidget {
  const _Meta({required this.label, required this.value});
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.labelSmall?.copyWith(fontSize: 9, fontWeight: FontWeight.w800, color: cs.onSurfaceVariant)),
        const SizedBox(height: 2),
        Text(value, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: cs.onSurface)),
      ],
    );
  }
}
