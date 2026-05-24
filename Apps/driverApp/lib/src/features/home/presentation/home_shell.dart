import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../../services/notification_store.dart';
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
    _initFcmHandlers();
  }

  void _initFcmHandlers() {
    final store = ref.read(notificationStoreProvider.notifier);
    ref.read(fcmServiceProvider).setHandlers(
      onReceived: (title, body, type) => store.add(title: title, body: body, type: type),
      onTap: () {
        if (mounted) setState(() => _index = 0);
        _showNotificationPanel();
      },
    );
  }

  void _showNotificationPanel() {
    if (!mounted) return;
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => const _NotificationPanel(),
    );
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

    final route = event.routeName.isNotEmpty ? '«${event.routeName}»' : 'votre tournée';
    final String message;
    IconData icon;
    Color color;

    switch (event.event) {
      case 'ROUTE_ASSIGNED':
        message = 'Tournée $route assignée — consultez-la avant de partir.';
        icon = Icons.check_circle_outline;
        color = const Color(0xFF16A34A);
        break;
      case 'ROUTE_CANCELLED':
        message = 'La tournée $route a été annulée.';
        icon = Icons.cancel_outlined;
        color = const Color(0xFFDC2626);
        break;
      case 'ROUTE_REASSIGNED_AWAY':
        message = 'La tournée $route a été réaffectée à un autre chauffeur.';
        icon = Icons.warning_amber_rounded;
        color = const Color(0xFFF59E0B);
        break;
      case 'ROUTE_REASSIGNED_TO_YOU':
        message = 'La tournée $route vous a été réaffectée !';
        icon = Icons.check_circle_outline;
        color = const Color(0xFF16A34A);
        break;
      case 'STOP_ADDED':
        final addedClient = event.clientName ?? 'Un arrêt';
        message = '$addedClient ajouté à $route.';
        icon = Icons.add_location_alt_outlined;
        color = const Color(0xFF2563EB);
        break;
      case 'STOP_REMOVED':
        final removedClient = event.clientName ?? 'Un arrêt';
        final ref = event.erpOrderId != null ? ' [${event.erpOrderId}]' : '';
        final why = event.reason != null ? ' — ${event.reason}' : '';
        message = '$removedClient$ref retiré de $route$why.';
        icon = Icons.remove_circle_outline;
        color = const Color(0xFFF59E0B);
        break;
      case 'ROUTE_UPDATED':
        message = 'La tournée $route a été modifiée.';
        icon = Icons.info_outline;
        color = const Color(0xFF2563EB);
        break;
      case 'STOPS_TRANSFERRED_OUT':
        message = 'Des arrêts ont été retirés de $route.';
        icon = Icons.swap_horiz_rounded;
        color = const Color(0xFFF59E0B);
        break;
      case 'STOPS_TRANSFERRED_IN':
        message = 'De nouveaux arrêts ont été ajoutés à $route.';
        icon = Icons.playlist_add_rounded;
        color = const Color(0xFF2563EB);
        break;
      default:
        return;
    }

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Row(
          children: [
            Icon(icon, color: Colors.white, size: 18),
            const SizedBox(width: 8),
            Expanded(child: Text(message, style: const TextStyle(color: Colors.white))),
          ],
        ),
        backgroundColor: color,
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 5),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
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
      ref.invalidate(todayRouteProvider);
      ref.invalidate(activeDeliveriesProvider);
      ref.invalidate(weekRoutesProvider(ref.read(calendarWeekProvider)));
    } catch (_) {}
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
        body: Stack(
          children: [
            Column(
              children: [
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
                Consumer(
                  builder: (context, ref, _) {
                    final status = ref.watch(driverProfileProvider).value?.onlineStatus ?? 'OFFLINE';
                    if (status == 'OFFLINE') return const SizedBox.shrink();
                    final (Color dot, String label) = switch (status) {
                      'ONLINE'   => (const Color(0xFF10B981), 'En service'),
                      'ON_BREAK' => (const Color(0xFFF59E0B), 'En pause'),
                      _          => (const Color(0xFF9CA3AF), 'Hors service'),
                    };
                    return GestureDetector(
                      onTap: () => setState(() => _index = 2),
                      child: Container(
                        width: double.infinity,
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                        color: AppColors.surface,
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Container(
                              width: 7,
                              height: 7,
                              decoration: BoxDecoration(color: dot, shape: BoxShape.circle),
                            ),
                            const SizedBox(width: 6),
                            Text(
                              label,
                              style: GoogleFonts.inter(
                                fontSize: 11,
                                fontWeight: FontWeight.w600,
                                color: dot,
                              ),
                            ),
                            const SizedBox(width: 6),
                            Icon(Icons.chevron_right_rounded, size: 13, color: dot),
                          ],
                        ),
                      ),
                    );
                  },
                ),
                Expanded(
                  child: IndexedStack(index: _index, children: pages),
                ),
              ],
            ),
            // Bell icon with unread badge
            Positioned(
              top: MediaQuery.of(context).padding.top + 8,
              right: 12,
              child: Consumer(
                builder: (context, ref, _) {
                  final unread = ref.watch(unreadNotifCountProvider);
                  return GestureDetector(
                    onTap: _showNotificationPanel,
                    child: Stack(
                      clipBehavior: Clip.none,
                      children: [
                        Container(
                          width: 36,
                          height: 36,
                          decoration: BoxDecoration(
                            color: AppColors.surface.withValues(alpha: 0.92),
                            shape: BoxShape.circle,
                            border: Border.all(color: AppColors.border),
                          ),
                          child: const Icon(Icons.notifications_outlined, size: 18),
                        ),
                        if (unread > 0)
                          Positioned(
                            top: -2,
                            right: -2,
                            child: Container(
                              width: 16,
                              height: 16,
                              decoration: const BoxDecoration(
                                color: AppColors.danger,
                                shape: BoxShape.circle,
                              ),
                              child: Center(
                                child: Text(
                                  unread > 9 ? '9+' : '$unread',
                                  style: const TextStyle(color: Colors.white, fontSize: 9, fontWeight: FontWeight.w700),
                                ),
                              ),
                            ),
                          ),
                      ],
                    ),
                  );
                },
              ),
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

class _NotificationPanel extends ConsumerWidget {
  const _NotificationPanel();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final notifications = ref.watch(notificationStoreProvider);
    final store = ref.read(notificationStoreProvider.notifier);

    // Mark all as read when panel opens
    WidgetsBinding.instance.addPostFrameCallback((_) => store.markAllRead());

    return DraggableScrollableSheet(
      initialChildSize: 0.55,
      minChildSize: 0.35,
      maxChildSize: 0.85,
      expand: false,
      builder: (_, controller) => Column(
        children: [
          const SizedBox(height: 8),
          Container(width: 36, height: 4, decoration: BoxDecoration(color: AppColors.border, borderRadius: BorderRadius.circular(2))),
          const SizedBox(height: 12),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                Text('Notifications', style: GoogleFonts.manrope(fontSize: 16, fontWeight: FontWeight.w700)),
              ],
            ),
          ),
          const SizedBox(height: 8),
          const Divider(height: 1),
          Expanded(
            child: notifications.isEmpty
                ? Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.notifications_off_outlined, size: 40, color: Color(0xFF9CA3AF)),
                        const SizedBox(height: 8),
                        Text('Aucune notification', style: GoogleFonts.inter(color: const Color(0xFF9CA3AF))),
                      ],
                    ),
                  )
                : ListView.separated(
                    controller: controller,
                    itemCount: notifications.length,
                    separatorBuilder: (_, __) => const Divider(height: 1, indent: 16),
                    itemBuilder: (_, i) {
                      final n = notifications[i];
                      return ListTile(
                        leading: CircleAvatar(
                          backgroundColor: _typeColor(n.type).withValues(alpha: 0.15),
                          child: Icon(_typeIcon(n.type), size: 18, color: _typeColor(n.type)),
                        ),
                        title: Text(n.title, style: GoogleFonts.manrope(fontWeight: FontWeight.w600, fontSize: 13)),
                        subtitle: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            if (n.body.isNotEmpty) Text(n.body, style: GoogleFonts.inter(fontSize: 12)),
                            Text(_formatTime(n.receivedAt), style: GoogleFonts.inter(fontSize: 11, color: const Color(0xFF9CA3AF))),
                          ],
                        ),
                        isThreeLine: n.body.isNotEmpty,
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }

  IconData _typeIcon(String type) {
    switch (type) {
      case 'DELIVERY_ASSIGNED': return Icons.local_shipping_outlined;
      case 'ROUTE_VALIDATED':   return Icons.route_outlined;
      case 'ROUTE_UPDATED':     return Icons.edit_road_outlined;
      case 'HANDOFF_REQUIRED':  return Icons.swap_horiz_outlined;
      default:                  return Icons.notifications_outlined;
    }
  }

  Color _typeColor(String type) {
    switch (type) {
      case 'DELIVERY_ASSIGNED': return const Color(0xFF0EA5E9);
      case 'ROUTE_VALIDATED':   return const Color(0xFF10B981);
      case 'ROUTE_UPDATED':     return const Color(0xFFF59E0B);
      case 'HANDOFF_REQUIRED':  return const Color(0xFF8B5CF6);
      default:                  return const Color(0xFF6B7280);
    }
  }

  String _formatTime(DateTime dt) {
    final now = DateTime.now();
    final diff = now.difference(dt);
    if (diff.inMinutes < 1) return 'À l\'instant';
    if (diff.inMinutes < 60) return 'Il y a ${diff.inMinutes} min';
    if (diff.inHours < 24) return 'Il y a ${diff.inHours}h';
    return 'Il y a ${diff.inDays}j';
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
