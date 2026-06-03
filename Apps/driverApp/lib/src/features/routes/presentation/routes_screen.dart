import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:latlong2/latlong.dart' hide Path;
import 'package:phosphor_flutter/phosphor_flutter.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../../theme/widgets.dart';
import '../../deliveries/models/delivery_models.dart';
import '../../deliveries/presentation/delivery_detail_screen.dart';
import '../../deliveries/presentation/handoff_scanner_screen.dart';
import '../../deliveries/presentation/handoff_token_sheet.dart';
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
    ref.invalidate(deliveryDetailProvider);
  }

  Future<void> _doAction(Future<void> Function() fn) async {
    if (_isWorking) return;
    setState(() => _isWorking = true);
    try {
      await fn();
      await _refresh();
    } catch (e) {
      if (e == 'OFFLINE_QUEUED') {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Hors ligne — sera envoyé à la reconnexion')),
          );
        }
      } else {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Erreur: $e')),
          );
        }
      }
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

        await ref.read(routeRepositoryProvider).start(id);
      });

  Future<void> _confirmPickup(String routeId, String stopId) => _doAction(() async {
        final pt = await LocationService().currentPosition();
        if (pt != null) {
          await ref.read(profileRepositoryProvider).updateLocation(pt.lat, pt.lng);
        }

        await ref.read(routeRepositoryProvider).confirmPickup(routeId, stopId);
      });

  Future<void> _startTransit(String deliveryId) => _doAction(() async {
        final pt = await LocationService().currentPosition();

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
    final currentDriverId = ref.watch(driverProfileProvider).value?.id;

    final pendingHandoffs = deliveries
        .where((d) => d.requiresHandoff && d.handoffConfirmedAt == null)
        .toList();

    // Determine this driver's role in the pending handoff
    final senderDelivery = currentDriverId != null
        ? pendingHandoffs.where((d) => d.handoffFromDriverId == currentDriverId).firstOrNull
        : null;
    final isReceiver = currentDriverId != null &&
        pendingHandoffs.any((d) => d.handoffToDriverId == currentDriverId);

    Widget? fab;
    if (senderDelivery != null) {
      // Driver 1 (sender): show QR code for Driver 2 to scan
      fab = FloatingActionButton.extended(
        onPressed: () async {
          await showModalBottomSheet(
            context: context,
            backgroundColor: Colors.transparent,
            isScrollControlled: true,
            builder: (_) => HandoffTokenSheet(deliveryId: senderDelivery.id),
          );
          _refresh();
        },
        backgroundColor: Theme.of(context).colorScheme.primary,
        foregroundColor: Colors.black,
        elevation: 0,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
        icon: const Icon(LucideIcons.qrCode, size: 20),
        label: Text('Generer QR', style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 1.5, fontSize: 13)),
      );
    } else if (isReceiver) {
      // Driver 2 (receiver): scan Driver 1's QR
      fab = FloatingActionButton.extended(
        onPressed: () async {
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
        backgroundColor: Theme.of(context).colorScheme.primary,
        foregroundColor: Colors.black,
        elevation: 0,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
        icon: const Icon(LucideIcons.qrCode, size: 20),
        label: Text('Scanner QR', style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 1.5, fontSize: 13)),
      );
    }

    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surface,
      body: routeAsync.when(
        data: (route) => _RouteMapBody(
          route: route,
          isWorking: _isWorking,
          mapController: _mapController,
          sheetController: _sheetController,
          onStart: route == null ? null : () => _startRoute(route.id),
          onConfirmPickup: route == null ? null : (stopId) => _confirmPickup(route.id, stopId),
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
      floatingActionButton: fab,
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
    required this.onConfirmPickup,
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
  final ValueChanged<String>? onConfirmPickup;
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
                    color: Theme.of(context).colorScheme.primary,
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
                    child: GestureDetector(
                      onTap: () => mapController.move(depotCoord, 15.5),
                      child: const _DepotMarker(),
                    ),
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
                  width: isNext ? 32 : 28,
                  height: isNext ? 42 : 36,
                  alignment: Alignment.bottomCenter,
                  child: GestureDetector(
                    onTap: () {
                      mapController.move(coord, 15.5);
                    },
                    child: _StopMarker(
                      order: stop.stopOrder,
                      isDone: isDone,
                      isNext: isNext,
                      etaAt: stop.etaAt,
                    ),
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
              _MapSearchBar(mapController: mapController),
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
            onConfirmPickup: onConfirmPickup,
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

// ─── Map Search Bar ───────────────────────────────────────────────────────────
class _MapSearchBar extends StatefulWidget {
  const _MapSearchBar({required this.mapController});
  final MapController mapController;

  @override
  State<_MapSearchBar> createState() => _MapSearchBarState();
}

class _MapSearchBarState extends State<_MapSearchBar> {
  final _controller = TextEditingController();
  final _focus = FocusNode();
  List<Map<String, dynamic>> _results = [];
  Timer? _debounce;
  bool _loading = false;

  @override
  void dispose() {
    _controller.dispose();
    _focus.dispose();
    _debounce?.cancel();
    super.dispose();
  }

  Future<void> _search(String q) async {
    _debounce?.cancel();
    if (q.trim().length < 3) {
      setState(() { _results = []; });
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 350), () async {
      setState(() => _loading = true);
      try {
        final client = HttpClient();
        final uri = Uri.https('nominatim.openstreetmap.org', '/search', {
          'format': 'json', 'q': q, 'limit': '5', 'accept-language': 'fr',
        });
        final req = await client.getUrl(uri);
        req.headers.set('User-Agent', 'ASMTrack/1.0');
        final res = await req.close();
        final body = await res.transform(utf8.decoder).join();
        final data = (jsonDecode(body) as List).cast<Map<String, dynamic>>();
        if (mounted) setState(() { _results = data; _loading = false; });
      } catch (_) {
        if (mounted) setState(() => _loading = false);
      }
    });
  }

  void _pick(Map<String, dynamic> r) {
    final lat = double.tryParse(r['lat'] as String? ?? '') ?? 0;
    final lon = double.tryParse(r['lon'] as String? ?? '') ?? 0;
    widget.mapController.move(LatLng(lat, lon), 15);
    final name = (r['display_name'] as String).split(',').first;
    _controller.text = name;
    _focus.unfocus();
    setState(() => _results = []);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final shadow = [BoxShadow(color: Colors.black.withValues(alpha: 0.25), blurRadius: 6, offset: const Offset(0, 2))];
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            decoration: BoxDecoration(
              color: cs.surface,
              borderRadius: BorderRadius.circular(8),
              boxShadow: shadow,
            ),
            child: TextField(
              controller: _controller,
              focusNode: _focus,
              onChanged: _search,
              style: TextStyle(fontSize: 13, color: cs.onSurface),
              decoration: InputDecoration(
                hintText: 'Rechercher un lieu…',
                hintStyle: TextStyle(fontSize: 13, color: cs.onSurfaceVariant),
                prefixIcon: Icon(Icons.search, size: 18, color: cs.onSurfaceVariant),
                suffixIcon: _loading
                    ? Padding(padding: const EdgeInsets.all(12), child: SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2, color: cs.primary)))
                    : _controller.text.isNotEmpty
                        ? IconButton(icon: Icon(Icons.close, size: 16, color: cs.onSurfaceVariant), onPressed: () { _controller.clear(); setState(() => _results = []); })
                        : null,
                border: InputBorder.none,
                contentPadding: const EdgeInsets.symmetric(vertical: 10),
                isDense: true,
              ),
            ),
          ),
          if (_results.isNotEmpty)
            Container(
              margin: const EdgeInsets.only(top: 2),
              decoration: BoxDecoration(
                color: cs.surface,
                borderRadius: BorderRadius.circular(8),
                boxShadow: shadow,
              ),
              child: ListView.separated(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: _results.length,
                separatorBuilder: (_, __) => Divider(height: 1, color: cs.outlineVariant),
                itemBuilder: (_, i) {
                  final r = _results[i];
                  return InkWell(
                    onTap: () => _pick(r),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                      child: Text(r['display_name'] as String, style: TextStyle(fontSize: 12, color: cs.onSurface), maxLines: 2, overflow: TextOverflow.ellipsis),
                    ),
                  );
                },
              ),
            ),
        ],
      ),
    );
  }
}

// ─── Depot Marker ─────────────────────────────────────────────────────────────
class _DepotMarker extends StatelessWidget {
  const _DepotMarker();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 42,
      height: 42,
      decoration: BoxDecoration(
        color: const Color(0xFF111827), // Admin Dark
        shape: BoxShape.circle,
        border: Border.all(color: Colors.white.withValues(alpha: 0.4), width: 1.5),
        boxShadow: [
          BoxShadow(color: Colors.black.withValues(alpha: 0.4), blurRadius: 8, offset: const Offset(0, 3)),
        ],
      ),
      child: Center(
        child: CustomPaint(
          size: const Size(22, 20),
          painter: _WarehousePainter(),
        ),
      ),
    );
  }
}

class _WarehousePainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.white.withValues(alpha: 0.95)
      ..style = PaintingStyle.fill;

    // Roof
    final roof = Path()
      ..moveTo(size.width / 2, 0)
      ..lineTo(0, size.height * 0.45)
      ..lineTo(size.width, size.height * 0.45)
      ..close();
    canvas.drawPath(roof, paint);

    // Body
    final bodyRect = RRect.fromRectAndRadius(
      Rect.fromLTWH(size.width * 0.1, size.height * 0.45, size.width * 0.8, size.height * 0.55),
      const Radius.circular(1),
    );
    canvas.drawRRect(bodyRect, paint);

    // Door
    final doorPaint = Paint()..color = const Color(0xFF111827);
    final doorRect = RRect.fromRectAndRadius(
      Rect.fromLTWH(size.width * 0.38, size.height * 0.65, size.width * 0.24, size.height * 0.35),
      const Radius.circular(1),
    );
    canvas.drawRRect(doorRect, doorPaint);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

// ─── Stop Marker ──────────────────────────────────────────────────────────────
class _StopMarker extends StatelessWidget {
  const _StopMarker({required this.order, required this.isDone, required this.isNext, this.etaAt});
  final int order;
  final bool isDone;
  final bool isNext;
  final String? etaAt;

  String? get _time {
    if (etaAt == null) return null;
    try {
      final dt = DateTime.parse(etaAt!).toLocal();
      return '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
    } catch (_) { return null; }
  }

  @override
  Widget build(BuildContext context) {
    final Color color;
    // Use Admin Dashboard Route Builder Colors
    if (isDone) {
      color = const Color(0xFF16A34A); // Success Green
    } else if (isNext) {
      color = const Color(0xFF2563EB); // Admin Blue (Primary)
    } else {
      color = const Color(0xFFD97706); // Warning Orange
    }

    final time = _time;
    final double width = isNext ? 32 : 28;
    final double height = isNext ? 42 : 36;
    
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        CustomPaint(
          size: Size(width, height),
          painter: _RouteBuilderPinPainter(color: color, isSelected: isNext),
          child: SizedBox(
            width: width,
            height: height,
            child: Center(
              child: Padding(
                padding: EdgeInsets.only(bottom: height * 0.25),
                child: isDone
                    ? const Icon(LucideIcons.check, size: 14, color: Colors.white)
                    : Text(
                        '$order',
                        style: TextStyle(
                          fontSize: isNext ? 13 : 11,
                          fontWeight: FontWeight.w800,
                          color: Colors.white,
                          letterSpacing: -0.5,
                        ),
                      ),
              ),
            ),
          ),
        ),
        if (time != null)
          Container(
            margin: const EdgeInsets.only(top: 2),
            padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
            decoration: BoxDecoration(
              color: Colors.black.withValues(alpha: 0.85),
              borderRadius: BorderRadius.circular(4),
              boxShadow: [
                BoxShadow(color: Colors.black.withValues(alpha: 0.2), blurRadius: 4),
              ],
            ),
            child: Text(
              time,
              style: TextStyle(fontSize: 9, color: Colors.white, fontWeight: FontWeight.w800, height: 1),
            ),
          ),
      ],
    );
  }
}

class _RouteBuilderPinPainter extends CustomPainter {
  _RouteBuilderPinPainter({required this.color, required this.isSelected});
  final Color color;
  final bool isSelected;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.fill;

    // Drop shadow
    final shadowPaint = Paint()
      ..color = Colors.black.withValues(alpha: isSelected ? 0.4 : 0.25)
      ..maskFilter = MaskFilter.blur(BlurStyle.normal, isSelected ? 4 : 2);
    
    final path = Path();
    // Scaling factors based on original 28x36 SVG from RouteBuilderMap.tsx
    final sw = size.width / 28;
    final sh = size.height / 36;

    path.moveTo(14 * sw, 0 * sh);
    path.cubicTo(6.268 * sw, 0 * sh, 0 * sw, 6.268 * sh, 0 * sw, 14 * sh);
    path.cubicTo(0 * sw, 19.746 * sh, 3.44 * sw, 24.71 * sh, 8.44 * sw, 27.07 * sh);
    path.lineTo(14 * sw, 36 * sh);
    path.lineTo(19.56 * sw, 27.07 * sh);
    path.cubicTo(24.56 * sw, 24.71 * sh, 28 * sw, 19.746 * sh, 28 * sw, 14 * sh);
    path.cubicTo(28 * sw, 6.268 * sh, 21.732 * sw, 0 * sh, 14 * sw, 0 * sh);
    path.close();

    // Draw shadow slightly offset
    canvas.save();
    canvas.translate(0, 2);
    canvas.drawPath(path, shadowPaint);
    canvas.restore();

    canvas.drawPath(path, paint);

    if (isSelected) {
      final borderPaint = Paint()
        ..color = Colors.white.withValues(alpha: 0.5)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5;
      canvas.drawPath(path, borderPaint);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => true;
}

// ─── Map Top Bar ──────────────────────────────────────────────────────────────
class _MapTopBar extends ConsumerWidget {
  const _MapTopBar({required this.route, required this.onRefresh});
  final DriverRoute? route;
  final VoidCallback onRefresh;

  static const months = [
    'JANVIER', 'FÉVRIER', 'MARS', 'AVRIL', 'MAI', 'JUIN',
    'JUILLET', 'AOÛT', 'SEPTEMBRE', 'OCTOBRE', 'NOVEMBRE', 'DÉCEMBRE'
  ];
  static const days = ['LUN', 'MAR', 'MER', 'JEU', 'VEN', 'SAM', 'DIM'];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final today = DateTime.now();
    final pendingSync = ref.watch(offlineQueueProvider);
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
                  color: Theme.of(context).colorScheme.surface,
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.5),
                      blurRadius: 10,
                    ),
                  ],
                ),
                child: Row(
                  children: [
                    Icon(LucideIcons.navigation, size: 14, color: Theme.of(context).colorScheme.primary),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        route?.name ?? label.toUpperCase(),
                        style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w800),
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
                    color: Theme.of(context).colorScheme.surface,
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(color: Theme.of(context).colorScheme.secondary.withValues(alpha: 0.5)),
                  ),
                  child: Row(
                    children: [
                      Icon(LucideIcons.refreshCw, size: 14, color: Theme.of(context).colorScheme.secondary),
                      const SizedBox(width: 6),
                      Text(
                        '$pendingSync',
                        style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: Theme.of(context).colorScheme.secondary),
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
                  color: Theme.of(context).colorScheme.surface,
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
                ),
                child: Icon(PhosphorIconsBold.arrowsClockwise, size: 18, color: Theme.of(context).colorScheme.onSurfaceVariant),
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
      case DriverRouteStatus.draft:       color = Theme.of(context).colorScheme.onSurfaceVariant; break;
      case DriverRouteStatus.validated:   color = Theme.of(context).colorScheme.tertiary; break;
      case DriverRouteStatus.inProgress:  color = Theme.of(context).colorScheme.primary; break;
      case DriverRouteStatus.closed:      color = Theme.of(context).colorScheme.tertiary; break;
      case DriverRouteStatus.cancelled:   color = Theme.of(context).colorScheme.error; break;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        status.label.toUpperCase(),
        style: TextStyle(fontSize: 9, fontWeight: FontWeight.w700, color: color, letterSpacing: 0.5),
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
    required this.onConfirmPickup,
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
  final ValueChanged<String>? onConfirmPickup;
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
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(4)),
        boxShadow: const [
          BoxShadow(color: Colors.black, blurRadius: 40, spreadRadius: 10),
        ],
        border: Border(top: BorderSide(color: Theme.of(context).colorScheme.outlineVariant, width: 1.5)),
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
                decoration: BoxDecoration(color: Theme.of(context).colorScheme.outlineVariant, borderRadius: BorderRadius.circular(1)),
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
                      color: Theme.of(context).colorScheme.surface,
                      borderRadius: BorderRadius.circular(4),
                      border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
                    ),
                    child: Icon(LucideIcons.ban, size: 26, color: Theme.of(context).colorScheme.onSurfaceVariant),
                  ),
                  const SizedBox(height: 16),
                  Text('Aucune tournee assignee', style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 8),
                  Text('En attente d\'instructions du dispatch',
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
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
              SizedBox(
                width: double.infinity,
                height: 52,
                child: FilledButton.icon(
                  onPressed: isWorking ? null : () => onStart?.call(),
                  icon: isWorking
                      ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(PhosphorIconsBold.play),
                  label: const Text('Démarrer la tournée'),
                  style: FilledButton.styleFrom(backgroundColor: const Color(0xFF1E40AF)),
                ),
              )
            else if (route!.status == DriverRouteStatus.inProgress) ...[
              if (nextPendingStop != null)
                SizedBox(
                  width: double.infinity,
                  height: 52,
                  child: FilledButton.icon(
                    onPressed: isWorking
                        ? null
                        : () => nextPendingStop.isPickup
                            ? onConfirmPickup?.call(nextPendingStop.id)
                            : onOpenDetails(nextPendingStop.deliveryId),
                    icon: isWorking
                        ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : Icon(nextPendingStop.isPickup
                            ? PhosphorIconsBold.package
                            : PhosphorIconsBold.arrowRight),
                    label: Text(nextPendingStop.isPickup
                        ? 'Confirmer le chargement'
                        : 'Ouvrir le point ${nextPendingStop.stopOrder}'),
                  ),
                )
              else
                _InfoChip(label: 'Tous les arrêts validés', color: Theme.of(context).colorScheme.tertiary),
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
                        color: Theme.of(context).colorScheme.tertiary,
                        onTap: () => onDownloadPdf?.call(),
                      ),
                    ),
                    const SizedBox(width: 8),
                  ],
                  Expanded(
                    child: _ActionButton(
                      label: 'Naviguer',
                      icon: LucideIcons.navigation2,
                      color: Theme.of(context).colorScheme.primary,
                      onTap: () => onNavigate?.call(),
                    ),
                  ),
                ],
              ),
            ],

            if (route!.status == DriverRouteStatus.closed)
              _InfoChip(label: 'Tournée terminée', color: Theme.of(context).colorScheme.onSurfaceVariant),

            const SizedBox(height: 20),
            Row(
              children: [
                Text('Journal des arrêts', style: Theme.of(context).textTheme.labelMedium),
                const SizedBox(width: 12),
                Expanded(child: Divider()),
                const SizedBox(width: 12),
                Text('${route!.stops.length}', style: Theme.of(context).textTheme.labelMedium?.copyWith(color: Theme.of(context).colorScheme.primary)),
              ],
            ),
            const SizedBox(height: 12),
            if (route!.stops.isEmpty)
              Text('Aucun arrêt configuré.', style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant))
            else
              ...route!.stops.map((stop) => Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: stop.isPickup
                        ? _PickupStopCard(
                            stop: stop,
                            parcelCount: route!.pickupParcelCount(stop),
                            pickList: route!.deliveriesForDepot(stop.sourceDepotId),
                            isWorking: isWorking,
                            onConfirm: onConfirmPickup == null ? null : () => onConfirmPickup!(stop.id),
                          )
                        : _StopListItem(
                            stop: stop,
                            depotPicked: route!.isDepotPicked(stop),
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
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final total = route.stops.length;
    final done = route.stops.where((s) => s.status != DriverRouteStopStatus.pending).length;
    final progress = total == 0 ? 0.0 : done / total;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(route.name, style: theme.textTheme.titleMedium),
            ),
            Text('$done / $total', style: theme.textTheme.titleMedium?.copyWith(color: colorScheme.primary)),
          ],
        ),
        if (route.zone != null && route.zone!.isNotEmpty) ...[
          const SizedBox(height: 3),
          Row(children: [
            Icon(Icons.map_outlined, size: 12, color: colorScheme.onSurfaceVariant),
            const SizedBox(width: 4),
            Text(route.zone!, style: theme.textTheme.bodySmall?.copyWith(color: colorScheme.onSurfaceVariant)),
          ]),
        ],
        if (route.depotName != null || route.depotAddress != null) ...[
          const SizedBox(height: 6),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
            decoration: BoxDecoration(
              color: colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: colorScheme.outlineVariant),
            ),
            child: Row(
              children: [
                Icon(Icons.warehouse_outlined, size: 13, color: colorScheme.primary),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (route.depotName != null)
                        Text(route.depotName!, style: theme.textTheme.labelSmall?.copyWith(color: colorScheme.primary)),
                      if (route.depotAddress != null)
                        Text(route.depotAddress!, style: theme.textTheme.bodySmall?.copyWith(color: colorScheme.onSurfaceVariant), overflow: TextOverflow.ellipsis),
                    ],
                  ),
                ),
              ],
            ),
          ),
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
                  backgroundColor: colorScheme.surfaceContainerHighest,
                ),
              ),
            ),
            const SizedBox(width: 10),
            Text('${(progress * 100).toStringAsFixed(0)}%', style: theme.textTheme.labelMedium?.copyWith(color: colorScheme.primary)),
          ],
        ),
      ],
    );
  }
}

/// Multi-depot PICKUP stop: "Charger N colis — Dépôt X" with a pick list and a confirm button.
class _PickupStopCard extends StatelessWidget {
  const _PickupStopCard({
    required this.stop,
    required this.parcelCount,
    required this.pickList,
    required this.isWorking,
    required this.onConfirm,
  });

  final DriverRouteStop stop;
  final int parcelCount;
  final List<DriverRouteStop> pickList;
  final bool isWorking;
  final VoidCallback? onConfirm;

  static const Color _pickup = Color(0xFF0891B2);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final done = stop.status == DriverRouteStopStatus.completed;

    return Container(
      decoration: BoxDecoration(
        color: cs.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: done ? cs.outlineVariant : _pickup.withValues(alpha: 0.5)),
        boxShadow: const [BoxShadow(color: Color(0x0F000000), blurRadius: 8, offset: Offset(0, 2))],
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 30,
                  height: 30,
                  decoration: BoxDecoration(
                    color: _pickup.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Icon(Icons.warehouse_outlined, size: 16, color: _pickup),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Charger $parcelCount colis — Dépôt ${stop.sourceDepotName ?? ''}'.trim(),
                    style: theme.textTheme.labelLarge?.copyWith(fontSize: 13, fontWeight: FontWeight.w800, color: done ? cs.onSurfaceVariant : _pickup),
                  ),
                ),
                if (done)
                  const Icon(Icons.check_circle, size: 18, color: _pickup),
              ],
            ),
            if (pickList.isNotEmpty) ...[
              const SizedBox(height: 10),
              ...pickList.map((d) => Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Row(
                      children: [
                        Icon(Icons.circle, size: 5, color: cs.onSurfaceVariant),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            [d.orderRef, d.clientName].where((e) => e != null && e.isNotEmpty).join(' · '),
                            style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                  )),
            ],
            if (!done) ...[
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: (isWorking || onConfirm == null) ? null : onConfirm,
                  style: FilledButton.styleFrom(backgroundColor: _pickup),
                  icon: const Icon(Icons.inventory_2_outlined, size: 16),
                  label: const Text('Confirmer le chargement'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _StopListItem extends StatelessWidget {
  const _StopListItem({
    required this.stop,
    required this.onStartTransit,
    required this.onOpenPod,
    required this.onOpenDetails,
    this.depotPicked = true,
  });

  final DriverRouteStop stop;
  final ValueChanged<String> onStartTransit;
  final ValueChanged<String> onOpenPod;
  final ValueChanged<String> onOpenDetails;
  /// False when this delivery's source depot hasn't been picked up yet — the row
  /// is locked until the driver confirms that depot's pickup stop.
  final bool depotPicked;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final ds = stop.parsedDeliveryStatus;

    return GestureDetector(
      onTap: depotPicked
          ? () => onOpenDetails(stop.deliveryId)
          : () => ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Confirmez d\'abord le chargement au dépôt')),
              ),
      behavior: HitTestBehavior.opaque,
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
            Row(
              children: [
                Container(
                  width: 30,
                  height: 30,
                  decoration: BoxDecoration(
                    color: colorScheme.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Center(
                    child: Text('${stop.stopOrder}', style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w900)),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    stop.clientName ?? 'Client',
                    style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w800),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 8),
                _DeliveryStatusBadge(ds, colorScheme),
              ],
            ),
            if (stop.address != null || stop.city != null) ...[
              const SizedBox(height: 6),
              Row(
                children: [
                  Icon(Icons.location_on_outlined, size: 12, color: colorScheme.onSurfaceVariant),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text(
                      [stop.address, stop.city].where((e) => e != null && e.isNotEmpty).join(', '),
                      style: theme.textTheme.bodySmall?.copyWith(color: colorScheme.onSurfaceVariant),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              ),
            ],
            if (stop.orderRef != null || stop.totalAmount != null) ...[
              const SizedBox(height: 4),
              Row(
                children: [
                  if (stop.orderRef != null)
                    Text(stop.orderRef!, style: theme.textTheme.bodySmall?.copyWith(color: colorScheme.onSurfaceVariant)),
                  if (stop.orderRef != null && stop.totalAmount != null)
                    Text('  .  ', style: TextStyle(color: colorScheme.onSurfaceVariant)),
                  if (stop.totalAmount != null)
                    Text('${stop.totalAmount!.toStringAsFixed(3)} TND', style: theme.textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w700)),
                ],
              ),
            ],
            if (stop.formattedEta != null || stop.formattedSla != null) ...[
              const SizedBox(height: 4),
              Row(
                children: [
                  if (stop.formattedEta != null) ...[
                    Icon(Icons.schedule_rounded, size: 11, color: colorScheme.tertiary),
                    const SizedBox(width: 3),
                    Text('ETA ${stop.formattedEta}', style: TextStyle(fontSize: 10, color: colorScheme.tertiary, fontWeight: FontWeight.w600)),
                  ],
                  if (stop.formattedEta != null && stop.formattedSla != null)
                    const Text('   '),
                  if (stop.formattedSla != null) ...[
                    Icon(Icons.flag_outlined, size: 11, color: colorScheme.secondary),
                    const SizedBox(width: 3),
                    Text('SLA ${stop.formattedSla}', style: TextStyle(fontSize: 10, color: colorScheme.secondary, fontWeight: FontWeight.w600)),
                  ],
                ],
              ),
            ],
            ],
          ),
        ),
      ),
    );
  }
}

class _DeliveryStatusBadge extends StatelessWidget {
  const _DeliveryStatusBadge(this.status, this.colorScheme);
  final DeliveryStatus status;
  final ColorScheme colorScheme;

  @override
  Widget build(BuildContext context) {
    final Color color;
    final String label;
    switch (status) {
      case DeliveryStatus.pickedUp:
        color = colorScheme.tertiary; label = 'Charge'; break;
      case DeliveryStatus.inTransit:
        color = colorScheme.primary; label = 'En transit'; break;
      case DeliveryStatus.delivered:
        color = colorScheme.tertiary; label = 'Livre'; break;
      case DeliveryStatus.partially_delivered:
        color = colorScheme.secondary; label = 'Partiel'; break;
      case DeliveryStatus.failed:
        color = colorScheme.error; label = 'Echoue'; break;
      case DeliveryStatus.cancelled:
        color = colorScheme.onSurfaceVariant; label = 'Annule'; break;
      default:
        color = colorScheme.onSurfaceVariant; label = status.label; break;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: color.withValues(alpha: 0.3), width: 0.5),
      ),
      child: Text(label, style: TextStyle(fontSize: 9, fontWeight: FontWeight.w700, color: color)),
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
              Text(label, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: color)),
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
      color: Colors.amber.shade700,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.cloud_off_rounded, size: 13, color: Colors.white),
          const SizedBox(width: 6),
          Text(
            'Donnees en cache - reconnectez-vous pour actualiser',
            style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: Colors.white),
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
        child: Text(label, style: TextStyle(fontSize: 13, color: color, fontWeight: FontWeight.w500)),
      );
}

class _MapPlaceholderLoading extends StatelessWidget {
  const _MapPlaceholderLoading();

  @override
  Widget build(BuildContext context) => Scaffold(
        body: LoadingState(message: 'Chargement de la tournee...'),
      );
}

class _MapError extends StatelessWidget {
  const _MapError({required this.onRetry});
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Scaffold(
        body: EmptyState(
          icon: PhosphorIconsRegular.cloudSlash,
          title: 'Impossible de charger la tournee',
          action: onRetry,
          actionLabel: 'Reessayer',
        ),
      );
}
