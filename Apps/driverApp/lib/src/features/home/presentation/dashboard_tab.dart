import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';
import '../../deliveries/models/delivery_models.dart';
import '../../deliveries/presentation/delivery_detail_screen.dart';
import '../../profile/models/profile_models.dart';
import '../../routes/models/route_models.dart';

class DashboardTab extends ConsumerStatefulWidget {
  const DashboardTab({super.key});

  @override
  ConsumerState<DashboardTab> createState() => _DashboardTabState();
}

class _DashboardTabState extends ConsumerState<DashboardTab> {
  bool _routeWorking = false;

  Future<void> _refreshAll() async {
    ref.invalidate(driverProfileProvider);
    ref.invalidate(driverStatsProvider);
    ref.invalidate(activeDeliveriesProvider);
    ref.invalidate(driverHistoryProvider);
    ref.invalidate(todayRouteProvider);
  }

  Future<void> _startRoute(String routeId) async {
    if (_routeWorking) return;
    setState(() => _routeWorking = true);
    try {
      await ref.read(routeRepositoryProvider).start(routeId);
      ref.invalidate(todayRouteProvider);
    } finally {
      if (mounted) setState(() => _routeWorking = false);
    }
  }

  Future<void> _arriveStop(String routeId, String stopId) async {
    if (_routeWorking) return;
    setState(() => _routeWorking = true);
    try {
      await ref.read(routeRepositoryProvider).arrive(routeId, stopId);
      ref.invalidate(todayRouteProvider);
    } finally {
      if (mounted) setState(() => _routeWorking = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final profileAsync = ref.watch(driverProfileProvider);
    final statsAsync   = ref.watch(driverStatsProvider);
    final activeAsync  = ref.watch(activeDeliveriesProvider);
    final historyAsync = ref.watch(driverHistoryProvider);
    final routeAsync   = ref.watch(todayRouteProvider);

    return RefreshIndicator(
      color: AppColors.accent,
      backgroundColor: AppColors.surface,
      onRefresh: _refreshAll,
      child: CustomScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          // ── Navy header ──────────────────────────────────────────────────
          SliverToBoxAdapter(child: _NavyHeader(profileAsync: profileAsync)),

          SliverPadding(
            padding: const EdgeInsets.fromLTRB(16, 20, 16, 32),
            sliver: SliverList(
              delegate: SliverChildListDelegate([
                // Stats
                _StatsRow(statsAsync: statsAsync),
                const SizedBox(height: 20),
                // Active mission
                _ActiveMission(activeAsync: activeAsync),
                const SizedBox(height: 20),
                // Route summary
                _RouteCard(
                  routeAsync: routeAsync,
                  isWorking: _routeWorking,
                  onStart: _startRoute,
                  onArrive: _arriveStop,
                ),
                const SizedBox(height: 20),
                // Recent history
                _RecentHistory(historyAsync: historyAsync),
              ]),
            ),
          ),
        ],
      ),
    );
  }
}

// ─── Navy Header ──────────────────────────────────────────────────────────────
class _NavyHeader extends StatelessWidget {
  const _NavyHeader({required this.profileAsync});
  final AsyncValue<DriverProfile> profileAsync;

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final hour = now.hour;
    final greeting = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';
    final dateStr = DateFormat('EEEE, MMMM d').format(now);

    return Container(
      color: AppColors.navy,
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: profileAsync.when(
              data: (p) => Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    dateStr,
                    style: GoogleFonts.inter(fontSize: 12, color: Colors.white.withValues(alpha: 0.45), letterSpacing: 0.2),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '$greeting, ${p.name.split(' ').first}',
                    style: GoogleFonts.sora(fontSize: 22, fontWeight: FontWeight.w700, color: Colors.white, letterSpacing: -0.4),
                  ),
                  if (p.city != null) ...[
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        Icon(Icons.location_on_outlined, size: 12, color: Colors.white.withValues(alpha: 0.4)),
                        const SizedBox(width: 3),
                        Text(p.city!, style: GoogleFonts.manrope(fontSize: 12, color: Colors.white.withValues(alpha: 0.48), fontWeight: FontWeight.w500)),
                      ],
                    ),
                  ],
                ],
              ),
              loading: () => const SizedBox(height: 56),
              error: (_, __) => Text('Driver', style: GoogleFonts.sora(fontSize: 22, fontWeight: FontWeight.w700, color: Colors.white)),
            ),
          ),
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: AppColors.accent,
              borderRadius: BorderRadius.circular(13),
            ),
            child: const Icon(PhosphorIconsFill.van, color: Colors.white, size: 22),
          ),
        ],
      ),
    );
  }
}

// ─── Stats Row ────────────────────────────────────────────────────────────────
class _StatsRow extends StatelessWidget {
  const _StatsRow({required this.statsAsync});
  final AsyncValue<DriverStats> statsAsync;

  @override
  Widget build(BuildContext context) {
    return statsAsync.when(
      data: (s) {
        final queue = (s.totalDeliveries - s.delivered - s.failed).clamp(0, 9999);
        return Row(
          children: [
            Expanded(child: MetricTile(label: 'Delivered', value: '${s.delivered}', accentColor: AppColors.success, icon: PhosphorIconsBold.checkCircle)),
            const SizedBox(width: 10),
            Expanded(child: MetricTile(label: 'Failed', value: '${s.failed}', accentColor: AppColors.danger, icon: PhosphorIconsBold.xCircle)),
            const SizedBox(width: 10),
            Expanded(child: MetricTile(label: 'Pending', value: '$queue', accentColor: AppColors.accent, icon: PhosphorIconsBold.clockCountdown)),
          ],
        );
      },
      loading: () => const SizedBox(height: 88, child: Center(child: LoadingState())),
      error: (_, __) => const SizedBox.shrink(),
    );
  }
}

// ─── Active Mission ───────────────────────────────────────────────────────────
class _ActiveMission extends StatelessWidget {
  const _ActiveMission({required this.activeAsync});
  final AsyncValue<List<DriverDelivery>> activeAsync;

  @override
  Widget build(BuildContext context) {
    return DriveCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 30,
                height: 30,
                decoration: BoxDecoration(
                  color: AppColors.accentSubtle,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Icon(PhosphorIconsFill.lightning, size: 16, color: AppColors.accent),
              ),
              const SizedBox(width: 10),
              Text('Active Mission', style: Theme.of(context).textTheme.titleSmall),
            ],
          ),
          const SizedBox(height: 14),
          activeAsync.when(
            data: (items) {
              if (items.isEmpty) {
                return Text(
                  'No active delivery. Ops will push an assignment once a parcel is ready.',
                  style: GoogleFonts.inter(fontSize: 13, color: AppColors.muted, height: 1.5),
                );
              }
              final d = items.first;
              final col = d.status.badgeColor;
              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  StatusBadge(label: d.status.label, color: col),
                  const SizedBox(height: 10),
                  Text(d.address ?? 'Address unavailable',
                      style: GoogleFonts.inter(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.textPrimary)),
                  if (d.city != null) ...[
                    const SizedBox(height: 2),
                    Row(children: [
                      const Icon(Icons.location_on_outlined, size: 12, color: AppColors.muted),
                      const SizedBox(width: 3),
                      Text(d.city!, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                    ]),
                  ],
                  const SizedBox(height: 14),
                  DriveButton(
                    label: 'Open Mission',
                    icon: PhosphorIconsBold.arrowSquareOut,
                    fullWidth: true,
                    onPressed: () => Navigator.of(context).pushNamed(
                      DeliveryDetailScreen.routeName,
                      arguments: DeliveryDetailArgs(deliveryId: d.id),
                    ),
                  ),
                ],
              );
            },
            loading: () => const LoadingState(),
            error: (_, __) => const Text('Feed unavailable', style: TextStyle(color: AppColors.muted)),
          ),
        ],
      ),
    );
  }
}

// ─── Route Card ───────────────────────────────────────────────────────────────
class _RouteCard extends StatelessWidget {
  const _RouteCard({required this.routeAsync, required this.isWorking, required this.onStart, required this.onArrive});
  final AsyncValue<DriverRoute?> routeAsync;
  final bool isWorking;
  final Future<void> Function(String) onStart;
  final Future<void> Function(String, String) onArrive;

  @override
  Widget build(BuildContext context) {
    return DriveCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 30,
                height: 30,
                decoration: BoxDecoration(
                  color: AppColors.infoSubtle,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Icon(PhosphorIconsFill.path, size: 16, color: AppColors.info),
              ),
              const SizedBox(width: 10),
              Text("Today's Route", style: Theme.of(context).textTheme.titleSmall),
            ],
          ),
          const SizedBox(height: 14),
          routeAsync.when(
            data: (route) {
              if (route == null) {
                return Text('No route assigned yet.', style: GoogleFonts.inter(fontSize: 13, color: AppColors.muted));
              }
              final total = route.stops.length;
              final done  = route.stops.where((s) => s.status != DriverRouteStopStatus.pending).length;
              final next  = route.stops.where((s) => s.status == DriverRouteStopStatus.pending).firstOrNull;

              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(route.name,
                            style: GoogleFonts.inter(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.textPrimary),
                            overflow: TextOverflow.ellipsis),
                      ),
                      Text('$done / $total stops', style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                    ],
                  ),
                  const SizedBox(height: 8),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(3),
                    child: LinearProgressIndicator(
                      value: total == 0 ? 0.0 : done / total,
                      minHeight: 4,
                      backgroundColor: AppColors.surfaceDim,
                      valueColor: const AlwaysStoppedAnimation(AppColors.accent),
                    ),
                  ),
                  const SizedBox(height: 14),
                  if (route.status == DriverRouteStatus.validated)
                    DriveButton(
                      label: 'Start Route',
                      icon: PhosphorIconsBold.play,
                      variant: DriveButtonVariant.success,
                      fullWidth: true,
                      isLoading: isWorking,
                      onPressed: isWorking ? null : () => onStart(route.id),
                    ),
                  if (route.status == DriverRouteStatus.inProgress && next != null)
                    DriveButton(
                      label: 'Arrive — Stop ${next.stopOrder}',
                      icon: PhosphorIconsBold.flagPennant,
                      fullWidth: true,
                      isLoading: isWorking,
                      onPressed: isWorking ? null : () => onArrive(route.id, next.id),
                    ),
                  if (route.status == DriverRouteStatus.inProgress && next == null)
                    Text('All stops reached. Awaiting ops closure.',
                        style: const TextStyle(fontSize: 13, color: AppColors.muted)),
                  if (route.status == DriverRouteStatus.closed)
                    Text('Route closed for today.', style: const TextStyle(fontSize: 13, color: AppColors.muted)),
                ],
              );
            },
            loading: () => const LoadingState(),
            error: (_, __) => const Text('Route feed unavailable', style: TextStyle(color: AppColors.muted)),
          ),
        ],
      ),
    );
  }
}

// ─── Recent History ───────────────────────────────────────────────────────────
class _RecentHistory extends StatelessWidget {
  const _RecentHistory({required this.historyAsync});
  final AsyncValue<List<DriverDelivery>> historyAsync;

  @override
  Widget build(BuildContext context) {
    return DriveCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionHeader(title: 'Recent Deliveries', subtitle: 'Last completed missions'),
          const SizedBox(height: 14),
          historyAsync.when(
            data: (history) {
              if (history.isEmpty) {
                return const Text('No completed deliveries yet.', style: TextStyle(fontSize: 13, color: AppColors.muted));
              }
              final slice = history.take(3).toList();
              return Column(
                children: slice.asMap().entries.map((e) {
                  final d = e.value;
                  final isLast = e.key == slice.length - 1;
                  final ts = d.timestamps['completedAt'] ?? d.timestamps['failedAt'] ?? d.timestamps['cancelledAt'];
                  return GestureDetector(
                    onTap: () => Navigator.of(context).pushNamed(
                      DeliveryDetailScreen.routeName,
                      arguments: DeliveryDetailArgs(deliveryId: d.id),
                    ),
                    child: Column(
                      children: [
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 9),
                          child: Row(
                            children: [
                              Container(
                                width: 8,
                                height: 8,
                                decoration: BoxDecoration(color: d.status.badgeColor, shape: BoxShape.circle),
                              ),
                              const SizedBox(width: 10),
                              Expanded(
                                child: Text(d.address ?? 'Unknown address',
                                    style: GoogleFonts.inter(fontSize: 13, fontWeight: FontWeight.w500, color: AppColors.textPrimary),
                                    overflow: TextOverflow.ellipsis),
                              ),
                              const SizedBox(width: 8),
                              if (ts != null)
                                Text(DateFormat('MMM d').format(ts),
                                    style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                            ],
                          ),
                        ),
                        if (!isLast) const Divider(color: AppColors.border, height: 1),
                      ],
                    ),
                  );
                }).toList(),
              );
            },
            loading: () => const LoadingState(),
            error: (_, __) => const Text('History unavailable', style: TextStyle(color: AppColors.muted)),
          ),
        ],
      ),
    );
  }
}
