import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../../services/offline_queue_service.dart';
import '../../../services/websocket_service.dart';
import '../../../theme/app_theme.dart';
import '../../auth/models/auth_models.dart';
import '../../deliveries/models/delivery_models.dart';
import '../../profile/presentation/profile_screen.dart';
import '../../routes/models/route_models.dart';
import '../../routes/presentation/calendar_screen.dart';
import '../../routes/presentation/routes_screen.dart';

class HomeShell extends ConsumerStatefulWidget {
  const HomeShell({super.key});
  static const routeName = '/home';

  @override
  ConsumerState<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends ConsumerState<HomeShell> {
  int _index = 0;
  Timer? _locationTimer;
  Timer? _assignmentRefreshTimer;
  bool _isTracking = false;
  Set<String> _knownRouteDeliveryIds = <String>{};
  bool _isOffline = false;
  StreamSubscription<bool>? _connectivitySub;

  final _wsService = WebSocketService();

  static const _navItems = [
    _NavItem(icon: PhosphorIconsFill.path,         label: 'Tournée'),
    _NavItem(icon: PhosphorIconsFill.calendarDots, label: 'Calendrier'),
    _NavItem(icon: PhosphorIconsFill.userCircle,   label: 'Profil'),
  ];

  @override
  void initState() {
    super.initState();
    _assignmentRefreshTimer = Timer.periodic(const Duration(seconds: 15), (_) {
      _refreshAssignmentsAndNotify();
    });
    _initWebSocket();
    _initConnectivityListener();
  }

  Future<void> _initWebSocket() async {
    final authState = ref.read(authControllerProvider);
    final driverId = authState.driver?.id;
    if (driverId == null || driverId.isEmpty) return;

    final token = await ref.read(tokenStorageProvider).readAccessToken();
    if (token == null) return;

    final config = ref.read(appConfigProvider);
    _wsService.connect(
      wsBaseUrl: config.apiBaseUrl,
      token: token,
      driverId: driverId,
      onEvent: _handleWsEvent,
    );
  }

  void _handleWsEvent(RouteWsEvent event) {
    if (!mounted) return;

    // Invalidate providers so UI refreshes automatically
    ref.invalidate(todayRouteProvider);
    ref.invalidate(weekRoutesProvider(ref.read(calendarWeekProvider)));

    final String message;
    switch (event.event) {
      case 'ROUTE_ASSIGNED':
        message = 'Nouvelle tournée assignée !';
        break;
      case 'ROUTE_CANCELLED':
        message = 'Votre tournée a été annulée.';
        break;
      case 'ROUTE_REASSIGNED_AWAY':
        message = 'Tournée réaffectée à un autre chauffeur.';
        break;
      case 'ROUTE_REASSIGNED_TO_YOU':
        message = 'Une tournée vous a été réaffectée !';
        break;
      case 'STOP_ADDED':
        message = 'Nouvel arrêt ajouté à votre tournée.';
        break;
      case 'STOP_REMOVED':
        message = 'Un arrêt a été retiré de votre tournée.';
        break;
      case 'ROUTE_UPDATED':
        message = 'Votre tournée a été modifiée.';
        break;
      default:
        return;
    }

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 4),
      ),
    );
  }

  void _initConnectivityListener() {
    final connectivity = ref.read(connectivityServiceProvider);
    _connectivitySub = connectivity.onlineStream.listen((isOnline) {
      if (!mounted) return;
      setState(() => _isOffline = !isOnline);
      if (isOnline) {
        // Belt + suspenders: trigger queue replay on reconnect
        ref.read(offlineQueueProvider.notifier).processQueue();
      }
    });
    // Initial check
    connectivity.isOnline.then((online) {
      if (mounted) setState(() => _isOffline = !online);
    });
  }

  @override
  void dispose() {
    _locationTimer?.cancel();
    _assignmentRefreshTimer?.cancel();
    _connectivitySub?.cancel();
    _wsService.disconnect();
    super.dispose();
  }

  void _startTracking() {
    if (_isTracking) return;
    _isTracking = true;
    _locationTimer = Timer.periodic(const Duration(seconds: 20), (_) => _pushLocation());
    _pushLocation();
  }

  void _stopTracking() {
    _isTracking = false;
    _locationTimer?.cancel();
    _locationTimer = null;
  }

  Future<void> _pushLocation() async {
    try {
      final point = await LocationService().currentPosition();
      if (point == null) return;
      await ref.read(profileRepositoryProvider).updateLocation(point.lat, point.lng);
    } catch (_) {}
  }

  Future<void> _refreshAssignmentsAndNotify() async {
    try {
      final route = await ref.read(routeRepositoryProvider).fetchToday();
      final nextIds = route == null
          ? <String>{}
          : route.stops.map((stop) => stop.deliveryId).toSet();

      if (_knownRouteDeliveryIds.isNotEmpty && mounted) {
        final added = nextIds.difference(_knownRouteDeliveryIds);
        final removed = _knownRouteDeliveryIds.difference(nextIds);

        if (added.isNotEmpty) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: const Text('NOUVELLE TOURNÉE ASSIGNÉE', style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 0.5)),
              backgroundColor: AppColors.accent,
              behavior: SnackBarBehavior.floating,
              action: SnackBarAction(label: 'VOIR', textColor: Colors.black, onPressed: () {}),
            ),
          );
        } else if (removed.isNotEmpty) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('TOURNÉE ANNULÉE OU MODIFIÉE'),
              backgroundColor: AppColors.danger,
              behavior: SnackBarBehavior.floating,
            ),
          );
        }
      }

      _knownRouteDeliveryIds = nextIds;
      ref.invalidate(todayRouteProvider);
      ref.invalidate(activeDeliveriesProvider);
      ref.invalidate(weekRoutesProvider(ref.read(calendarWeekProvider)));
    } catch (_) {
      // Ignore background refresh errors; main screens still handle explicit fetch failures.
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(activeDeliveriesProvider, (_, next) {
      next.whenData((list) {
        if (list.any((d) => d.status == DeliveryStatus.inTransit)) {
          _startTracking();
        } else if (!_isRouteInProgress()) {
          _stopTracking();
        }
      });
    });

    ref.listen(todayRouteProvider, (_, next) {
      next.whenData((route) {
        if (route?.status == DriverRouteStatus.inProgress) {
          _startTracking();
        } else if (!_hasInTransit()) {
          _stopTracking();
        }
      });
    });

    final pages = [
      const RoutesScreen(),
      CalendarScreen(onNavigateToRoute: () => setState(() => _index = 0)),
      const SafeArea(child: ProfileScreen()),
    ];

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light,
        systemNavigationBarColor: AppColors.surface,
        systemNavigationBarIconBrightness: Brightness.dark,
      ),
      child: Scaffold(
        backgroundColor: AppColors.background,
        body: Column(
          children: [
            // Global offline banner
            if (_isOffline)
              Container(
                width: double.infinity,
                padding: EdgeInsets.only(
                  top: MediaQuery.of(context).padding.top + 6,
                  bottom: 8,
                  left: 16,
                  right: 16,
                ),
                color: AppColors.warning.withValues(alpha: 0.92),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    const Icon(Icons.cloud_off_rounded, size: 14, color: Colors.black),
                    const SizedBox(width: 8),
                    Flexible(
                      child: Text(
                        'Hors ligne — les actions seront synchronisées à la reconnexion',
                        style: GoogleFonts.inter(
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                          color: Colors.black,
                        ),
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ],
                ),
              ),
            Expanded(
              child: IndexedStack(index: _index, children: pages),
            ),
          ],
        ),
        bottomNavigationBar: _BottomNav(
          index: _index,
          items: _navItems,
          onTap: (i) => setState(() => _index = i),
        ),
      ),
    );
  }

  bool _hasInTransit() {
    return ref.read(activeDeliveriesProvider).value?.any((d) => d.status == DeliveryStatus.inTransit) ?? false;
  }

  bool _isRouteInProgress() {
    return ref.read(todayRouteProvider).value?.status == DriverRouteStatus.inProgress;
  }
}

class _NavItem {
  const _NavItem({required this.icon, required this.label});
  final IconData icon;
  final String label;
}

class _BottomNav extends StatelessWidget {
  const _BottomNav({required this.index, required this.items, required this.onTap});
  final int index;
  final List<_NavItem> items;
  final ValueChanged<int> onTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.surface,
        border: Border(top: BorderSide(color: AppColors.border, width: 1.2)),
        boxShadow: [
          BoxShadow(color: Color(0x1A101014), blurRadius: 26, offset: Offset(0, -8)),
        ],
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
          child: Row(
            children: List.generate(items.length, (i) {
              final item = items[i];
              final selected = i == index;
              return Expanded(
                child: GestureDetector(
                  onTap: () => onTap(i),
                  behavior: HitTestBehavior.opaque,
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 180),
                    curve: Curves.easeOut,
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    decoration: BoxDecoration(
                      color: selected ? AppColors.accent : Colors.transparent,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          item.icon,
                          size: 22,
                          color: selected ? Colors.black : AppColors.muted,
                        ),
                        const SizedBox(height: 3),
                        Text(
                          item.label,
                          style: GoogleFonts.manrope(
                            fontSize: 10,
                            fontWeight: selected ? FontWeight.w800 : FontWeight.w600,
                            color: selected ? Colors.black : AppColors.muted,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              );
            }),
          ),
        ),
      ),
    );
  }
}
