import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../models/route_models.dart';
import 'route_detail_sheet.dart';
import 'widgets/route_card.dart';

// ── Selected day state ────────────────────────────────────────────────────────
final selectedCalendarDayProvider = StateProvider<DateTime>((ref) {
  final now = DateTime.now();
  return DateTime(now.year, now.month, now.day);
});

// ── Locale helpers ────────────────────────────────────────────────────────────
const _kMonths = [
  'Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin',
  'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre',
];
const _kDayLetters = ['L', 'M', 'M', 'J', 'V', 'S', 'D'];
const _kDayFull = ['Lundi', 'Mardi', 'Mercredi', 'Jeudi', 'Vendredi', 'Samedi', 'Dimanche'];

bool _sameDay(DateTime a, DateTime b) =>
    a.year == b.year && a.month == b.month && a.day == b.day;

DateTime _weekStartOf(DateTime d) =>
    DateTime(d.year, d.month, d.day).subtract(Duration(days: d.weekday - 1));

int _weekNumber(DateTime date) {
  final startOfYear = DateTime(date.year, 1, 1);
  final doy = date.difference(startOfYear).inDays + 1;
  return ((doy + startOfYear.weekday - 2) / 7).floor() + 1;
}

// ─────────────────────────────────────────────────────────────────────────────
class CalendarScreen extends ConsumerWidget {
  const CalendarScreen({super.key, required this.onNavigateToRoute});
  final VoidCallback onNavigateToRoute;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final weekStart  = ref.watch(calendarWeekProvider);
    final selectedDay = ref.watch(selectedCalendarDayProvider);
    final routesAsync = ref.watch(weekRoutesProvider(weekStart));

    void changeWeek(DateTime newStart) {
      ref.read(calendarWeekProvider.notifier).state = newStart;
      final today = DateTime.now();
      final todayNorm = DateTime(today.year, today.month, today.day);
      final inNewWeek = !todayNorm.isBefore(newStart) &&
          todayNorm.isBefore(newStart.add(const Duration(days: 7)));
      ref.read(selectedCalendarDayProvider.notifier).state =
          inNewWeek ? todayNorm : newStart;
    }

    void jumpToToday() {
      final now = DateTime.now();
      final todayNorm = DateTime(now.year, now.month, now.day);
      ref.read(calendarWeekProvider.notifier).state = _weekStartOf(todayNorm);
      ref.read(selectedCalendarDayProvider.notifier).state = todayNorm;
    }

    final routes = routesAsync.valueOrNull ?? const [];

    return Scaffold(
      backgroundColor: cs.surface,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // ── Header ────────────────────────────────────────────────────
            _CalendarHeader(
              weekStart: weekStart,
              selectedDay: selectedDay,
              onJumpToday: jumpToToday,
            ),

            const SizedBox(height: 20),

            // ── Week strip ────────────────────────────────────────────────
            _WeekStrip(
              weekStart: weekStart,
              selectedDay: selectedDay,
              routes: routes,
              loading: routesAsync.isLoading,
              onDaySelected: (d) =>
                  ref.read(selectedCalendarDayProvider.notifier).state = d,
              onWeekChanged: changeWeek,
            ),

            const SizedBox(height: 16),

            // ── Day stats bar ─────────────────────────────────────────────
            AnimatedSwitcher(
              duration: const Duration(milliseconds: 220),
              child: _DayStatsBar(
                key: ValueKey('${selectedDay.day}-${selectedDay.month}'),
                routes: routes,
                selectedDay: selectedDay,
              ),
            ),

            const SizedBox(height: 20),

            // ── Route list ────────────────────────────────────────────────
            Expanded(
              child: routesAsync.when(
                data: (allRoutes) {
                  final dayRoutes = allRoutes
                      .where((r) => r.date != null && _sameDay(r.date!, selectedDay))
                      .toList()
                    ..sort((a, b) {
                      const order = [
                        DriverRouteStatus.inProgress,
                        DriverRouteStatus.validated,
                        DriverRouteStatus.closed,
                        DriverRouteStatus.draft,
                        DriverRouteStatus.cancelled,
                      ];
                      return order.indexOf(a.status).compareTo(order.indexOf(b.status));
                    });
                  return _DayRouteList(
                    routes: dayRoutes,
                    selectedDay: selectedDay,
                    onNavigateToRoute: onNavigateToRoute,
                  );
                },
                loading: () => Center(
                  child: CircularProgressIndicator(
                    color: cs.primary,
                    strokeWidth: 2,
                  ),
                ),
                error: (e, _) => _ErrorState(message: e.toString()),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Header ───────────────────────────────────────────────────────────────────
class _CalendarHeader extends StatelessWidget {
  const _CalendarHeader({
    required this.weekStart,
    required this.selectedDay,
    required this.onJumpToday,
  });

  final DateTime weekStart;
  final DateTime selectedDay;
  final VoidCallback onJumpToday;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final today = DateTime.now();
    final todayNorm = DateTime(today.year, today.month, today.day);
    final currentWeekStart = _weekStartOf(todayNorm);
    final isCurrentWeek = _sameDay(weekStart, currentWeekStart);
    final weekNum = _weekNumber(weekStart);

    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 0),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${_kMonths[selectedDay.month - 1]} ${selectedDay.year}',
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.w900,
                    color: cs.onSurface,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  'Semaine $weekNum',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                    color: cs.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          if (!isCurrentWeek)
            GestureDetector(
              onTap: onJumpToday,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                decoration: BoxDecoration(
                  color: cs.primary.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(24),
                  border: Border.all(
                    color: cs.primary.withValues(alpha: 0.35),
                    width: 1,
                  ),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(PhosphorIconsBold.calendar, size: 13, color: cs.primary),
                    const SizedBox(width: 6),
                    Text(
                      "Aujourd'hui",
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        color: cs.primary,
                      ),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}

// ─── Week strip ───────────────────────────────────────────────────────────────
class _WeekStrip extends StatelessWidget {
  const _WeekStrip({
    required this.weekStart,
    required this.selectedDay,
    required this.routes,
    required this.loading,
    required this.onDaySelected,
    required this.onWeekChanged,
  });

  final DateTime weekStart;
  final DateTime selectedDay;
  final List<DriverRoute> routes;
  final bool loading;
  final ValueChanged<DateTime> onDaySelected;
  final ValueChanged<DateTime> onWeekChanged;

  // route count and dominant status per day
  Map<String, List<DriverRoute>> _byDay() {
    final map = <String, List<DriverRoute>>{};
    for (final r in routes) {
      if (r.date != null) {
        final k = _dayKey(r.date!);
        map.putIfAbsent(k, () => []).add(r);
      }
    }
    return map;
  }

  static String _dayKey(DateTime d) => '${d.year}-${d.month}-${d.day}';

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final today = DateTime.now();
    final todayNorm = DateTime(today.year, today.month, today.day);
    final byDay = _byDay();

    return GestureDetector(
      onHorizontalDragEnd: (d) {
        final v = d.primaryVelocity;
        if (v == null) return;
        if (v < -400) onWeekChanged(weekStart.add(const Duration(days: 7)));
        if (v > 400) onWeekChanged(weekStart.subtract(const Duration(days: 7)));
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 4),
          decoration: BoxDecoration(
            color: cs.surfaceContainerLow,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: cs.outlineVariant),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.12),
                blurRadius: 16,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: Row(
            children: [
              // ← prev week
              _NavArrow(
                icon: PhosphorIconsBold.caretLeft,
                onTap: () => onWeekChanged(weekStart.subtract(const Duration(days: 7))),
              ),
              // 7 day cells
              Expanded(
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: List.generate(7, (i) {
                    final day = weekStart.add(Duration(days: i));
                    final dayRoutes = byDay[_dayKey(day)] ?? [];
                    return _DayCell(
                      day: day,
                      isToday: _sameDay(day, todayNorm),
                      isSelected: _sameDay(day, selectedDay),
                      dayRoutes: dayRoutes,
                      loading: loading,
                      onTap: () => onDaySelected(day),
                    );
                  }),
                ),
              ),
              // → next week
              _NavArrow(
                icon: PhosphorIconsBold.caretRight,
                onTap: () => onWeekChanged(weekStart.add(const Duration(days: 7))),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _NavArrow extends StatelessWidget {
  const _NavArrow({required this.icon, required this.onTap});
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: SizedBox(
        width: 28,
        height: 64,
        child: Icon(icon, size: 13, color: cs.onSurfaceVariant),
      ),
    );
  }
}

class _DayCell extends StatelessWidget {
  const _DayCell({
    required this.day,
    required this.isToday,
    required this.isSelected,
    required this.dayRoutes,
    required this.loading,
    required this.onTap,
  });

  final DateTime day;
  final bool isToday;
  final bool isSelected;
  final List<DriverRoute> dayRoutes;
  final bool loading;
  final VoidCallback onTap;

  static DriverRouteStatus? _dominant(List<DriverRoute> routes) {
    if (routes.isEmpty) return null;
    const order = [
      DriverRouteStatus.inProgress,
      DriverRouteStatus.validated,
      DriverRouteStatus.closed,
      DriverRouteStatus.cancelled,
      DriverRouteStatus.draft,
    ];
    for (final s in order) {
      if (routes.any((r) => r.status == s)) return s;
    }
    return routes.first.status;
  }

  static Color _statusColor(BuildContext context, DriverRouteStatus s) {
    final cs = Theme.of(context).colorScheme;
    switch (s) {
      case DriverRouteStatus.draft:      return cs.onSurfaceVariant;
      case DriverRouteStatus.validated:  return cs.tertiary;
      case DriverRouteStatus.inProgress: return cs.primary;
      case DriverRouteStatus.closed:     return cs.tertiary;
      case DriverRouteStatus.cancelled:  return cs.error;
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final hasRoutes = dayRoutes.isNotEmpty;
    final dominant = _dominant(dayRoutes);
    final dotColor = dominant != null ? _statusColor(context, dominant) : null;
    final routeCount = dayRoutes.length;

    // Circle styling
    final Color circleBg;
    final Color circleText;
    final BoxBorder? circleBorder;

    if (isToday && isSelected) {
      circleBg = cs.primary;
      circleText = Colors.black;
      circleBorder = null;
    } else if (isToday) {
      circleBg = cs.primary.withValues(alpha: 0.85);
      circleText = Colors.black;
      circleBorder = null;
    } else if (isSelected) {
      circleBg = cs.primary.withValues(alpha: 0.15);
      circleText = cs.primary;
      circleBorder = Border.all(color: cs.primary, width: 1.5);
    } else {
      circleBg = Colors.transparent;
      circleText = hasRoutes ? cs.onSurface : cs.onSurfaceVariant;
      circleBorder = null;
    }

    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: SizedBox(
        width: 36,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // Day letter
            Text(
              _kDayLetters[day.weekday - 1],
              style: TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w700,
                color: isSelected || isToday ? cs.primary : cs.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 6),
            // Circle with day number
            Container(
              width: 34,
              height: 34,
              decoration: BoxDecoration(
                color: circleBg,
                shape: BoxShape.circle,
                border: circleBorder,
              ),
              child: Center(
                child: Text(
                  '${day.day}',
                  style: Theme.of(context).textTheme.titleSmall?.copyWith(
                    fontSize: 14,
                    fontWeight: FontWeight.w800,
                    color: circleText,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 7),
            // Route count badge or empty placeholder
            if (!loading && hasRoutes && dotColor != null)
              Container(
                height: 16,
                constraints: const BoxConstraints(minWidth: 16),
                padding: const EdgeInsets.symmetric(horizontal: 5),
                decoration: BoxDecoration(
                  color: dotColor.withValues(alpha: 0.18),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                    color: dotColor.withValues(alpha: 0.45),
                    width: 0.5,
                  ),
                ),
                child: Center(
                  child: Text(
                    '$routeCount',
                    style: TextStyle(
                      fontSize: 9,
                      fontWeight: FontWeight.w800,
                      color: dotColor,
                      height: 1,
                    ),
                  ),
                ),
              )
            else if (loading && isSelected)
              SizedBox(
                width: 10,
                height: 10,
                child: CircularProgressIndicator(
                  strokeWidth: 1.5,
                  color: cs.primary.withValues(alpha: 0.5),
                ),
              )
            else
              const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }
}

// ─── Day stats bar ────────────────────────────────────────────────────────────
class _DayStatsBar extends StatelessWidget {
  const _DayStatsBar({super.key, required this.routes, required this.selectedDay});
  final List<DriverRoute> routes;
  final DateTime selectedDay;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final dayRoutes = routes
        .where((r) => r.date != null && _sameDay(r.date!, selectedDay))
        .toList();

    if (dayRoutes.isEmpty) return const SizedBox.shrink();

    final totalRoutes = dayRoutes.length;
    final totalStops = dayRoutes.fold<int>(
      0, (s, r) => s + (r.totalStops ?? r.stops.length),
    );
    final completedStops = dayRoutes.fold<int>(
      0, (s, r) => s + (r.completedStops ?? 0),
    );
    final hasProgress = dayRoutes.any(
      (r) => r.status == DriverRouteStatus.inProgress || r.status == DriverRouteStatus.closed,
    );
    final inProgressCount = dayRoutes
        .where((r) => r.status == DriverRouteStatus.inProgress)
        .length;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
        decoration: BoxDecoration(
          color: cs.surfaceContainerLow,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: cs.outlineVariant),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.06),
              blurRadius: 8,
              offset: const Offset(0, 3),
            ),
          ],
        ),
        child: Row(
          children: [
            _StatItem(
              value: '$totalRoutes',
              label: totalRoutes > 1 ? 'Tournées' : 'Tournée',
              color: cs.tertiary,
              icon: PhosphorIconsRegular.path,
            ),
            _StatDivider(),
            _StatItem(
              value: '$totalStops',
              label: 'Arrêts',
              color: cs.primary,
              icon: PhosphorIconsRegular.package,
            ),
            if (hasProgress) ...[
              _StatDivider(),
              _StatItem(
                value: '$completedStops/$totalStops',
                label: 'Livrés',
                color: cs.tertiary,
                icon: PhosphorIconsRegular.checkCircle,
              ),
            ],
            if (inProgressCount > 0) ...[
              _StatDivider(),
              _StatItem(
                value: '$inProgressCount',
                label: 'En cours',
                color: cs.secondary,
                icon: PhosphorIconsFill.circle,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _StatItem extends StatelessWidget {
  const _StatItem({
    required this.value,
    required this.label,
    required this.color,
    required this.icon,
  });
  final String value;
  final String label;
  final Color color;
  final IconData icon;

  @override
  Widget build(BuildContext context) => Expanded(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 14, color: color.withValues(alpha: 0.7)),
            const SizedBox(height: 5),
            Text(
              value,
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                fontSize: 17,
                fontWeight: FontWeight.w900,
                color: color,
              ),
            ),
            const SizedBox(height: 2),
            Text(
              label,
              style: TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w500,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      );
}

class _StatDivider extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      width: 1,
      height: 32,
      color: cs.outlineVariant,
      margin: const EdgeInsets.symmetric(horizontal: 4),
    );
  }
}

// ─── Day route list ───────────────────────────────────────────────────────────
class _DayRouteList extends StatelessWidget {
  const _DayRouteList({
    required this.routes,
    required this.selectedDay,
    required this.onNavigateToRoute,
  });

  final List<DriverRoute> routes;
  final DateTime selectedDay;
  final VoidCallback onNavigateToRoute;

  String _dayLabel() {
    final today = DateTime.now();
    final todayNorm = DateTime(today.year, today.month, today.day);
    final tomorrow = todayNorm.add(const Duration(days: 1));
    if (_sameDay(selectedDay, todayNorm)) return "Aujourd'hui";
    if (_sameDay(selectedDay, tomorrow)) return 'Demain';
    return '${_kDayFull[selectedDay.weekday - 1]} ${selectedDay.day} ${_kMonths[selectedDay.month - 1]}';
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final label = _dayLabel();

    if (routes.isEmpty) return _EmptyDay(label: label);

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 32),
      children: [
        // Section title bar
        Padding(
          padding: const EdgeInsets.only(bottom: 14),
          child: Row(
            children: [
              Text(
                label,
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  fontWeight: FontWeight.w900,
                  color: cs.onSurfaceVariant,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(child: Container(height: 1, color: cs.outlineVariant)),
              const SizedBox(width: 10),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: cs.primary.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  '${routes.length}',
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    fontWeight: FontWeight.w900,
                    color: cs.primary,
                  ),
                ),
              ),
            ],
          ),
        ),
        // Route cards
        ...routes.map((route) => Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: RouteCard(
                route: route,
                onTap: () => _handleTap(context, route, onNavigateToRoute),
              ),
            )),
      ],
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
    if (route.isToday &&
        (route.status == DriverRouteStatus.validated ||
            route.status == DriverRouteStatus.inProgress)) {
      onNavigateToRoute();
      return;
    }
    RouteDetailSheet.show(context, route);
  }

  static void _showCancelledSheet(BuildContext context, DriverRoute route) {
    final cs = Theme.of(context).colorScheme;
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (_) => Container(
        decoration: BoxDecoration(
          color: cs.surfaceContainerLow,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        ),
        padding: const EdgeInsets.fromLTRB(24, 0, 24, 40),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
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
            Container(
              width: 52,
              height: 52,
              decoration: BoxDecoration(
                color: cs.errorContainer,
                borderRadius: BorderRadius.circular(14),
              ),
              child: Icon(PhosphorIconsFill.xCircle, size: 24, color: cs.error),
            ),
            const SizedBox(height: 14),
            Text(
              'Tournée annulée',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                fontWeight: FontWeight.w700,
                color: cs.onSurface,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              'La tournée "${route.name}" a été annulée par la dispatch.',
              style: TextStyle(fontSize: 14, color: cs.onSurfaceVariant, height: 1.5),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            SizedBox(
              width: double.infinity,
              child: TextButton(
                onPressed: () => Navigator.of(context).pop(),
                style: TextButton.styleFrom(
                  foregroundColor: cs.onSurfaceVariant,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                    side: BorderSide(color: cs.outlineVariant),
                  ),
                ),
                child: Text(
                  'Fermer',
                  style: Theme.of(context).textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.w700,
                    fontSize: 14,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Empty day ────────────────────────────────────────────────────────────────
class _EmptyDay extends StatelessWidget {
  const _EmptyDay({required this.label});
  final String label;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 72,
            height: 72,
            decoration: BoxDecoration(
              color: cs.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(22),
              border: Border.all(color: cs.outlineVariant),
            ),
            child: Icon(
              PhosphorIconsRegular.calendarBlank,
              size: 30,
              color: cs.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 18),
          Text(
            'Aucune tournée',
            style: Theme.of(context).textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.w700,
              color: cs.onSurface,
            ),
          ),
          const SizedBox(height: 5),
          Text(
            label,
            style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

// ─── Error state ──────────────────────────────────────────────────────────────
class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.message});
  final String message;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(PhosphorIconsRegular.cloudSlash, size: 34, color: cs.onSurfaceVariant),
          const SizedBox(height: 12),
          Text(
            'Impossible de charger les tournées',
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: cs.onSurface,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            'Vérifiez votre connexion et réessayez.',
            style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}
