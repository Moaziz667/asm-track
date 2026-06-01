import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../models/route_models.dart';

class RouteCard extends StatelessWidget {
  const RouteCard({
    super.key,
    required this.route,
    required this.onTap,
  });

  final DriverRoute route;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final statusColor = _statusColor(context, route.status);
    final total = route.totalStops ?? route.stops.length;
    final done = route.completedStops ?? route.stops.where((s) => s.status != DriverRouteStopStatus.pending).length;
    final progress = total == 0 ? 0.0 : done / total;
    final showProgress = route.status == DriverRouteStatus.inProgress || route.status == DriverRouteStatus.closed;

    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: cs.surfaceContainerLow,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: route.status == DriverRouteStatus.cancelled
                ? cs.error
                : cs.outlineVariant,
            width: 1.2,
          ),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.04),
              blurRadius: 8,
              offset: const Offset(0, 2),
            ),
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Row 1: name + status chip
            Row(
              children: [
                Container(
                  width: 34,
                  height: 34,
                  decoration: BoxDecoration(
                    color: statusColor.withValues(alpha: 0.1),
                    borderRadius: BorderRadius.circular(9),
                  ),
                  child: Icon(_statusIcon(route.status), size: 16, color: statusColor),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    route.name,
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w700,
                      color: cs.onSurface,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 8),
                StatusChip(route.status),
              ],
            ),

            const SizedBox(height: 10),

            // Row 2: time window + zone pill + stop badge
            Wrap(
              spacing: 8,
              runSpacing: 6,
              children: [
                if (route.plannedStart != null || route.plannedEnd != null)
                  _MetaPill(
                    icon: PhosphorIconsRegular.clock,
                    label: _formatTimeWindow(route.plannedStart, route.plannedEnd),
                    color: cs.tertiary,
                  ),
                if (route.zone != null && route.zone!.isNotEmpty)
                  _MetaPill(
                    icon: PhosphorIconsRegular.mapPin,
                    label: route.zone!,
                    color: cs.onSurfaceVariant,
                  )
                else if (route.city != null && route.city!.isNotEmpty)
                  _MetaPill(
                    icon: PhosphorIconsRegular.mapPin,
                    label: route.city!,
                    color: cs.onSurfaceVariant,
                  ),
                _MetaPill(
                  icon: PhosphorIconsRegular.package,
                  label: '$total arrêt${total != 1 ? 's' : ''}',
                  color: cs.onSurfaceVariant,
                ),
              ],
            ),

            // Progress bar (inProgress / closed)
            if (showProgress) ...[
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(3),
                      child: LinearProgressIndicator(
                        value: progress,
                        minHeight: 5,
                        backgroundColor: cs.surfaceContainerHigh,
                        valueColor: AlwaysStoppedAnimation(
                          route.status == DriverRouteStatus.closed
                              ? cs.tertiary
                              : cs.primary,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Text(
                    '$done/$total',
                    style: GoogleFonts.inter(
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                      color: route.status == DriverRouteStatus.closed
                          ? cs.tertiary
                          : cs.primary,
                    ),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  static String _formatTimeWindow(String? start, String? end) {
    if (start != null && end != null) return '$start – $end';
    if (start != null) return 'Dès $start';
    if (end != null) return 'Avant $end';
    return '';
  }

  static Color _statusColor(BuildContext context, DriverRouteStatus s) {
    final cs = Theme.of(context).colorScheme;
    switch (s) {
      case DriverRouteStatus.draft:       return cs.onSurfaceVariant;
      case DriverRouteStatus.validated:   return cs.tertiary;
      case DriverRouteStatus.inProgress:  return cs.primary;
      case DriverRouteStatus.closed:      return cs.tertiary;
      case DriverRouteStatus.cancelled:   return cs.error;
    }
  }

  static IconData _statusIcon(DriverRouteStatus s) {
    switch (s) {
      case DriverRouteStatus.draft:       return PhosphorIconsRegular.pencilSimple;
      case DriverRouteStatus.validated:   return PhosphorIconsRegular.checkCircle;
      case DriverRouteStatus.inProgress:  return PhosphorIconsFill.path;
      case DriverRouteStatus.closed:      return PhosphorIconsFill.checkCircle;
      case DriverRouteStatus.cancelled:   return PhosphorIconsRegular.xCircle;
    }
  }
}

class StatusChip extends StatelessWidget {
  const StatusChip(this.status);
  final DriverRouteStatus status;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final Color bg;
    final Color fg;
    switch (status) {
      case DriverRouteStatus.draft:
        bg = cs.surfaceContainerHighest; fg = cs.onSurfaceVariant; break;
      case DriverRouteStatus.validated:
        bg = cs.tertiaryContainer; fg = cs.tertiary; break;
      case DriverRouteStatus.inProgress:
        bg = cs.primaryContainer; fg = cs.primary; break;
      case DriverRouteStatus.closed:
        bg = cs.tertiaryContainer; fg = cs.tertiary; break;
      case DriverRouteStatus.cancelled:
        bg = cs.errorContainer; fg = cs.error; break;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        status.label,
        style: GoogleFonts.inter(
          fontSize: 9,
          fontWeight: FontWeight.w800,
          color: fg,
        ),
      ),
    );
  }
}

class _MetaPill extends StatelessWidget {
  const _MetaPill({required this.icon, required this.label, required this.color});
  final IconData icon;
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: cs.outlineVariant),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 11, color: color),
          const SizedBox(width: 4),
          Text(
            label,
            style: GoogleFonts.inter(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}
