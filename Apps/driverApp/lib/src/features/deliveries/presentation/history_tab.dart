import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';
import '../models/delivery_models.dart';
import 'delivery_detail_screen.dart';

class HistoryTab extends ConsumerStatefulWidget {
  const HistoryTab({super.key});

  @override
  ConsumerState<HistoryTab> createState() => _HistoryTabState();
}

class _HistoryTabState extends ConsumerState<HistoryTab> {
  _HistoryFilter _filter = _HistoryFilter.all;

  @override
  Widget build(BuildContext context) {
    final historyAsync = ref.watch(driverHistoryProvider);
    return RefreshIndicator(
      color: AppColors.accent,
      backgroundColor: AppColors.surface,
      onRefresh: () async => ref.invalidate(driverHistoryProvider),
      child: historyAsync.when(
        data: (history) {
          final filtered = history.where((d) => _filter.matches(d.status)).toList();
          return ListView.builder(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 120),
            itemCount: filtered.isEmpty ? 2 : filtered.length + 1,
            itemBuilder: (context, index) {
              if (index == 0) {
                return _ArchiveHeader(
                  total: filtered.length,
                  filter: _filter,
                  onFilterChanged: (v) => setState(() => _filter = v),
                );
              }
              if (filtered.isEmpty) {
                return EmptyState(
                    icon: PhosphorIconsRegular.package,
                  title: 'No records yet',
                  subtitle: 'Completed deliveries will appear here.',
                );
              }
              final delivery = filtered[index - 1];
              return Padding(
                padding: const EdgeInsets.only(top: 12),
                child: _HistoryTile(delivery: delivery),
              );
            },
          );
        },
        loading: () => const LoadingState(message: 'Loading archive…'),
        error: (_, __) => EmptyState(
          icon: PhosphorIconsRegular.cloudSlash,
          title: 'History offline',
          action: () => ref.invalidate(driverHistoryProvider),
          actionLabel: 'Retry',
        ),
      ),
    );
  }
}

class _ArchiveHeader extends StatelessWidget {
  const _ArchiveHeader({
    required this.total,
    required this.filter,
    required this.onFilterChanged,
  });

  final int total;
  final _HistoryFilter filter;
  final ValueChanged<_HistoryFilter> onFilterChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(0, 20, 0, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
                'Mission Archive',
                style: GoogleFonts.sora(fontSize: 20, fontWeight: FontWeight.w700, color: AppColors.textPrimary),
              ),
              const Spacer(),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: AppColors.border),
                ),
                child: Text(
                  '$total records',
                  style: const TextStyle(fontSize: 12, color: AppColors.muted, fontWeight: FontWeight.w500),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: _HistoryFilter.values.map((f) {
                final isSelected = f == filter;
                return Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: GestureDetector(
                    onTap: () => onFilterChanged(f),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 150),
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                      decoration: BoxDecoration(
                        color: isSelected ? AppColors.accent : AppColors.surface,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(
                          color: isSelected ? AppColors.accent : AppColors.border,
                        ),
                      ),
                      child: Text(
                        f.label,
                        style: GoogleFonts.manrope(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                          color: isSelected ? Colors.white : AppColors.muted,
                        ),
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
        ],
      ),
    );
  }
}

class _HistoryTile extends StatelessWidget {
  _HistoryTile({required this.delivery}) : _fmt = DateFormat('MMM d · HH:mm');

  final DriverDelivery delivery;
  final DateFormat _fmt;

  @override
  Widget build(BuildContext context) {
    final statusColor = delivery.status.badgeColor;
    final timestamp = delivery.timestamps['completedAt'] ??
        delivery.timestamps['failedAt'] ??
        delivery.timestamps['cancelledAt'] ??
        delivery.timestamps['createdAt'];

    return GestureDetector(
      onTap: () => Navigator.of(context).pushNamed(
        DeliveryDetailScreen.routeName,
        arguments: DeliveryDetailArgs(deliveryId: delivery.id),
      ),
      child: Container(
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: AppColors.border),
        ),
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: statusColor.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(
                        width: 6,
                        height: 6,
                        decoration: BoxDecoration(color: statusColor, shape: BoxShape.circle),
                      ),
                      const SizedBox(width: 5),
                      Text(
                        delivery.status.label.toUpperCase(),
                        style: GoogleFonts.manrope(
                          fontSize: 10,
                          fontWeight: FontWeight.w700,
                          color: statusColor,
                        ),
                      ),
                    ],
                  ),
                ),
                const Spacer(),
                if (timestamp != null)
                  Text(
                    _fmt.format(timestamp),
                    style: const TextStyle(fontSize: 12, color: AppColors.muted),
                  ),
              ],
            ),
            const SizedBox(height: 10),
            Text(
              delivery.address ?? 'No address',
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.textPrimary),
            ),
            if (delivery.city != null) ...[
              const SizedBox(height: 2),
              Text(delivery.city!, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
            ],
            const SizedBox(height: 12),
            Row(
              children: [
                _HistoryStat(label: 'Order', value: delivery.orderId ?? delivery.id.substring(0, 8)),
                _HistoryStat(label: 'Items', value: '${delivery.items.length}'),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _HistoryStat extends StatelessWidget {
  const _HistoryStat({required this.label, required this.value, this.valueColor});
  final String label;
  final String value;
  final Color? valueColor;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: const TextStyle(fontSize: 10, color: AppColors.muted, fontWeight: FontWeight.w500)),
          const SizedBox(height: 2),
          Text(
            value,
            style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: valueColor ?? AppColors.textPrimary),
            overflow: TextOverflow.ellipsis,
          ),
        ],
      ),
    );
  }
}

enum _HistoryFilter { all, delivered, failed, cancelled }

extension on _HistoryFilter {
  String get label {
    switch (this) {
      case _HistoryFilter.all:
        return 'All';
      case _HistoryFilter.delivered:
        return 'Delivered';
      case _HistoryFilter.failed:
        return 'Failed';
      case _HistoryFilter.cancelled:
        return 'Cancelled';
    }
  }

  bool matches(DeliveryStatus status) {
    switch (this) {
      case _HistoryFilter.all:
        return true;
      case _HistoryFilter.delivered:
        return status == DeliveryStatus.delivered;
      case _HistoryFilter.failed:
        return status == DeliveryStatus.failed;
      case _HistoryFilter.cancelled:
        return status == DeliveryStatus.cancelled;
    }
  }
}
