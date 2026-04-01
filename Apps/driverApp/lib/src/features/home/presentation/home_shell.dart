import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../../theme/app_theme.dart';
import '../../deliveries/models/delivery_models.dart';
import '../../deliveries/presentation/active_tab.dart';
import '../../profile/presentation/profile_screen.dart';
import '../../routes/models/route_models.dart';
import '../../routes/presentation/routes_screen.dart';
import 'dashboard_tab.dart';

class HomeShell extends ConsumerStatefulWidget {
  const HomeShell({super.key});
  static const routeName = '/home';

  @override
  ConsumerState<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends ConsumerState<HomeShell> {
  int _index = 0;
  Timer? _locationTimer;
  bool _isTracking = false;

  static const _navItems = [
    _NavItem(icon: PhosphorIconsFill.squaresFour,   label: 'Overview'),
    _NavItem(icon: PhosphorIconsFill.path,          label: 'Route'),
    _NavItem(icon: PhosphorIconsFill.package,       label: 'Deliveries'),
    _NavItem(icon: PhosphorIconsFill.userCircle,    label: 'Profile'),
  ];

  @override
  void dispose() {
    _locationTimer?.cancel();
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
      const SafeArea(child: DashboardTab()),
      const RoutesScreen(),
      const SafeArea(child: ActiveDeliveriesTab()),
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
        body: IndexedStack(index: _index, children: pages),
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
                          color: selected ? Colors.white : AppColors.muted,
                        ),
                        const SizedBox(height: 3),
                        Text(
                          item.label,
                          style: GoogleFonts.manrope(
                            fontSize: 10,
                            fontWeight: selected ? FontWeight.w800 : FontWeight.w600,
                            color: selected ? Colors.white : AppColors.muted,
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
