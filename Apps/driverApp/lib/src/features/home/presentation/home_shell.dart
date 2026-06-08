import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../../../services/locale_provider.dart';
import '../../../services/location_service.dart';
import '../../../services/notification_store.dart';
import '../../../services/websocket_service.dart';
import '../../../services/offline_queue_service.dart';
import '../../../theme/status_colors.dart';
import '../../deliveries/models/delivery_models.dart';
import '../../deliveries/presentation/delivery_detail_screen.dart';
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
  StreamSubscription<bool>? _connectivitySub;

  final _wsService = WebSocketService();

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
      onTap: (type, deliveryId) {
        if (type.startsWith('HANDOFF_') && deliveryId != null && deliveryId.isNotEmpty) {
          _openHandoffDelivery(deliveryId);
          return;
        }
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

    final config = ref.read(appConfigProvider);
    final storage = ref.read(tokenStorageProvider);
    _wsService.connect(
      wsBaseUrl: config.apiBaseUrl,
      driverId: driverId,
      tokenStorage: storage,
      onEvent: _handleWsEvent,
    );
  }

  void _handleWsEvent(RouteWsEvent event) {
    if (!mounted) return;

    // S2: an admin force-logged-out this driver — sign out instantly instead of waiting for
    // the access token to expire. The DriverApp auth listener handles navigation to login.
    if (event.event == 'session.revoked') {
      ref.read(authControllerProvider.notifier).logout();
      return;
    }

    ref.invalidate(todayRouteProvider);
    ref.invalidate(weekRoutesProvider(ref.read(calendarWeekProvider)));
    ref.invalidate(activeDeliveriesProvider);

    if (event.event.startsWith('handoff.')) {
      _handleHandoffWsEvent(event);
      return;
    }

    final locale = ref.read(localeProvider);
    final statusColors = Theme.of(context).extension<StatusColors>()!;
    final routeStr = event.routeName.isNotEmpty ? '\u00ab${event.routeName}\u00bb' : DriverCopy.get('ws_generic_route', locale);
    final String message;
    IconData icon;
    Color color;

    switch (event.event) {
      case 'ROUTE_ASSIGNED':
        message = DriverCopy.get('ws_route_assigned', locale).replaceAll('{route}', routeStr);
        icon = PhosphorIconsRegular.checkCircle;
        color = statusColors.deliveredText;
        break;
      case 'ROUTE_CANCELLED':
        message = DriverCopy.get('ws_route_cancelled', locale).replaceAll('{route}', routeStr);
        icon = PhosphorIconsRegular.xCircle;
        color = statusColors.failedText;
        break;
      case 'ROUTE_REASSIGNED_AWAY':
        message = DriverCopy.get('ws_route_reassigned_away', locale).replaceAll('{route}', routeStr);
        icon = PhosphorIconsRegular.warning;
        color = statusColors.cancelledText;
        break;
      case 'ROUTE_REASSIGNED_TO_YOU':
        message = DriverCopy.get('ws_route_reassigned_to_you', locale).replaceAll('{route}', routeStr);
        icon = PhosphorIconsRegular.checkCircle;
        color = statusColors.deliveredText;
        break;
      case 'STOP_ADDED':
        final addedClient = event.clientName ?? '';
        message = DriverCopy.get('ws_stop_added', locale)
            .replaceAll('{client}', addedClient)
            .replaceAll('{route}', routeStr);
        icon = PhosphorIconsRegular.mapPin;
        color = statusColors.scheduledText;
        break;
      case 'STOP_REMOVED':
        final removedClient = event.clientName ?? '';
        final refStr = event.erpOrderId != null ? ' [${event.erpOrderId}]' : '';
        final why = event.reason != null ? ' \u2014 ${event.reason}' : '';
        message = DriverCopy.get('ws_stop_removed', locale)
            .replaceAll('{client}', removedClient)
            .replaceAll('{ref}', refStr)
            .replaceAll('{route}', routeStr)
            .replaceAll('{why}', why);
        icon = PhosphorIconsRegular.minusCircle;
        color = statusColors.cancelledText;
        break;
      case 'ROUTE_UPDATED':
        message = DriverCopy.get('ws_route_updated', locale).replaceAll('{route}', routeStr);
        icon = PhosphorIconsRegular.info;
        color = statusColors.unscheduledText;
        break;
      case 'STOPS_TRANSFERRED_OUT':
        message = DriverCopy.get('ws_stops_transferred_out', locale).replaceAll('{route}', routeStr);
        icon = PhosphorIconsRegular.arrowsLeftRight;
        color = statusColors.cancelledText;
        break;
      case 'STOPS_TRANSFERRED_IN':
        message = DriverCopy.get('ws_stops_transferred_in', locale).replaceAll('{route}', routeStr);
        icon = PhosphorIconsRegular.listPlus;
        color = statusColors.scheduledText;
        break;
      case 'pickup.overdue':
        message = DriverCopy.get('ws_pickup_overdue', locale)
            .replaceAll('{client}', event.clientName ?? '')
            .replaceAll('{count}', event.reason ?? '')
            .replaceAll('  ', ' ')
            .trim();
        icon = PhosphorIconsRegular.house;
        color = statusColors.failedText;
        break;
      default:
        return;
    }

    if (!mounted) return;
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

  void _handleHandoffWsEvent(RouteWsEvent event) {
    if (!mounted) return;
    final locale = ref.read(localeProvider);
    final statusColors = Theme.of(context).extension<StatusColors>()!;

    final refStr = (event.erpOrderId != null && event.erpOrderId!.isNotEmpty)
        ? '#${event.erpOrderId}'
        : (event.deliveryId != null && event.deliveryId!.length >= 8
            ? '#${event.deliveryId!.substring(0, 8)}'
            : '');
    String build(String key, String? name) => DriverCopy.get(key, locale)
        .replaceAll('{ref}', refStr)
        .replaceAll('{name}', (name != null && name.isNotEmpty) ? name : DriverCopy.get('ws_handoff_other', locale))
        .replaceAll('  ', ' ')
        .trim();

    switch (event.event) {
      case 'handoff.incoming':
      case 'handoff.code_ready':
        _showHandoffBanner(
          message: build('ws_handoff_incoming', event.fromDriverName),
          icon: PhosphorIconsRegular.qrCode,
          color: statusColors.scheduledText,
          actionLabel: DriverCopy.get('handoff_action_scan', locale),
          onAction: () => _openHandoffDelivery(event.deliveryId),
          locale: locale,
        );
        break;
      case 'handoff.outgoing':
        _showHandoffBanner(
          message: build('ws_handoff_outgoing', event.toDriverName),
          icon: PhosphorIconsRegular.arrowsLeftRight,
          color: statusColors.inTransitText,
          actionLabel: DriverCopy.get('handoff_action_show', locale),
          onAction: () => _openHandoffDelivery(event.deliveryId),
          locale: locale,
        );
        break;
      case 'handoff.confirmed':
        ScaffoldMessenger.of(context).hideCurrentMaterialBanner();
        _showHandoffSnack(build('ws_handoff_confirmed', null), PhosphorIconsRegular.checkCircle, statusColors.deliveredText);
        break;
      case 'handoff.cancelled':
        ScaffoldMessenger.of(context).hideCurrentMaterialBanner();
        _showHandoffSnack(build('ws_handoff_cancelled', null), PhosphorIconsRegular.xCircle, statusColors.failedText);
        break;
      default:
        return;
    }
  }

  void _openHandoffDelivery(String? deliveryId) {
    ScaffoldMessenger.of(context).hideCurrentMaterialBanner();
    if (deliveryId == null || deliveryId.isEmpty || !mounted) return;
    setState(() => _index = 0);
    Navigator.of(context).pushNamed(
      DeliveryDetailScreen.routeName,
      arguments: DeliveryDetailArgs(deliveryId: deliveryId),
    );
  }

  void _showHandoffBanner({
    required String message,
    required IconData icon,
    required Color color,
    required String actionLabel,
    required VoidCallback onAction,
    required String locale,
  }) {
    if (!mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    final theme = Theme.of(context);
    messenger.hideCurrentMaterialBanner();
    messenger.showMaterialBanner(
      MaterialBanner(
        backgroundColor: theme.colorScheme.surface,
        dividerColor: theme.colorScheme.outlineVariant,
        leading: Icon(icon, color: color),
        content: Text(
          message,
          style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: theme.colorScheme.onSurface),
        ),
        actions: [
          TextButton(
            onPressed: () => messenger.hideCurrentMaterialBanner(),
            child: Text(DriverCopy.get('cancel', locale)),
          ),
          TextButton(
            onPressed: () { messenger.hideCurrentMaterialBanner(); onAction(); },
            style: TextButton.styleFrom(foregroundColor: color),
            child: Text(actionLabel),
          ),
        ],
      ),
    );
  }

  void _showHandoffSnack(String message, IconData icon, Color color) {
    if (!mounted) return;
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
      if (isOnline) {
        ref.read(offlineQueueProvider.notifier).processQueue();
      }
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
    final theme = Theme.of(context);
    final locale = ref.watch(localeProvider);
    final colorScheme = theme.colorScheme;
    final statusColors = theme.extension<StatusColors>()!;

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
      value: SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: theme.brightness == Brightness.dark ? Brightness.light : Brightness.dark,
        systemNavigationBarColor: colorScheme.surface,
        systemNavigationBarIconBrightness: theme.brightness == Brightness.dark ? Brightness.light : Brightness.dark,
      ),
      child: Scaffold(
        body: Stack(
          children: [
            Column(
              children: [
                const _OfflineStatusBar(),
                Consumer(
                  builder: (context, ref, _) {
                    final status = ref.watch(driverProfileProvider).value?.onlineStatus ?? 'OFFLINE';
                    if (status == 'OFFLINE') return const SizedBox.shrink();
                    final (Color dot, String label) = switch (status) {
                      'ONLINE'   => (statusColors.online, DriverCopy.get('status_online', locale)),
                      'ON_BREAK' => (statusColors.onBreak, DriverCopy.get('status_on_break', locale)),
                      _          => (statusColors.offline, DriverCopy.get('status_offline', locale)),
                    };
                    return GestureDetector(
                      onTap: () => setState(() => _index = 2),
                      child: Container(
                        width: double.infinity,
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                        color: colorScheme.surfaceContainerLow,
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
                            Icon(PhosphorIconsRegular.caretRight, size: 13, color: dot),
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
            Positioned(
              top: MediaQuery.of(context).padding.top + 8,
              right: 12,
              child: Consumer(
                builder: (context, ref, _) {
                  final unread = ref.watch(unreadNotifCountProvider);
                  return IconButton(
                    onPressed: _showNotificationPanel,
                    icon: Badge(
                      isLabelVisible: unread > 0,
                      label: Text(unread > 9 ? '9+' : '$unread'),
                      child: const Icon(PhosphorIconsRegular.bell),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: _index,
          onDestinationSelected: (i) => setState(() => _index = i),
          destinations: [
            NavigationDestination(
              icon: const Icon(PhosphorIconsFill.path),
              label: DriverCopy.get('tab_route', locale),
            ),
            NavigationDestination(
              icon: const Icon(PhosphorIconsFill.calendarDots),
              label: DriverCopy.get('tab_calendar', locale),
            ),
            NavigationDestination(
              icon: const Icon(PhosphorIconsFill.userCircle),
              label: DriverCopy.get('tab_profile', locale),
            ),
          ],
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
    final theme = Theme.of(context);
    final statusColors = theme.extension<StatusColors>()!;
    final notifications = ref.watch(notificationStoreProvider);
    final store = ref.read(notificationStoreProvider.notifier);
    final locale = ref.watch(localeProvider);

    WidgetsBinding.instance.addPostFrameCallback((_) => store.markAllRead());

    return DraggableScrollableSheet(
      initialChildSize: 0.55,
      minChildSize: 0.35,
      maxChildSize: 0.85,
      expand: false,
      builder: (_, controller) => Column(
        children: [
          const SizedBox(height: 8),
          Container(
            width: 36,
            height: 4,
            decoration: BoxDecoration(
              color: theme.colorScheme.outlineVariant,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(height: 12),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                Text(
                  DriverCopy.get('notifications', locale),
                  style: theme.textTheme.titleMedium,
                ),
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
                        const Icon(PhosphorIconsRegular.bellSlash, size: 40),
                        const SizedBox(height: 8),
                        Text(
                          DriverCopy.get('no_notifications', locale),
                          style: theme.textTheme.bodySmall,
                        ),
                      ],
                    ),
                  )
                : ListView.separated(
                    controller: controller,
                    itemCount: notifications.length,
                    separatorBuilder: (_, __) => const Divider(height: 1, indent: 16),
                    itemBuilder: (_, i) {
                      final n = notifications[i];
                      final tColor = _typeColor(n.type, statusColors);
                      return ListTile(
                        leading: CircleAvatar(
                          backgroundColor: tColor.withValues(alpha: 0.15),
                          child: Icon(_typeIcon(n.type), size: 18, color: tColor),
                        ),
                        title: Text(n.title, style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600)),
                        subtitle: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            if (n.body.isNotEmpty)
                              Text(
                                n.body,
                                style: theme.textTheme.bodySmall,
                                maxLines: 4,
                                overflow: TextOverflow.ellipsis,
                              ),
                            Text(_formatTime(n.receivedAt, locale), style: theme.textTheme.labelSmall),
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
      case 'DELIVERY_ASSIGNED': return PhosphorIconsRegular.truck;
      case 'ROUTE_VALIDATED':   return PhosphorIconsRegular.mapTrifold;
      case 'ROUTE_UPDATED':     return PhosphorIconsRegular.path;
      case 'HANDOFF_REQUIRED':  return PhosphorIconsRegular.arrowsLeftRight;
      default:                  return PhosphorIconsRegular.bell;
    }
  }

  Color _typeColor(String type, StatusColors statusColors) {
    switch (type) {
      case 'DELIVERY_ASSIGNED': return statusColors.pickedUpText;
      case 'ROUTE_VALIDATED':   return statusColors.deliveredText;
      case 'ROUTE_UPDATED':     return statusColors.unscheduledText;
      case 'HANDOFF_REQUIRED':  return statusColors.partiallyDeliveredText;
      default:                  return statusColors.cancelledText;
    }
  }

  String _formatTime(DateTime dt, String locale) {
    final now = DateTime.now();
    final diff = now.difference(dt);
    if (locale == 'ar') {
      if (diff.inMinutes < 1) return 'الآن';
      if (diff.inMinutes < 60) return 'منذ ${diff.inMinutes} د';
      if (diff.inHours < 24) return 'منذ ${diff.inHours} س';
      return 'منذ ${diff.inDays} ي';
    } else if (locale == 'en') {
      if (diff.inMinutes < 1) return 'Just now';
      if (diff.inMinutes < 60) return '${diff.inMinutes}m ago';
      if (diff.inHours < 24) return '${diff.inHours}h ago';
      return '${diff.inDays}d ago';
    } else {
      if (diff.inMinutes < 1) return 'A l\'instant';
      if (diff.inMinutes < 60) return 'Il y a ${diff.inMinutes} min';
      if (diff.inHours < 24) return 'Il y a ${diff.inHours}h';
      return 'Il y a ${diff.inDays}j';
    }
  }
}

class _OfflineStatusBar extends ConsumerStatefulWidget {
  const _OfflineStatusBar();

  @override
  ConsumerState<_OfflineStatusBar> createState() => _OfflineStatusBarState();
}

class _OfflineStatusBarState extends ConsumerState<_OfflineStatusBar> {
  bool _wasOffline = false;
  bool _showSyncedBanner = false;
  Timer? _dismissTimer;

  @override
  void dispose() {
    _dismissTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final locale = ref.watch(localeProvider);
    
    final isOnlineAsync = ref.watch(connectionStatusProvider);
    final pendingCount = ref.watch(offlineQueueProvider);

    final isOnline = isOnlineAsync.value ?? true;

    if (isOnline && _wasOffline) {
      _wasOffline = false;
      _showSyncedBanner = true;
      _dismissTimer?.cancel();
      _dismissTimer = Timer(const Duration(seconds: 3), () {
        if (mounted) {
          setState(() {
            _showSyncedBanner = false;
          });
        }
      });
    } else if (!isOnline) {
      _wasOffline = true;
      _showSyncedBanner = false;
    }

    final double topPadding = MediaQuery.of(context).padding.top;

    if (!isOnline) {
      return AnimatedContainer(
        duration: const Duration(milliseconds: 300),
        width: double.infinity,
        padding: EdgeInsets.only(
          top: topPadding + 6,
          bottom: 8,
          left: 16,
          right: 16,
        ),
        color: const Color(0xFFF59E0B),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(PhosphorIconsRegular.cloudSlash, size: 14, color: Colors.white),
            const SizedBox(width: 8),
            Flexible(
              child: Text(
                pendingCount > 0
                    ? '${DriverCopy.get('offline_banner', locale)} · $pendingCount ${locale == 'ar' ? 'تعديلات معلقة' : locale == 'en' ? 'pending updates' : 'modifications en attente'}'
                    : DriverCopy.get('offline_banner', locale),
                style: GoogleFonts.inter(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  color: Colors.white,
                ),
                textAlign: TextAlign.center,
              ),
            ),
          ],
        ),
      );
    } else if (_showSyncedBanner) {
      return AnimatedContainer(
        duration: const Duration(milliseconds: 300),
        width: double.infinity,
        padding: EdgeInsets.only(
          top: topPadding + 6,
          bottom: 8,
          left: 16,
          right: 16,
        ),
        color: const Color(0xFF10B981),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(PhosphorIconsBold.cloudArrowUp, size: 14, color: Colors.white),
            const SizedBox(width: 8),
            Flexible(
              child: Text(
                locale == 'ar'
                    ? 'تم استعادة الاتصال · جاري المزامنة...'
                    : locale == 'en'
                        ? 'Connection restored · Syncing data...'
                        : 'Connexion rétablie · Synchronisation...',
                style: GoogleFonts.inter(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  color: Colors.white,
                ),
                textAlign: TextAlign.center,
              ),
            ),
          ],
        ),
      );
    }

    return const SizedBox.shrink();
  }
}
