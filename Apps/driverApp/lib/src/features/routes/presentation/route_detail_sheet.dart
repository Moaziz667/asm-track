import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../models/route_models.dart';
import 'widgets/route_card.dart' show StatusChip;

/// Read-only bottom sheet shown when the driver taps a non-active route in
/// the calendar (e.g. a future VALIDATED route or a past CLOSED route).
class RouteDetailSheet extends StatelessWidget {
  const RouteDetailSheet({super.key, required this.route});

  final DriverRoute route;

  static Future<void> show(BuildContext context, DriverRoute route) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => RouteDetailSheet(route: route),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final total = route.totalStops ?? route.stops.length;
    final done = route.completedStops ??
        route.stops.where((s) => s.status != DriverRouteStopStatus.pending).length;

    return DraggableScrollableSheet(
      initialChildSize: 0.55,
      minChildSize: 0.35,
      maxChildSize: 0.9,
      expand: false,
      builder: (context, scroll) => Container(
        decoration: BoxDecoration(
          color: cs.surfaceContainerLow,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
          boxShadow: [
            const BoxShadow(color: Color(0x1A0F172A), blurRadius: 24, offset: Offset(0, -4)),
          ],
        ),
        child: ListView(
          controller: scroll,
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 40),
          children: [
            // Handle
            Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Container(
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(
                    color: cs.surfaceContainerHigh,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
            ),

            // Header row: name + status chip
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Text(
                    route.name,
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.w700,
                      color: cs.onSurface,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                StatusChip(route.status),
              ],
            ),

            const SizedBox(height: 16),

            // Meta grid
            _InfoGrid(route: route, total: total, done: done),

            const SizedBox(height: 20),

            // Stop list (read-only)
            Row(
              children: [
                Text(
                  'Arrêts',
                  style: GoogleFonts.inter(
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: cs.onSurface,
                  ),
                ),
                const SizedBox(width: 8),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: cs.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: cs.outlineVariant),
                  ),
                  child: Text(
                    '$total',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: cs.onSurfaceVariant,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),

            if (route.stops.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Text(
                  'Aucun arrêt configuré.',
                  style: TextStyle(color: cs.onSurfaceVariant, fontSize: 13),
                ),
              )
            else
              ...route.stops.map((stop) => Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: _ReadOnlyStopRow(stop: stop),
                  )),
          ],
        ),
      ),
    );
  }
}

// ─── Info grid ────────────────────────────────────────────────────────────────
class _InfoGrid extends StatelessWidget {
  const _InfoGrid({required this.route, required this.total, required this.done});
  final DriverRoute route;
  final int total;
  final int done;

  @override
  Widget build(BuildContext context) {
    final items = <_InfoItem>[];

    if (route.date != null) {
      const months = ['JAN', 'FÉV', 'MAR', 'AVR', 'MAI', 'JUIN', 'JUIL', 'AOÛT', 'SEP', 'OCT', 'NOV', 'DÉC'];
      final d = route.date!;
      items.add(_InfoItem(
        icon: PhosphorIconsRegular.calendarBlank,
        label: 'Date',
        value: '${d.day} ${months[d.month - 1]} ${d.year}',
      ));
    }

    if (route.plannedStart != null || route.plannedEnd != null) {
      final label = [route.plannedStart, route.plannedEnd]
          .where((e) => e != null)
          .join(' – ');
      items.add(_InfoItem(
        icon: PhosphorIconsRegular.clock,
        label: 'Horaire',
        value: label,
      ));
    }

    if (route.zone != null && route.zone!.isNotEmpty) {
      items.add(_InfoItem(
        icon: PhosphorIconsRegular.mapPin,
        label: 'Zone',
        value: route.zone!,
      ));
    } else if (route.city != null && route.city!.isNotEmpty) {
      items.add(_InfoItem(
        icon: PhosphorIconsRegular.mapPin,
        label: 'Ville',
        value: route.city!,
      ));
    }

    items.add(_InfoItem(
      icon: PhosphorIconsRegular.package,
      label: 'Arrêts',
      value: route.status == DriverRouteStatus.closed ||
              route.status == DriverRouteStatus.inProgress
          ? '$done / $total'
          : '$total',
    ));

    return Wrap(
      spacing: 10,
      runSpacing: 10,
      children: items.map((item) => _InfoTile(item: item)).toList(),
    );
  }
}

class _InfoItem {
  const _InfoItem({required this.icon, required this.label, required this.value});
  final IconData icon;
  final String label;
  final String value;
}

class _InfoTile extends StatelessWidget {
  const _InfoTile({required this.item});
  final _InfoItem item;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: cs.outlineVariant),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(item.icon, size: 14, color: cs.onSurfaceVariant),
          const SizedBox(width: 7),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                item.label,
                style: TextStyle(fontSize: 10, color: cs.onSurfaceVariant, fontWeight: FontWeight.w500),
              ),
              Text(
                item.value,
                style: GoogleFonts.inter(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  color: cs.onSurface,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

// ─── Read-only stop row ───────────────────────────────────────────────────────
class _ReadOnlyStopRow extends StatelessWidget {
  const _ReadOnlyStopRow({required this.stop});
  final DriverRouteStop stop;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    if (stop.isPickup) {
      const pickup = Color(0xFF0891B2);
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: cs.surfaceContainerLow,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: pickup.withValues(alpha: 0.4)),
        ),
        child: Row(
          children: [
            Container(
              width: 28,
              height: 28,
              decoration: BoxDecoration(color: pickup.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(7)),
              child: const Icon(Icons.warehouse_outlined, size: 15, color: pickup),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                'Chargement — Dépôt ${stop.sourceDepotName ?? ''}'.trim(),
                style: GoogleFonts.inter(fontSize: 12, fontWeight: FontWeight.w700, color: pickup),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      );
    }

    final Color dotColor;
    switch (stop.status) {
      case DriverRouteStopStatus.arrived:
      case DriverRouteStopStatus.completed:
      case DriverRouteStopStatus.partial:
        dotColor = cs.tertiary; break;
      case DriverRouteStopStatus.failed:
        dotColor = cs.error; break;
      case DriverRouteStopStatus.pending:
        dotColor = cs.onSurfaceVariant; break;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: cs.surfaceContainerLow,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: cs.outlineVariant),
      ),
      child: Row(
        children: [
          // Stop order badge
          Container(
            width: 28,
            height: 28,
            decoration: BoxDecoration(
              color: dotColor.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(7),
            ),
            child: Center(
              child: Text(
                '${stop.stopOrder}',
                style: Theme.of(context).textTheme.labelLarge?.copyWith(
                  fontSize: 12,
                  fontWeight: FontWeight.w800,
                  color: dotColor,
                ),
              ),
            ),
          ),
          const SizedBox(width: 10),

          // Address + client
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (stop.clientName != null)
                  Text(
                    stop.clientName!,
                    style: GoogleFonts.inter(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                      color: cs.onSurface,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                if (stop.address != null || stop.city != null)
                  Text(
                    [stop.address, stop.city].where((e) => e != null && e.isNotEmpty).join(', '),
                    style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                    overflow: TextOverflow.ellipsis,
                  ),
              ],
            ),
          ),

          // Amount
          if (stop.totalAmount != null)
            Text(
              '${stop.totalAmount!.toStringAsFixed(3)} TND',
              style: GoogleFonts.inter(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: cs.onSurface,
              ),
            ),
        ],
      ),
    );
  }
}
