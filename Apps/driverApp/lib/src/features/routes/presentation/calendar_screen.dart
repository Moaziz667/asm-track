import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../../../theme/app_theme.dart';
import '../models/route_models.dart';
import 'route_detail_sheet.dart';
import 'widgets/route_card.dart';

class CalendarScreen extends ConsumerWidget {
  const CalendarScreen({super.key, required this.onNavigateToRoute});

  /// Called when the driver taps today's VALIDATED or IN_PROGRESS route to
  /// switch back to the map / route tab (tab 0).
  final VoidCallback onNavigateToRoute;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final weekStart = ref.watch(calendarWeekProvider);
    final routesAsync = ref.watch(weekRoutesProvider(weekStart));

    return Scaffold(
      backgroundColor: AppColors.background,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // ── Header ──────────────────────────────────────────────────────
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
              child: Text(
                'Calendrier',
                style: GoogleFonts.sora(
                  fontSize: 22,
                  fontWeight: FontWeight.w800,
                  color: AppColors.textPrimary,
                  letterSpacing: -0.5,
                ),
              ),
            ),

            const SizedBox(height: 16),

            // ── Week strip ──────────────────────────────────────────────────
            routesAsync.when(
              data: (routes) => _WeekStrip(
                weekStart: weekStart,
                routes: routes,
                onWeekChanged: (d) => ref.read(calendarWeekProvider.notifier).state = d,
              ),
              loading: () => _WeekStrip(
                weekStart: weekStart,
                routes: const [],
                onWeekChanged: (d) => ref.read(calendarWeekProvider.notifier).state = d,
              ),
              error: (_, __) => _WeekStrip(
                weekStart: weekStart,
                routes: const [],
                onWeekChanged: (d) => ref.read(calendarWeekProvider.notifier).state = d,
              ),
            ),

            const SizedBox(height: 16),

            // ── Route card list ─────────────────────────────────────────────
            Expanded(
              child: routesAsync.when(
                data: (routes) => _RouteList(
                  routes: routes,
                  onNavigateToRoute: onNavigateToRoute,
                ),
                loading: () => const Center(
                  child: CircularProgressIndicator(
                    color: AppColors.accent,
                    strokeWidth: 2.5,
                  ),
                ),
                error: (err, _) => _CalendarError(message: err.toString()),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Week strip ───────────────────────────────────────────────────────────────
class _WeekStrip extends StatelessWidget {
  const _WeekStrip({
    required this.weekStart,
    required this.routes,
    required this.onWeekChanged,
  });

  final DateTime weekStart;
  final List<DriverRoute> routes;
  final ValueChanged<DateTime> onWeekChanged;

  @override
  Widget build(BuildContext context) {
    final today = DateTime.now();

    // Build a map from date → list of routes for that day
    final Map<String, List<DriverRoute>> byDay = {};
    for (final r in routes) {
      if (r.date != null) {
        final key = _dateKey(r.date!);
        byDay.putIfAbsent(key, () => []).add(r);
      }
    }

    return Container(
      height: 90,
      margin: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.border),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.04),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Row(
        children: [
          // Prev week
          _ArrowBtn(
            icon: PhosphorIconsBold.caretLeft,
            onTap: () => onWeekChanged(weekStart.subtract(const Duration(days: 7))),
          ),

          // 7 day cells
          Expanded(
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: List.generate(7, (i) {
                final day = weekStart.add(Duration(days: i));
                final isToday = day.year == today.year &&
                    day.month == today.month &&
                    day.day == today.day;
                final dayRoutes = byDay[_dateKey(day)] ?? [];
                return _DayCell(
                  day: day,
                  isToday: isToday,
                  routes: dayRoutes,
                );
              }),
            ),
          ),

          // Next week
          _ArrowBtn(
            icon: PhosphorIconsBold.caretRight,
            onTap: () => onWeekChanged(weekStart.add(const Duration(days: 7))),
          ),
        ],
      ),
    );
  }

  static String _dateKey(DateTime d) => '${d.year}-${d.month}-${d.day}';
}

class _ArrowBtn extends StatelessWidget {
  const _ArrowBtn({required this.icon, required this.onTap});
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: SizedBox(
          width: 32,
          height: double.infinity,
          child: Icon(icon, size: 14, color: AppColors.muted),
        ),
      );
}

class _DayCell extends StatelessWidget {
  const _DayCell({
    required this.day,
    required this.isToday,
    required this.routes,
  });

  final DateTime day;
  final bool isToday;
  final List<DriverRoute> routes;

  static const _dayLabels = ['Lu', 'Ma', 'Me', 'Je', 'Ve', 'Sa', 'Di'];

  @override
  Widget build(BuildContext context) {
    final dayLabel = _dayLabels[day.weekday - 1];

    // Dominant status = highest priority route for that day
    final dominantStatus = routes.isEmpty
        ? null
        : _dominantStatus(routes);

    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Text(
          dayLabel,
          style: GoogleFonts.inter(
            fontSize: 10,
            fontWeight: FontWeight.w600,
            color: isToday ? AppColors.accent : AppColors.muted,
          ),
        ),
        const SizedBox(height: 4),
        Container(
          width: 30,
          height: 30,
          decoration: BoxDecoration(
            color: isToday ? AppColors.accent : Colors.transparent,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Center(
            child: Text(
              '${day.day}',
              style: GoogleFonts.manrope(
                fontSize: 13,
                fontWeight: FontWeight.w800,
                color: isToday ? Colors.black : AppColors.textPrimary,
              ),
            ),
          ),
        ),
        const SizedBox(height: 5),
        // Status dot
        if (dominantStatus != null)
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              color: _dotColor(dominantStatus),
              shape: BoxShape.circle,
            ),
          )
        else
          const SizedBox(width: 6, height: 6),
      ],
    );
  }

  static DriverRouteStatus _dominantStatus(List<DriverRoute> routes) {
    // Priority: inProgress > validated > closed > cancelled > draft
    const priority = [
      DriverRouteStatus.inProgress,
      DriverRouteStatus.validated,
      DriverRouteStatus.closed,
      DriverRouteStatus.cancelled,
      DriverRouteStatus.draft,
    ];
    for (final s in priority) {
      if (routes.any((r) => r.status == s)) return s;
    }
    return routes.first.status;
  }

  static Color _dotColor(DriverRouteStatus s) {
    switch (s) {
      case DriverRouteStatus.draft:       return AppColors.muted;
      case DriverRouteStatus.validated:   return AppColors.info;
      case DriverRouteStatus.inProgress:  return AppColors.accent;
      case DriverRouteStatus.closed:      return AppColors.success;
      case DriverRouteStatus.cancelled:   return AppColors.danger;
    }
  }
}

// ─── Route card list ──────────────────────────────────────────────────────────
class _RouteList extends StatelessWidget {
  const _RouteList({required this.routes, required this.onNavigateToRoute});
  final List<DriverRoute> routes;
  final VoidCallback onNavigateToRoute;

  @override
  Widget build(BuildContext context) {
    if (routes.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                color: AppColors.surfaceElevated,
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: AppColors.border),
              ),
              child: const Icon(PhosphorIconsRegular.calendarBlank, size: 26, color: AppColors.muted),
            ),
            const SizedBox(height: 12),
            Text(
              'Aucune tournée cette semaine',
              style: GoogleFonts.inter(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: AppColors.textPrimary,
              ),
            ),
            const SizedBox(height: 4),
            const Text(
              'Les tournées planifiées apparaîtront ici.',
              style: TextStyle(fontSize: 13, color: AppColors.muted),
            ),
          ],
        ),
      );
    }

    // Sort routes: today first, then by date
    final sorted = [...routes]..sort((a, b) {
        final aDate = a.date ?? DateTime(2100);
        final bDate = b.date ?? DateTime(2100);
        return aDate.compareTo(bDate);
      });

    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
      itemCount: sorted.length,
      separatorBuilder: (_, __) => const SizedBox(height: 10),
      itemBuilder: (context, i) {
        final route = sorted[i];
        return RouteCard(
          route: route,
          onTap: () => _handleTap(context, route, onNavigateToRoute),
        );
      },
    );
  }

  static void _handleTap(
    BuildContext context,
    DriverRoute route,
    VoidCallback onNavigateToRoute,
  ) {
    if (route.status == DriverRouteStatus.cancelled) {
      _showCancelledSheet(context, route);
      return;
    }

    // Today's active route → navigate to map tab
    if (route.isToday &&
        (route.status == DriverRouteStatus.validated ||
            route.status == DriverRouteStatus.inProgress)) {
      onNavigateToRoute();
      return;
    }

    // Everything else → read-only detail sheet
    RouteDetailSheet.show(context, route);
  }

  static void _showCancelledSheet(BuildContext context, DriverRoute route) {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (_) => Container(
        decoration: const BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
        padding: const EdgeInsets.fromLTRB(24, 0, 24, 40),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Handle
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(
                  color: AppColors.surfaceDim,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),

            Container(
              width: 52,
              height: 52,
              decoration: BoxDecoration(
                color: AppColors.dangerSubtle,
                borderRadius: BorderRadius.circular(14),
              ),
              child: const Icon(PhosphorIconsFill.xCircle, size: 24, color: AppColors.danger),
            ),
            const SizedBox(height: 14),
            Text(
              'Tournée annulée',
              style: GoogleFonts.sora(
                fontSize: 18,
                fontWeight: FontWeight.w700,
                color: AppColors.textPrimary,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              'La tournée "${route.name}" a été annulée par la dispatch.',
              style: const TextStyle(
                fontSize: 14,
                color: AppColors.muted,
                height: 1.5,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            SizedBox(
              width: double.infinity,
              child: TextButton(
                onPressed: () => Navigator.of(context).pop(),
                style: TextButton.styleFrom(
                  foregroundColor: AppColors.textSecondary,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                    side: const BorderSide(color: AppColors.border),
                  ),
                ),
                child: Text(
                  'Fermer',
                  style: GoogleFonts.manrope(fontWeight: FontWeight.w700, fontSize: 14),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Error state ──────────────────────────────────────────────────────────────
class _CalendarError extends StatelessWidget {
  const _CalendarError({required this.message});
  final String message;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(PhosphorIconsRegular.cloudSlash, size: 32, color: AppColors.muted),
          const SizedBox(height: 10),
          Text(
            'Impossible de charger les tournées',
            style: GoogleFonts.inter(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: AppColors.textPrimary,
            ),
          ),
          const SizedBox(height: 4),
          const Text(
            'Vérifiez votre connexion et réessayez.',
            style: TextStyle(fontSize: 12, color: AppColors.muted),
          ),
        ],
      ),
    );
  }
}
