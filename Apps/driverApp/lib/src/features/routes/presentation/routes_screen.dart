import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:latlong2/latlong.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';
import '../../deliveries/presentation/delivery_detail_screen.dart';
import '../models/route_models.dart';

// Default map center — Algeria (Algiers). Will be replaced by real coords later.
const _kDefaultCenter = LatLng(36.7372, 3.0867);

class RoutesScreen extends ConsumerStatefulWidget {
  const RoutesScreen({super.key});

  @override
  ConsumerState<RoutesScreen> createState() => _RoutesScreenState();
}

class _RoutesScreenState extends ConsumerState<RoutesScreen> {
  final _mapController = MapController();
  bool _isWorking = false;
  final _sheetController = DraggableScrollableController();

  @override
  void dispose() {
    _mapController.dispose();
    _sheetController.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    ref.invalidate(todayRouteProvider);
    ref.invalidate(activeDeliveriesProvider);
  }

  Future<void> _doAction(Future<void> Function() fn) async {
    if (_isWorking) return;
    setState(() => _isWorking = true);
    try {
      await fn();
      await _refresh();
    } finally {
      if (mounted) setState(() => _isWorking = false);
    }
  }

  Future<void> _startRoute(String id) => _doAction(() async {
        final pt = await LocationService().currentPosition();
        if (pt != null) {
          await ref.read(profileRepositoryProvider).updateLocation(pt.lat, pt.lng);
        }
        await ref.read(routeRepositoryProvider).start(id);
      });

  Future<void> _arriveStop(String routeId, String stopId) => _doAction(() async {
        final pt = await LocationService().currentPosition();
        if (pt != null) {
          await ref.read(profileRepositoryProvider).updateLocation(pt.lat, pt.lng);
        }
        await ref.read(routeRepositoryProvider).arrive(routeId, stopId);
      });

  /// Generates evenly-spread fake stop coordinates around [center].
  /// Replace with real delivery lat/lng once available from API.
  List<LatLng> _fakeStopCoords(int count, LatLng center) {
    final coords = <LatLng>[];
    for (int i = 0; i < count; i++) {
      final angle = (2 * math.pi / count) * i;
      final radius = 0.012 + (i % 3) * 0.004;
      coords.add(LatLng(
        center.latitude + radius * math.sin(angle),
        center.longitude + radius * math.cos(angle),
      ));
    }
    return coords;
  }

  @override
  Widget build(BuildContext context) {
    final routeAsync = ref.watch(todayRouteProvider);

    return Scaffold(
      backgroundColor: AppColors.background,
      body: routeAsync.when(
        data: (route) => _RouteMapBody(
          route: route,
          isWorking: _isWorking,
          mapController: _mapController,
          sheetController: _sheetController,
          fakeCoords: route == null ? [] : _fakeStopCoords(route.stops.length, _kDefaultCenter),
          onStart: route == null ? null : () => _startRoute(route.id),
          onArrive: route == null ? null : (stopId) => _arriveStop(route.id, stopId),
          onRefresh: _refresh,
        ),
        loading: () => const _MapPlaceholderLoading(),
        error: (_, __) => _MapError(onRetry: _refresh),
      ),
    );
  }
}

// ─── Map Body ─────────────────────────────────────────────────────────────────
class _RouteMapBody extends StatelessWidget {
  const _RouteMapBody({
    required this.route,
    required this.isWorking,
    required this.mapController,
    required this.sheetController,
    required this.fakeCoords,
    required this.onStart,
    required this.onArrive,
    required this.onRefresh,
  });

  final DriverRoute? route;
  final bool isWorking;
  final MapController mapController;
  final DraggableScrollableController sheetController;
  final List<LatLng> fakeCoords;
  final VoidCallback? onStart;
  final ValueChanged<String>? onArrive;
  final VoidCallback onRefresh;

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        // ── Full-screen OSM Map ──────────────────────────────────────────────
        FlutterMap(
          mapController: mapController,
          options: MapOptions(
            initialCenter: _kDefaultCenter,
            initialZoom: 13,
            interactionOptions: const InteractionOptions(
              flags: InteractiveFlag.pinchZoom | InteractiveFlag.drag,
            ),
          ),
          children: [
            TileLayer(
              urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
              userAgentPackageName: 'com.asm.driverapp',
            ),
            // Route polyline
            if (fakeCoords.length > 1)
              PolylineLayer(
                polylines: [
                  Polyline(
                    points: fakeCoords,
                    color: AppColors.accent,
                    strokeWidth: 3.5,
                    pattern: const StrokePattern.dotted(),
                  ),
                ],
              ),
            // Stop markers
            MarkerLayer(
              markers: List.generate(fakeCoords.length, (i) {
                final stop = route?.stops[i];
                final isDone = stop != null && stop.status != DriverRouteStopStatus.pending;
                final isNext = stop != null &&
                    !isDone &&
                    (route?.stops.firstWhere((s) => s.status == DriverRouteStopStatus.pending,
                            orElse: () => stop) ==
                        stop);
                return Marker(
                  point: fakeCoords[i],
                  width: isNext ? 46 : 36,
                  height: isNext ? 46 : 36,
                  child: _StopMarker(
                    order: (stop?.stopOrder ?? i + 1),
                    isDone: isDone,
                    isNext: isNext,
                  ),
                );
              }),
            ),
          ],
        ),

        // ── Top bar ─────────────────────────────────────────────────────────
        Positioned(
          top: 0,
          left: 0,
          right: 0,
          child: _MapTopBar(
            route: route,
            onRefresh: onRefresh,
          ),
        ),

        // ── Bottom sheet ────────────────────────────────────────────────────
        DraggableScrollableSheet(
          controller: sheetController,
          initialChildSize: 0.35,
          minChildSize: 0.12,
          maxChildSize: 0.75,
          snap: true,
          snapSizes: const [0.12, 0.35, 0.75],
          builder: (context, scrollController) => _BottomSheet(
            scrollController: scrollController,
            route: route,
            isWorking: isWorking,
            onStart: onStart,
            onArrive: onArrive,
          ),
        ),
      ],
    );
  }
}

// ─── Stop Marker ──────────────────────────────────────────────────────────────
class _StopMarker extends StatelessWidget {
  const _StopMarker({required this.order, required this.isDone, required this.isNext});
  final int order;
  final bool isDone;
  final bool isNext;

  @override
  Widget build(BuildContext context) {
    final Color bg;
    final Color fg;
    if (isDone) {
      bg = AppColors.success;
      fg = Colors.white;
    } else if (isNext) {
      bg = AppColors.accent;
      fg = Colors.white;
    } else {
      bg = AppColors.surface;
      fg = AppColors.textPrimary;
    }

    return Container(
      decoration: BoxDecoration(
        color: bg,
        shape: BoxShape.circle,
        border: Border.all(color: Colors.white, width: isNext ? 2.5 : 2),
        boxShadow: [
          BoxShadow(
            color: bg.withValues(alpha: 0.4),
            blurRadius: isNext ? 12 : 6,
            spreadRadius: isNext ? 2 : 0,
          ),
        ],
      ),
      child: isDone
          ? Icon(Icons.check_rounded, size: isNext ? 20 : 15, color: fg)
          : Center(
              child: Text(
                '$order',
                style: GoogleFonts.inter(
                  fontSize: isNext ? 14 : 12,
                  fontWeight: FontWeight.w800,
                  color: fg,
                ),
              ),
            ),
    );
  }
}

// ─── Map Top Bar ──────────────────────────────────────────────────────────────
class _MapTopBar extends StatelessWidget {
  const _MapTopBar({required this.route, required this.onRefresh});
  final DriverRoute? route;
  final VoidCallback onRefresh;

  @override
  Widget build(BuildContext context) {
    final today = DateTime.now();
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    final label = '${days[today.weekday - 1]}, ${months[today.month - 1]} ${today.day}';

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 0),
        child: Row(
          children: [
            // Route name pill
            Expanded(
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(12),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.08),
                      blurRadius: 12,
                      offset: const Offset(0, 2),
                    ),
                  ],
                ),
                child: Row(
                  children: [
                    const Icon(PhosphorIconsFill.path, size: 16, color: AppColors.accent),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        route?.name ?? label,
                        style: GoogleFonts.manrope(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.textPrimary),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (route != null) ...[
                      const SizedBox(width: 8),
                      _StatusPill(route!.status),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(width: 8),
            // Refresh
            GestureDetector(
              onTap: onRefresh,
              child: Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(12),
                  boxShadow: [
                    BoxShadow(color: Colors.black.withValues(alpha: 0.12), blurRadius: 16, offset: const Offset(0, 3)),
                  ],
                ),
                child: const Icon(PhosphorIconsBold.arrowsClockwise, size: 18, color: AppColors.textSecondary),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill(this.status);
  final DriverRouteStatus status;

  @override
  Widget build(BuildContext context) {
    final Color color;
    switch (status) {
      case DriverRouteStatus.draft:       color = AppColors.muted; break;
      case DriverRouteStatus.validated:   color = AppColors.info; break;
      case DriverRouteStatus.inProgress:  color = AppColors.accent; break;
      case DriverRouteStatus.closed:      color = AppColors.success; break;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        status.label.toUpperCase(),
        style: GoogleFonts.inter(fontSize: 9, fontWeight: FontWeight.w700, color: color, letterSpacing: 0.5),
      ),
    );
  }
}

// ─── Bottom Sheet ─────────────────────────────────────────────────────────────
class _BottomSheet extends StatelessWidget {
  const _BottomSheet({
    required this.scrollController,
    required this.route,
    required this.isWorking,
    required this.onStart,
    required this.onArrive,
  });

  final ScrollController scrollController;
  final DriverRoute? route;
  final bool isWorking;
  final VoidCallback? onStart;
  final ValueChanged<String>? onArrive;

  @override
  Widget build(BuildContext context) {
    final pendingStops = route?.stops
        .where((s) => s.status == DriverRouteStopStatus.pending)
        .toList() ??
      const [];
    final nextPendingStop = pendingStops.isNotEmpty ? pendingStops.first : null;

    return Container(
      decoration: const BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
        boxShadow: [
          BoxShadow(color: Color(0x1A0F172A), blurRadius: 24, offset: Offset(0, -4)),
        ],
      ),
      child: ListView(
        controller: scrollController,
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 40),
        children: [
          // Handle
          Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(color: AppColors.surfaceDim, borderRadius: BorderRadius.circular(2)),
              ),
            ),
          ),

          if (route == null) ...[
            // No route
            const SizedBox(height: 12),
            Center(
              child: Column(
                children: [
                  Container(
                    width: 56,
                    height: 56,
                    decoration: BoxDecoration(
                      color: AppColors.surfaceElevated,
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: AppColors.border),
                    ),
                    child: const Icon(PhosphorIconsRegular.path, size: 26, color: AppColors.muted),
                  ),
                  const SizedBox(height: 12),
                  Text('No route assigned', style: GoogleFonts.inter(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.textPrimary)),
                  const SizedBox(height: 4),
                  Text('Your ops team will assign a route once it is validated.',
                      style: const TextStyle(fontSize: 13, color: AppColors.muted, height: 1.4),
                      textAlign: TextAlign.center),
                ],
              ),
            ),
          ] else ...[
            // Route summary row
            _RouteSummaryBar(route: route!),
            const SizedBox(height: 16),

            // CTA button
            if (route!.status == DriverRouteStatus.validated)
              DriveButton(
                label: 'Start Route',
                icon: PhosphorIconsBold.play,
                variant: DriveButtonVariant.success,
                fullWidth: true,
                size: DriveButtonSize.lg,
                isLoading: isWorking,
                onPressed: isWorking ? null : onStart,
              ),

            if (route!.status == DriverRouteStatus.inProgress) ...[
              if (nextPendingStop != null)
                DriveButton(
                  label: 'Arrived at Stop ${nextPendingStop.stopOrder}',
                  icon: PhosphorIconsBold.flagPennant,
                  fullWidth: true,
                  size: DriveButtonSize.lg,
                  isLoading: isWorking,
                  onPressed: isWorking ? null : () => onArrive?.call(nextPendingStop.id),
                )
              else
                _InfoChip(label: 'All stops reached — awaiting closure from ops', color: AppColors.success),
            ],

            if (route!.status == DriverRouteStatus.closed)
              _InfoChip(label: 'Route closed for today', color: AppColors.muted),

            const SizedBox(height: 20),
            Row(
              children: [
                Text('Stop List', style: GoogleFonts.inter(fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.textPrimary)),
                const SizedBox(width: 8),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: AppColors.surfaceElevated,
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: AppColors.border),
                  ),
                  child: Text('${route!.stops.length}', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.textSecondary)),
                ),
              ],
            ),
            const SizedBox(height: 12),
            if (route!.stops.isEmpty)
              Text('No stops configured.', style: const TextStyle(color: AppColors.muted))
            else
              ...route!.stops.map((stop) => Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: _StopListItem(stop: stop),
                  )),
          ],
        ],
      ),
    );
  }
}

class _RouteSummaryBar extends StatelessWidget {
  const _RouteSummaryBar({required this.route});
  final DriverRoute route;

  @override
  Widget build(BuildContext context) {
    final total = route.stops.length;
    final done = route.stops.where((s) => s.status != DriverRouteStopStatus.pending).length;
    final progress = total == 0 ? 0.0 : done / total;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                route.name,
                style: GoogleFonts.inter(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.textPrimary, letterSpacing: -0.3),
              ),
            ),
            Text(
              '$done/$total',
              style: GoogleFonts.inter(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.accent),
            ),
          ],
        ),
        if (route.zone != null && route.zone!.isNotEmpty) ...[
          const SizedBox(height: 3),
          Row(children: [
            const Icon(Icons.map_outlined, size: 12, color: AppColors.muted),
            const SizedBox(width: 4),
            Text(route.zone!, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
          ]),
        ],
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              child: ClipRRect(
                borderRadius: BorderRadius.circular(3),
                child: LinearProgressIndicator(
                  value: progress,
                  minHeight: 5,
                  backgroundColor: AppColors.surfaceDim,
                  valueColor: const AlwaysStoppedAnimation(AppColors.accent),
                ),
              ),
            ),
            const SizedBox(width: 10),
            Text(
              '${(progress * 100).toStringAsFixed(0)}%',
              style: GoogleFonts.inter(fontSize: 11, fontWeight: FontWeight.w700, color: AppColors.accent),
            ),
          ],
        ),
      ],
    );
  }
}

class _StopListItem extends StatelessWidget {
  const _StopListItem({required this.stop});
  final DriverRouteStop stop;

  @override
  Widget build(BuildContext context) {
    final Color statusColor;
    final IconData statusIcon;
    switch (stop.status) {
      case DriverRouteStopStatus.pending:
        statusColor = AppColors.muted;
        statusIcon = Icons.radio_button_unchecked_rounded;
        break;
      case DriverRouteStopStatus.arrived:
        statusColor = AppColors.accent;
        statusIcon = Icons.place_rounded;
        break;
      case DriverRouteStopStatus.completed:
        statusColor = AppColors.success;
        statusIcon = Icons.check_circle_rounded;
        break;
      case DriverRouteStopStatus.failed:
        statusColor = AppColors.danger;
        statusIcon = Icons.cancel_rounded;
        break;
      case DriverRouteStopStatus.partial:
        statusColor = AppColors.warning;
        statusIcon = Icons.remove_circle_rounded;
        break;
    }

    final isDone = stop.status != DriverRouteStopStatus.pending;

    return GestureDetector(
      onTap: () => Navigator.of(context).pushNamed(
        DeliveryDetailScreen.routeName,
        arguments: DeliveryDetailArgs(deliveryId: stop.deliveryId),
      ),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: isDone ? statusColor.withValues(alpha: 0.05) : AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isDone ? statusColor.withValues(alpha: 0.2) : AppColors.border,
          ),
        ),
        child: Row(
          children: [
            // Order bubble
            Container(
              width: 30,
              height: 30,
              decoration: BoxDecoration(
                color: isDone ? statusColor.withValues(alpha: 0.12) : AppColors.surfaceElevated,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Center(
                child: Text(
                  '${stop.stopOrder}',
                  style: GoogleFonts.manrope(fontSize: 13, fontWeight: FontWeight.w800, color: isDone ? statusColor : AppColors.textSecondary),
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Stop ${stop.stopOrder}',
                    style: GoogleFonts.inter(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: isDone ? AppColors.textSecondary : AppColors.textPrimary,
                    ),
                  ),
                  Text(
                    stop.deliveryId,
                    style: const TextStyle(fontSize: 11, color: AppColors.muted),
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Icon(statusIcon, size: 18, color: statusColor),
          ],
        ),
      ),
    );
  }
}

class _InfoChip extends StatelessWidget {
  const _InfoChip({required this.label, required this.color});
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: color.withValues(alpha: 0.2)),
        ),
        child: Text(label, style: GoogleFonts.inter(fontSize: 13, color: color, fontWeight: FontWeight.w500)),
      );
}

// ─── Loading placeholder ──────────────────────────────────────────────────────
class _MapPlaceholderLoading extends StatelessWidget {
  const _MapPlaceholderLoading();

  @override
  Widget build(BuildContext context) => const Scaffold(
        backgroundColor: AppColors.surfaceElevated,
        body: LoadingState(message: 'Loading route...'),
      );
}

class _MapError extends StatelessWidget {
  const _MapError({required this.onRetry});
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Scaffold(
        backgroundColor: AppColors.background,
        body: EmptyState(
          icon: PhosphorIconsRegular.cloudSlash,
          title: 'Could not load route',
          action: onRetry,
          actionLabel: 'Retry',
        ),
      );
}
