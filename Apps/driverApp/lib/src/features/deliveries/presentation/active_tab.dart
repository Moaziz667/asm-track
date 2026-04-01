import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';
import 'delivery_detail_screen.dart';
import 'history_tab.dart';
import 'widgets/delivery_card.dart';

class ActiveDeliveriesTab extends ConsumerStatefulWidget {
  const ActiveDeliveriesTab({super.key});

  @override
  ConsumerState<ActiveDeliveriesTab> createState() => _ActiveDeliveriesTabState();
}

class _ActiveDeliveriesTabState extends ConsumerState<ActiveDeliveriesTab>
    with SingleTickerProviderStateMixin {
  late final TabController _tab;

  @override
  void initState() {
    super.initState();
    _tab = TabController(length: 2, vsync: this);
  }

  @override
  void dispose() {
    _tab.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        // ── Header ──────────────────────────────────────────────────────────
        Container(
          color: AppColors.navy,
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Deliveries',
                style: GoogleFonts.sora(fontSize: 22, fontWeight: FontWeight.w700, color: Colors.white, letterSpacing: -0.4),
              ),
              const SizedBox(height: 14),
              // Tab bar
              TabBar(
                controller: _tab,
                labelStyle: GoogleFonts.manrope(fontSize: 13, fontWeight: FontWeight.w700),
                unselectedLabelStyle: GoogleFonts.manrope(fontSize: 13, fontWeight: FontWeight.w600),
                labelColor: Colors.white,
                unselectedLabelColor: Colors.white.withValues(alpha: 0.45),
                indicatorColor: AppColors.accent,
                indicatorWeight: 2.5,
                indicatorSize: TabBarIndicatorSize.label,
                dividerColor: Colors.transparent,
                tabs: const [
                  Tab(text: 'Assigned'),
                  Tab(text: 'History'),
                ],
              ),
            ],
          ),
        ),

        // ── Content ─────────────────────────────────────────────────────────
        Expanded(
          child: TabBarView(
            controller: _tab,
            children: [
              _AssignedList(onRefresh: () => ref.invalidate(activeDeliveriesProvider)),
              const HistoryTab(),
            ],
          ),
        ),
      ],
    );
  }
}

class _AssignedList extends ConsumerWidget {
  const _AssignedList({required this.onRefresh});
  final VoidCallback onRefresh;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(activeDeliveriesProvider);
    return async.when(
      data: (deliveries) {
        if (deliveries.isEmpty) {
          return EmptyState(
            icon: PhosphorIconsRegular.package,
            title: 'No active deliveries',
            subtitle: 'Ops will assign deliveries once orders are confirmed.',
            action: onRefresh,
            actionLabel: 'Refresh',
          );
        }
        return RefreshIndicator(
          color: AppColors.accent,
          backgroundColor: AppColors.surface,
          onRefresh: () async => onRefresh(),
          child: ListView.separated(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 120),
            itemCount: deliveries.length,
            separatorBuilder: (_, __) => const SizedBox(height: 12),
            itemBuilder: (_, i) {
              final d = deliveries[i];
              return DeliveryCard(
                delivery: d,
                onTap: () => Navigator.of(context).pushNamed(
                  DeliveryDetailScreen.routeName,
                  arguments: DeliveryDetailArgs(deliveryId: d.id),
                ),
              );
            },
          ),
        );
      },
      loading: () => const LoadingState(message: 'Loading deliveries...'),
      error: (_, __) => EmptyState(
        icon: PhosphorIconsRegular.cloudSlash,
        title: 'Could not load deliveries',
        action: onRefresh,
        actionLabel: 'Retry',
      ),
    );
  }
}
