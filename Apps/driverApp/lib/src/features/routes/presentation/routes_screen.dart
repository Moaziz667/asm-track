import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:latlong2/latlong.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';
import '../../deliveries/models/delivery_models.dart';
import '../../deliveries/presentation/delivery_detail_screen.dart';
import '../../deliveries/presentation/handoff_scanner_screen.dart';
import '../models/route_models.dart';
import 'package:lucide_icons/lucide_icons.dart';
import '../../../services/offline_queue_service.dart';

// Default map center — Tunisia (Tunis)
const _kDefaultCenter = LatLng(36.8065, 10.1815);

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

  Future<void> _downloadPdf(String routeId) => _doAction(() async {
        // Offline check
        final isOnline = await ref.read(connectivityServiceProvider).isOnline;
        if (!isOnline) {
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Non disponible hors ligne')),
            );
          }
          return;
        }
        final ok = await ref.read(pdfServiceProvider).downloadAndOpen(
          '/api/driver/routes/$routeId/pdf',
          fileName: 'route-$routeId.pdf',
        );
        if (!ok && mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Échec du téléchargement du PDF')),
          );
        }
      });

  Future<void> _openMaps(List<DriverRouteStop> stops) async {
    final pinned = stops.where((s) => s.hasPinned).toList();
    if (pinned.isEmpty) return;
    final dest = pinned.last;
    final waypoints = pinned.length > 1
        ? pinned.sublist(0, pinned.length - 1).map((s) => '${s.lat},${s.lng}').join('|')
        : null;
    final buffer = StringBuffer(
      'https://www.google.com/maps/dir/?api=1&travelmode=driving'
      '&destination=${dest.lat},${dest.lng}',
    );
    if (waypoints != null) buffer.write('&waypoints=$waypoints');
    final uri = Uri.parse(buffer.toString());
    try {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      // Fallback to geo: URI for devices without Google Maps
      final geoUri = Uri.parse('geo:${dest.lat},${dest.lng}?q=${dest.lat},${dest.lng}');
      try {
        await launchUrl(geoUri, mode: LaunchMode.externalApplication);
      } catch (_) {}
    }
  }

  Future<void> _startRoute(String id) => _doAction(() async {
        final pt = await LocationService().currentPosition();
        if (pt != null) {
          await ref.read(profileRepositoryProvider).updateLocation(pt.lat, pt.lng);
        }

        // Offline check
        final isOnline = await ref.read(connectivityServiceProvider).isOnline;
        if (!isOnline) {
          ref.read(offlineQueueProvider.notifier).enqueueRequest(
            path: '/api/driver/routes/$id/start',
            method: 'POST',
          );
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Hors ligne \u2014 sera envoy\u00e9 \u00e0 la reconnexion')),
            );
          }
          return;
        }

        await ref.read(routeRepositoryProvider).start(id);
      });

  Future<void> _arriveStop(String routeId, String stopId) => _doAction(() async {
        final pt = await LocationService().currentPosition();
        if (pt != null) {
          await ref.read(profileRepositoryProvider).updateLocation(pt.lat, pt.lng);
        }

        // Offline check
        final isOnline = await ref.read(connectivityServiceProvider).isOnline;
        if (!isOnline) {
          ref.read(offlineQueueProvider.notifier).enqueueRequest(
            path: '/api/driver/routes/$routeId/stops/$stopId/arrive',
            method: 'POST',
          );
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Hors ligne \u2014 sera envoy\u00e9 \u00e0 la reconnexion')),
            );
          }
          return;
        }

        await ref.read(routeRepositoryProvider).arrive(routeId, stopId);
      });

  Future<void> _startTransit(String deliveryId) => _doAction(() async {
        final pt = await LocationService().currentPosition();

        // Offline check
        final isOnline = await ref.read(connectivityServiceProvider).isOnline;
        if (!isOnline) {
          ref.read(offlineQueueProvider.notifier).enqueueRequest(
            path: '/api/driver/deliveries/$deliveryId/transit',
            method: 'POST',
            data: pt != null ? {'lat': pt.lat, 'lng': pt.lng} : null,
          );
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Hors ligne \u2014 sera envoy\u00e9 \u00e0 la reconnexion')),
            );
          }
          return;
        }

        await ref.read(deliveryRepositoryProvider).startTransit(
          deliveryId,
          lat: pt?.lat,
          lng: pt?.lng,
        );
      });

  void _openPod(BuildContext context, String deliveryId) {
    Navigator.of(context).pushNamed(
      DeliveryDetailScreen.routeName,
      arguments: DeliveryDetailArgs(deliveryId: deliveryId),
    );
  }

  void _openDetails(BuildContext context, String deliveryId) {
    Navigator.of(context).pushNamed(
      DeliveryDetailScreen.routeName,
      arguments: DeliveryDetailArgs(deliveryId: deliveryId),
    );
  }

  @override
  Widget build(BuildContext context) {
    final routeAsync = ref.watch(todayRouteProvider);
    final deliveries = ref.watch(activeDeliveriesProvider).value ?? [];
    final hasPendingHandoff = deliveries.any(
      (d) => d.requiresHandoff && d.handoffConfirmedAt == null,
    );

    return Scaffold(
      backgroundColor: AppColors.background,
      body: routeAsync.when(
        data: (route) => _RouteMapBody(
          route: route,
          isWorking: _isWorking,
          mapController: _mapController,
          sheetController: _sheetController,
          onStart: route == null ? null : () => _startRoute(route.id),
          onArrive: route == null ? null : (stopId) => _arriveStop(route.id, stopId),
          onStartTransit: _startTransit,
          onOpenPod: (deliveryId) => _openPod(context, deliveryId),
          onOpenDetails: (deliveryId) => _openDetails(context, deliveryId),
          onRefresh: _refresh,
          onDownloadPdf: route == null ? null : () => _downloadPdf(route.id),
          onNavigate: route == null ? null : () => _openMaps(route.stops),
        ),
        loading: () => const _MapPlaceholderLoading(),
        error: (_, __) => _MapError(onRetry: _refresh),
      ),
      floatingActionButton: hasPendingHandoff
          ? FloatingActionButton.extended(
              onPressed: () async {
                // Offline check — scanner requires server validation
                final isOnline = await ref.read(connectivityServiceProvider).isOnline;
                if (!isOnline) {
                  if (mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Scanner non disponible hors ligne')),
                    );
                  }
                  return;
                }
                final result = await Navigator.of(context).push<bool>(
                  MaterialPageRoute(builder: (_) => const HandoffScannerScreen()),
                );
                if (result == true) _refresh();
              },
              backgroundColor: AppColors.neonYellow,
              foregroundColor: Colors.black,
              elevation: 0,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
              icon: const Icon(LucideIcons.qrCode, size: 20),
              label: Text('SCANNER', style: GoogleFonts.spaceGrotesk(fontWeight: FontWeight.w900, letterSpacing: 1.5, fontSize: 13)),
            )
          : null,
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
    required this.onStart,
    required this.onArrive,
    required this.onStartTransit,
    required this.onOpenPod,
    required this.onOpenDetails,
    required this.onRefresh,
    this.onDownloadPdf,
    this.onNavigate,
  });

  final DriverRoute? route;
  final bool isWorking;
  final MapController mapController;
  final DraggableScrollableController sheetController;
  final VoidCallback? onStart;
  final ValueChanged<String>? onArrive;
  final ValueChanged<String> onStartTransit;
  final ValueChanged<String> onOpenPod;
  final ValueChanged<String> onOpenDetails;
  final VoidCallback onRefresh;
  final VoidCallback? onDownloadPdf;
  final VoidCallback? onNavigate;

  @override
  Widget build(BuildContext context) {
    // Build real stop coords from pinned deliveries; filter out unpinned stops
    final pinnedStops = route?.stops.where((s) => s.hasPinned).toList() ?? [];
    final stopCoords = pinnedStops.map((s) => LatLng(s.lat!, s.lng!)).toList();

    // Map center: first pinned stop, or Tunisia default
    final mapCenter = stopCoords.isNotEmpty ? stopCoords.first : _kDefaultCenter;

    // Route polyline: prefer OSRM geometry, fallback to point-to-point
    final List<LatLng> polyPoints;
    if (route?.routeGeometry != null && route!.routeGeometry!.isNotEmpty) {
      polyPoints = DriverRoute.decodePolyline(route!.routeGeometry!);
    } else {
      polyPoints = stopCoords;
    }

    // Depot marker: first point of OSRM polyline (depot is always origin of optimization)
    final LatLng? depotCoord = polyPoints.isNotEmpty ? polyPoints.first : null;

    return Stack(
      children: [
        // ── Full-screen OSM Map ──────────────────────────────────────────────
        FlutterMap(
          mapController: mapController,
          options: MapOptions(
            initialCenter: mapCenter,
            initialZoom: stopCoords.length > 1 ? 12 : 13,
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
            if (polyPoints.length > 1)
              PolylineLayer(
                polylines: <Polyline>[
                  Polyline(
                    points: polyPoints,
                    strokeWidth: 4,
                    color: AppColors.neonYellow,
                    borderColor: Colors.black,
                    borderStrokeWidth: 1.5,
                  ),
                ],
              ),
            // Depot marker (start of OSRM polyline = depot origin)
            if (depotCoord != null)
              MarkerLayer(
                markers: [
                  Marker(
                    point: depotCoord,
                    width: 40,
                    height: 40,
                    child: const _DepotMarker(),
                  ),
                ],
              ),
            // Stop markers at real coordinates
            MarkerLayer(
              markers: pinnedStops.asMap().entries.map((entry) {
                final stop = entry.value;
                final coord = stopCoords[entry.key];
                final isDone = stop.status != DriverRouteStopStatus.pending;
                final isNext = !isDone &&
                    (route?.stops.firstWhere(
                          (s) => s.status == DriverRouteStopStatus.pending,
                          orElse: () => stop,
                        ) ==
                        stop);
                return Marker(
                  point: coord,
                  width: isNext ? 46 : 36,
                  height: isNext ? 46 : 36,
                  child: _StopMarker(
                    order: stop.stopOrder,
                    isDone: isDone,
                    isNext: isNext,
                  ),
                );
              }).toList(),
            ),
          ],
        ),

        // ── Top bar ─────────────────────────────────────────────────────────
        Positioned(
          top: 0,
          left: 0,
          right: 0,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _MapTopBar(route: route, onRefresh: onRefresh),
              if (route?.fromCache == true) const _OfflineBanner(),
            ],
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
            onStartTransit: onStartTransit,
            onOpenPod: onOpenPod,
            onOpenDetails: onOpenDetails,
            onDownloadPdf: onDownloadPdf,
            onNavigate: onNavigate,
          ),
        ),
      ],
    );
  }
}

// ─── Depot Marker ─────────────────────────────────────────────────────────────
class _DepotMarker extends StatelessWidget {
  const _DepotMarker();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 34,
      height: 34,
      decoration: BoxDecoration(
        color: AppColors.background,
        borderRadius: BorderRadius.circular(2),
        border: Border.all(color: AppColors.neonYellow, width: 2),
        boxShadow: [
          BoxShadow(color: AppColors.neonYellow.withValues(alpha: 0.4), blurRadius: 8, spreadRadius: 1),
        ],
      ),
      child: Center(
        child: Text(
          'D',
          style: GoogleFonts.spaceGrotesk(fontSize: 14, fontWeight: FontWeight.w900, color: AppColors.neonYellow),
        ),
      ),
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
    final double size = isNext ? 32 : 28;
    
    if (isDone) {
      bg = AppColors.success;
      fg = Colors.black;
    } else if (isNext) {
      bg = AppColors.neonYellow;
      fg = Colors.black;
    } else {
      bg = AppColors.surfaceElevated;
      fg = AppColors.textPrimary;
    }

    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(2), // Sharp tactical corner
        border: Border.all(color: Colors.black, width: 1.5),
        boxShadow: isNext ? [
          BoxShadow(
            color: bg.withValues(alpha: 0.6),
            blurRadius: 10,
            spreadRadius: 2,
          ),
        ] : null,
      ),
      child: isDone
          ? Icon(LucideIcons.check, size: isNext ? 18 : 14, color: fg)
          : Center(
              child: Text(
                '$order',
                style: GoogleFonts.spaceGrotesk(
                  fontSize: isNext ? 14 : 12,
                  fontWeight: FontWeight.w900,
                  color: fg,
                ),
              ),
            ),
    );
  }
}

// ─── Map Top Bar ──────────────────────────────────────────────────────────────
class _MapTopBar extends ConsumerWidget {
  const _MapTopBar({required this.route, required this.onRefresh});
  final DriverRoute? route;
  final VoidCallback onRefresh;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final today = DateTime.now();
    final pendingSync = ref.watch(offlineQueueProvider);
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
                  color: AppColors.background,
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(color: AppColors.border),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.5),
                      blurRadius: 10,
                    ),
                  ],
                ),
                child: Row(
                  children: [
                    const Icon(LucideIcons.navigation, size: 14, color: AppColors.neonYellow),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        route?.name ?? label.toUpperCase(),
                        style: GoogleFonts.spaceGrotesk(fontSize: 12, fontWeight: FontWeight.w800, color: AppColors.textPrimary, letterSpacing: 0.5),
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
            // Sync status
            if (pendingSync > 0)
              GestureDetector(
                onTap: () => ref.read(offlineQueueProvider.notifier).processQueue(),
                child: Container(
                  height: 40,
                  padding: const EdgeInsets.symmetric(horizontal: 10),
                  decoration: BoxDecoration(
                    color: AppColors.background,
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(color: AppColors.warning.withValues(alpha: 0.5)),
                  ),
                  child: Row(
                    children: [
                      const Icon(LucideIcons.refreshCw, size: 14, color: AppColors.warning),
                      const SizedBox(width: 6),
                      Text(
                        '$pendingSync',
                        style: GoogleFonts.outfit(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.warning),
                      ),
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
                  color: AppColors.background,
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(color: AppColors.border),
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
      case DriverRouteStatus.cancelled:   color = AppColors.danger; break;
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
    required this.onStartTransit,
    required this.onOpenPod,
    required this.onOpenDetails,
    this.onDownloadPdf,
    this.onNavigate,
  });

  final ScrollController scrollController;
  final DriverRoute? route;
  final bool isWorking;
  final VoidCallback? onStart;
  final ValueChanged<String>? onArrive;
  final ValueChanged<String> onStartTransit;
  final ValueChanged<String> onOpenPod;
  final ValueChanged<String> onOpenDetails;
  final VoidCallback? onDownloadPdf;
  final VoidCallback? onNavigate;

  @override
  Widget build(BuildContext context) {
    final pendingStops = route?.stops
        .where((s) => s.status == DriverRouteStopStatus.pending)
        .toList() ??
      const [];
    final nextPendingStop = pendingStops.isNotEmpty ? pendingStops.first : null;

    return Container(
      decoration: const BoxDecoration(
        color: AppColors.background,
        borderRadius: BorderRadius.vertical(top: Radius.circular(4)), // Tactical 4px
        boxShadow: [
          BoxShadow(color: Colors.black, blurRadius: 40, spreadRadius: 10),
        ],
        border: Border(top: BorderSide(color: AppColors.border, width: 1.5)),
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
                width: 40,
                height: 2,
                decoration: BoxDecoration(color: AppColors.border, borderRadius: BorderRadius.circular(1)),
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
                      color: AppColors.surface,
                      borderRadius: BorderRadius.circular(4),
                      border: Border.all(color: AppColors.border),
                    ),
                    child: const Icon(LucideIcons.ban, size: 26, color: AppColors.muted),
                  ),
                  const SizedBox(height: 16),
                  Text('AUCUNE MISSION ASSIGNÉE', style: GoogleFonts.spaceGrotesk(fontSize: 14, fontWeight: FontWeight.w800, color: AppColors.textPrimary, letterSpacing: 1)),
                  const SizedBox(height: 8),
                  Text('EN ATTENTE DES INSTRUCTIONS DU DISPATCH',
                      style: GoogleFonts.spaceGrotesk(fontSize: 11, color: AppColors.muted, fontWeight: FontWeight.w700),
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
                label: 'Démarrer la tournée',
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
                  label: 'Arrivé à l\'arrêt ${nextPendingStop.stopOrder}',
                  icon: PhosphorIconsBold.flagPennant,
                  fullWidth: true,
                  size: DriveButtonSize.lg,
                  isLoading: isWorking,
                  onPressed: isWorking ? null : () => onArrive?.call(nextPendingStop.id),
                )
              else
                _InfoChip(label: 'Tous les arrêts atteints — en attente de clôture par le dispatch', color: AppColors.success),
            ],

            // Secondary actions row (PDF + Maps)
            if (route!.status == DriverRouteStatus.validated ||
                route!.status == DriverRouteStatus.inProgress) ...[
              const SizedBox(height: 10),
              Row(
                children: [
                  if (route!.status == DriverRouteStatus.validated) ...[
                    Expanded(
                      child: _ActionButton(
                        label: 'Télécharger PDF',
                        icon: LucideIcons.fileDown,
                        color: AppColors.info,
                        onTap: () => onDownloadPdf?.call(),
                      ),
                    ),
                    const SizedBox(width: 8),
                  ],
                  Expanded(
                    child: _ActionButton(
                      label: 'Naviguer',
                      icon: LucideIcons.navigation2,
                      color: AppColors.neonYellow,
                      onTap: () => onNavigate?.call(),
                    ),
                  ),
                ],
              ),
            ],

            if (route!.status == DriverRouteStatus.closed)
              _InfoChip(label: 'Tournée clôturée pour aujourd\'hui', color: AppColors.muted),

            const SizedBox(height: 20),
            Row(
              children: [
                Text('JOURNAL DES ARRÊTS', style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.muted, letterSpacing: 1.5)),
                const SizedBox(width: 12),
                Expanded(child: Container(height: 1, color: AppColors.border)),
                const SizedBox(width: 12),
                Text('${route!.stops.length}', style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.neonYellow)),
              ],
            ),
            const SizedBox(height: 12),
            if (route!.stops.isEmpty)
              Text('Aucun arrêt configuré.', style: const TextStyle(color: AppColors.muted))
            else
              ...route!.stops.map((stop) => Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: _StopListItem(
                      stop: stop,
                      onStartTransit: onStartTransit,
                      onOpenPod: onOpenPod,
                      onOpenDetails: onOpenDetails,
                    ),
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
                route.name.toUpperCase(),
                style: GoogleFonts.spaceGrotesk(fontSize: 14, fontWeight: FontWeight.w900, color: AppColors.textPrimary, letterSpacing: 0.5),
              ),
            ),
            Text(
              '$done / $total',
              style: GoogleFonts.spaceGrotesk(fontSize: 14, fontWeight: FontWeight.w900, color: AppColors.neonYellow),
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
                  minHeight: 3,
                  backgroundColor: AppColors.surface,
                  valueColor: const AlwaysStoppedAnimation(AppColors.neonYellow),
                ),
              ),
            ),
            const SizedBox(width: 10),
            Text(
              '${(progress * 100).toStringAsFixed(0)}%',
              style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.neonYellow),
            ),
          ],
        ),
      ],
    );
  }
}

class _StopListItem extends StatelessWidget {
  const _StopListItem({
    required this.stop,
    required this.onStartTransit,
    required this.onOpenPod,
    required this.onOpenDetails,
  });

  final DriverRouteStop stop;
  final ValueChanged<String> onStartTransit;
  final ValueChanged<String> onOpenPod;
  final ValueChanged<String> onOpenDetails;

  @override
  Widget build(BuildContext context) {
    final ds = stop.parsedDeliveryStatus;
    final Color borderColor;
    switch (ds) {
      case DeliveryStatus.inTransit:
        borderColor = AppColors.accent.withValues(alpha: 0.3);
        break;
      case DeliveryStatus.delivered:
      case DeliveryStatus.partially_delivered:
        borderColor = AppColors.success.withValues(alpha: 0.25);
        break;
      case DeliveryStatus.failed:
      case DeliveryStatus.cancelled:
        borderColor = AppColors.danger.withValues(alpha: 0.25);
        break;
      default:
        borderColor = AppColors.border;
    }

    return GestureDetector(
      onTap: () => onOpenDetails(stop.deliveryId),
      behavior: HitTestBehavior.opaque,
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(4),
          border: Border.all(color: borderColor, width: ds == DeliveryStatus.inTransit ? 1.5 : 1),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
          // Row 1: stop bubble + client name + status badge
          Row(
            children: [
              Container(
                width: 30,
                height: 30,
                decoration: BoxDecoration(
                  color: AppColors.surfaceElevated,
                  borderRadius: BorderRadius.circular(2),
                ),
                child: Center(
                  child: Text(
                    '${stop.stopOrder}',
                    style: GoogleFonts.spaceGrotesk(fontSize: 13, fontWeight: FontWeight.w900, color: AppColors.textPrimary),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  (stop.clientName ?? 'CLIENT').toUpperCase(),
                  style: GoogleFonts.spaceGrotesk(fontSize: 13, fontWeight: FontWeight.w800, color: AppColors.textPrimary, letterSpacing: 0.5),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              const SizedBox(width: 8),
              _DeliveryStatusBadge(ds),
            ],
          ),

          // Row 2: address
          if (stop.address != null || stop.city != null) ...[
            const SizedBox(height: 6),
            Row(
              children: [
                const Icon(Icons.location_on_outlined, size: 12, color: AppColors.muted),
                const SizedBox(width: 4),
                Expanded(
                  child: Text(
                    [stop.address, stop.city].where((e) => e != null && e.isNotEmpty).join(', '),
                    style: const TextStyle(fontSize: 11, color: AppColors.muted),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ),
          ],

          // Row 3: orderRef + totalAmount
          if (stop.orderRef != null || stop.totalAmount != null) ...[
            const SizedBox(height: 4),
            Row(
              children: [
                if (stop.orderRef != null)
                  Text(
                    stop.orderRef!,
                    style: GoogleFonts.inter(fontSize: 10, color: AppColors.textSecondary, fontWeight: FontWeight.w600),
                  ),
                if (stop.orderRef != null && stop.totalAmount != null)
                  const Text('  ·  ', style: TextStyle(fontSize: 11, color: AppColors.muted)),
                if (stop.totalAmount != null)
                  Text(
                    '${stop.totalAmount!.toStringAsFixed(3)} TND',
                    style: GoogleFonts.inter(fontSize: 11, fontWeight: FontWeight.w700, color: AppColors.textPrimary),
                  ),
              ],
            ),
          ],

          // Row 4: ETA + SLA
          if (stop.formattedEta != null || stop.formattedSla != null) ...[
            const SizedBox(height: 4),
            Row(
              children: [
                if (stop.formattedEta != null) ...[
                  const Icon(Icons.schedule_rounded, size: 11, color: AppColors.info),
                  const SizedBox(width: 3),
                  Text('ETA ${stop.formattedEta}', style: const TextStyle(fontSize: 10, color: AppColors.info, fontWeight: FontWeight.w600)),
                ],
                if (stop.formattedEta != null && stop.formattedSla != null)
                  const Text('   ', style: TextStyle(fontSize: 10)),
                if (stop.formattedSla != null) ...[
                  const Icon(Icons.flag_outlined, size: 11, color: AppColors.warning),
                  const SizedBox(width: 3),
                  Text('SLA ${stop.formattedSla}', style: const TextStyle(fontSize: 10, color: AppColors.warning, fontWeight: FontWeight.w600)),
                ],
              ],
            ),
          ],

          ],
        ),
      ),
    );
  }
}

class _DeliveryStatusBadge extends StatelessWidget {
  const _DeliveryStatusBadge(this.status);
  final DeliveryStatus status;

  @override
  Widget build(BuildContext context) {
    final Color color;
    final String label;
    switch (status) {
      case DeliveryStatus.pickedUp:
        color = AppColors.info; label = 'Chargé'; break;
      case DeliveryStatus.inTransit:
        color = AppColors.accent; label = 'En transit'; break;
      case DeliveryStatus.delivered:
        color = AppColors.success; label = 'Livré'; break;
      case DeliveryStatus.partially_delivered:
        color = AppColors.warning; label = 'Partiel'; break;
      case DeliveryStatus.failed:
        color = AppColors.danger; label = 'Échoué'; break;
      case DeliveryStatus.cancelled:
        color = AppColors.muted; label = 'Annulé'; break;
      default:
        color = AppColors.muted; label = status.label; break;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(2),
        border: Border.all(color: color.withValues(alpha: 0.3), width: 0.5),
      ),
      child: Text(label.toUpperCase(), style: GoogleFonts.spaceGrotesk(fontSize: 9, fontWeight: FontWeight.w900, color: color, letterSpacing: 1)),
    );
  }
}

class _ActionButton extends StatelessWidget {
  const _ActionButton({required this.label, required this.icon, required this.color, required this.onTap});
  final String label;
  final IconData icon;
  final Color color;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.08),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: color.withValues(alpha: 0.2)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, size: 13, color: color),
              const SizedBox(width: 5),
              Text(label, style: GoogleFonts.inter(fontSize: 11, fontWeight: FontWeight.w700, color: color)),
            ],
          ),
        ),
      );
}

class _OfflineBanner extends StatelessWidget {
  const _OfflineBanner();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
      color: AppColors.warning.withValues(alpha: 0.92),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.cloud_off_rounded, size: 13, color: Colors.white),
          const SizedBox(width: 6),
          Text(
            'Données en cache — reconnectez-vous pour actualiser',
            style: GoogleFonts.inter(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: Colors.white,
            ),
          ),
        ],
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
        body: LoadingState(message: 'Chargement de la tournée...'),
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
          title: 'Impossible de charger la tournée',
          action: onRetry,
          actionLabel: 'Réessayer',
        ),
      );
}
