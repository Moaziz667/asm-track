import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';
import 'package:url_launcher/url_launcher_string.dart';

import '../../../app_providers.dart';
import '../../../services/locale_provider.dart';
import '../../../services/location_service.dart';
import '../../../theme/status_colors.dart';
import '../../../theme/widgets.dart';
import '../../../theme/swipe_button.dart';
import '../../pod/presentation/pod_form_screen.dart';
import '../models/delivery_models.dart';
import 'handoff_scanner_screen.dart';
import 'handoff_token_sheet.dart';

class DeliveryDetailArgs {
  const DeliveryDetailArgs({required this.deliveryId});
  final String deliveryId;
}

class DeliveryDetailScreen extends ConsumerStatefulWidget {
  const DeliveryDetailScreen({super.key, required this.args});

  static const routeName = '/delivery/detail';
  final DeliveryDetailArgs args;

  @override
  ConsumerState<DeliveryDetailScreen> createState() => _DeliveryDetailScreenState();
}

class _DeliveryDetailScreenState extends ConsumerState<DeliveryDetailScreen> {
  bool _isWorking = false;
  final _locationService = LocationService();

  Future<void> _refresh() async {
    ref.invalidate(deliveryDetailProvider(widget.args.deliveryId));
    ref.invalidate(activeDeliveriesProvider);
  }

  Future<void> _perform(Future<DriverDelivery> Function() task) async {
    final locale = ref.read(localeProvider);
    setState(() => _isWorking = true);
    try {
      await task();
      await _refresh();
    } catch (e) {
      if (e == 'OFFLINE_QUEUED') {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(DriverCopy.get('delivery_detail_offline_queue', locale))),
          );
        }
      } else {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('${DriverCopy.get('delivery_detail_error_prefix', locale)}: $e')),
          );
        }
      }
    } finally {
      if (mounted) setState(() => _isWorking = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final locale = ref.watch(localeProvider);
    final asyncDetail = ref.watch(deliveryDetailProvider(widget.args.deliveryId));
    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        backgroundColor: cs.surface,
        leading: IconButton(
          icon: const Icon(PhosphorIconsBold.caretLeft, size: 18),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: Text(DriverCopy.get('delivery_detail_title', locale)),
      ),
      body: asyncDetail.when(
        data: (delivery) => RefreshIndicator(
          color: cs.primary,
          backgroundColor: cs.surfaceContainerLow,
          onRefresh: _refresh,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 40),
            children: [
              _HeroCard(delivery: delivery),
              const SizedBox(height: 16),
              if (delivery.instructions != null && delivery.instructions!.isNotEmpty) ...[
                _InstructionsCard(text: delivery.instructions!),
                const SizedBox(height: 16),
              ],
              if (delivery.items.isNotEmpty) ...[
                _ItemsCard(items: delivery.items),
                const SizedBox(height: 16),
              ],
              _TimestampCard(delivery: delivery),
              const SizedBox(height: 16),
              _BonLivraisonCard(deliveryId: delivery.id),
              const SizedBox(height: 24),
              _ActionPanel(
                delivery: delivery,
                isWorking: _isWorking,
                currentDriverId: ref.watch(driverProfileProvider).value?.id,
                onScanHandoff: () async {
                  final result = await Navigator.of(context).push<bool>(
                    MaterialPageRoute(builder: (_) => const HandoffScannerScreen()),
                  );
                  if (result == true) await _refresh();
                },
                onPickup: () => _perform(() => ref.read(deliveryRepositoryProvider).pickup(delivery.id)),
                onTransit: () => _perform(() async {
                  final point = await _locationService.currentPosition();
                  return ref.read(deliveryRepositoryProvider).startTransit(
                        delivery.id,
                        lat: point?.lat,
                        lng: point?.lng,
                      );
                }),
                onFail: () async {
                  final reasons = await ref.read(deliveryRepositoryProvider).fetchFailureReasons();
                  if (!context.mounted) return;
                  final reason = await _showFailSheet(context, reasons);
                  if (reason == null) return;
                  await _perform(() => ref.read(deliveryRepositoryProvider).fail(
                        delivery.id,
                        reasonCode: reason.$1,
                        comment: reason.$2,
                      ));
                },
                onPod: () async {
                  final result = await Navigator.of(context).pushNamed(
                    PodFormScreen.routeName,
                    arguments: PodFormArgs(delivery: delivery),
                  );
                  if (result == true) await _refresh();
                },
              ),
            ],
          ),
        ),
        loading: () => LoadingState(message: DriverCopy.get('delivery_detail_loading', locale)),
        error: (error, _) {
          final isUnauthorized = error.toString().contains('403') || error.toString().contains('unauthorized');
          return EmptyState(
            icon: PhosphorIconsRegular.warningCircle,
            title: isUnauthorized 
                ? DriverCopy.get('delivery_detail_unauthorized_link', locale)
                : DriverCopy.get('delivery_detail_load_failed', locale),
            action: _refresh,
            actionLabel: DriverCopy.get('delivery_detail_retry', locale),
          );
        },
      ),
    );
  }

  Future<(String, String?)?> _showFailSheet(BuildContext context, List<FailureReasonOption> reasons) async {
    final cs = Theme.of(context).colorScheme;
    final locale = ref.read(localeProvider);
    final options = reasons.isNotEmpty ? reasons : FailureReasonOption.fallback;
    FailureReasonOption selected = options.first;
    final commentCtrl = TextEditingController();
    
    final confirmed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: cs.surfaceContainerHighest,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (context) {
        return StatefulBuilder(
          builder: (context, setModal) {
            return Padding(
              padding: EdgeInsets.fromLTRB(24, 24, 24, MediaQuery.of(context).viewInsets.bottom + 24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Center(
                    child: Container(
                      width: 40,
                      height: 4,
                      decoration: BoxDecoration(
                        color: cs.outlineVariant, 
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),
                  Text(
                    DriverCopy.get('delivery_detail_fail_report', locale), 
                    style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                          fontWeight: FontWeight.w700, 
                          color: cs.onSurface,
                        ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    DriverCopy.get('delivery_detail_fail_select', locale), 
                    style: TextStyle(color: cs.onSurfaceVariant, fontSize: 13),
                  ),
                  const SizedBox(height: 20),
                  ...options.map((reason) {
                    final isSelected = reason.code == selected.code;
                    return GestureDetector(
                      onTap: () => setModal(() => selected = reason),
                      child: Container(
                        margin: const EdgeInsets.only(bottom: 8),
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                        decoration: BoxDecoration(
                          color: isSelected ? cs.errorContainer : cs.surfaceContainerLow,
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(
                            color: isSelected ? cs.error.withValues(alpha: 0.4) : cs.outlineVariant,
                          ),
                        ),
                        child: Row(
                          children: [
                            Icon(
                              isSelected ? PhosphorIconsFill.radioButton : PhosphorIconsRegular.circle,
                              color: isSelected ? cs.error : cs.onSurfaceVariant,
                              size: 18,
                            ),
                            const SizedBox(width: 12),
                            Text(
                              reason.label,
                              style: TextStyle(
                                color: isSelected ? cs.onSurface : cs.onSurfaceVariant,
                                fontWeight: isSelected ? FontWeight.w600 : FontWeight.w400,
                                fontSize: 14,
                              ),
                            ),
                          ],
                        ),
                      ),
                    );
                  }),
                  const SizedBox(height: 12),
                  TextField(
                    controller: commentCtrl,
                    style: TextStyle(color: cs.onSurface),
                    decoration: InputDecoration(
                      hintText: DriverCopy.get('delivery_detail_comment_hint', locale),
                      prefixIcon: const Icon(PhosphorIconsRegular.notePencil, size: 18),
                    ),
                  ),
                  const SizedBox(height: 20),
                  SizedBox(
                    width: double.infinity,
                    child: TextButton.icon(
                      onPressed: () => Navigator.pop(context, true),
                      icon: const Icon(PhosphorIconsBold.flagPennant),
                      label: Text(DriverCopy.get('delivery_detail_fail_submit', locale)),
                      style: TextButton.styleFrom(
                        foregroundColor: cs.error,
                        padding: const EdgeInsets.symmetric(vertical: 14),
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        );
      },
    );
    if (confirmed == true) {
      return (selected.code, commentCtrl.text.trim().isEmpty ? null : commentCtrl.text.trim());
    }
    return null;
  }
}

// ─── Hero Card ───────────────────────────────────────────────────────────────
class _HeroCard extends ConsumerWidget {
  const _HeroCard({required this.delivery});
  final DriverDelivery delivery;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final locale = ref.watch(localeProvider);
    final statusColors = Theme.of(context).extension<StatusColors>()!;

    final Color statusColor = switch (delivery.status) {
      DeliveryStatus.unscheduled => statusColors.unscheduledText,
      DeliveryStatus.scheduled => statusColors.scheduledText,
      DeliveryStatus.pickedUp => statusColors.pickedUpText,
      DeliveryStatus.inTransit => statusColors.inTransitText,
      DeliveryStatus.delivered => statusColors.deliveredText,
      DeliveryStatus.partially_delivered => statusColors.partiallyDeliveredText,
      DeliveryStatus.failed => statusColors.failedText,
      DeliveryStatus.cancelled => statusColors.cancelledText,
    };

    return Card(
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            height: 4,
            color: statusColor,
          ),
          Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                      decoration: BoxDecoration(
                        color: statusColor.withValues(alpha: 0.09),
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: statusColor.withValues(alpha: 0.15)),
                      ),
                      child: Text(
                        delivery.status.label,
                        style: TextStyle(
                          fontSize: 10,
                          fontWeight: FontWeight.w700,
                          color: statusColor,
                        ),
                      ),
                    ),
                    const Spacer(),
                    if (delivery.orderRef != null)
                      Text(
                        delivery.orderRef!,
                        style: Theme.of(context).textTheme.labelLarge?.copyWith(
                              fontSize: 12,
                              fontWeight: FontWeight.w700,
                              color: cs.onSurfaceVariant,
                            ),
                      ),
                  ],
                ),
                const SizedBox(height: 16),
                if (delivery.clientName != null) ...[
                  Row(
                    children: [
                      Icon(PhosphorIconsRegular.user, size: 14, color: cs.onSurfaceVariant),
                      const SizedBox(width: 4),
                      Text(
                        delivery.clientName!, 
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                      ),
                      if (delivery.clientPhone != null) ...[
                        const SizedBox(width: 8),
                        GestureDetector(
                          onTap: () => launchUrlString('tel:${delivery.clientPhone}'),
                          child: Row(
                            children: [
                              Icon(PhosphorIconsRegular.phone, size: 13, color: cs.primary),
                              const SizedBox(width: 3),
                              Text(
                                delivery.clientPhone!, 
                                style: TextStyle(fontSize: 13, color: cs.primary, fontWeight: FontWeight.w500),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: 8),
                ],
                Text(
                  delivery.address ?? 'Aucune adresse fournie',
                  style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w700,
                        color: cs.onSurface,
                      ),
                ),
                if (delivery.city != null) ...[
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      Icon(PhosphorIconsRegular.mapPin, size: 14, color: cs.onSurfaceVariant),
                      const SizedBox(width: 4),
                      Text(
                        delivery.city!, 
                        style: TextStyle(fontSize: 14, color: cs.onSurfaceVariant),
                      ),
                    ],
                  ),
                ],
                const SizedBox(height: 16),
                Divider(color: cs.outlineVariant, height: 1),
                const SizedBox(height: 16),
                Row(
                  children: [
                    _StatBox(
                      label: DriverCopy.get('delivery_detail_articles', locale),
                      value: '${delivery.items.length}',
                    ),
                    if (delivery.scheduledAt != null) ...[
                      const SizedBox(width: 12),
                      _StatBox(
                        label: DriverCopy.get('delivery_detail_scheduled', locale),
                        value: _fmtDate(delivery.scheduledAt!),
                      ),
                    ],
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _fmtDate(DateTime dt) {
    final d = dt.toLocal();
    return '${d.day}/${d.month} ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
  }
}

class _StatBox extends StatelessWidget {
  const _StatBox({required this.label, required this.value});
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Expanded(
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: cs.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: cs.outlineVariant),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label, 
              style: TextStyle(fontSize: 10, color: cs.onSurfaceVariant, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 4),
            Text(
              value, 
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: cs.onSurface),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Instructions ────────────────────────────────────────────────────────────
class _InstructionsCard extends StatelessWidget {
  const _InstructionsCard({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: cs.tertiaryContainer,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: cs.tertiary.withValues(alpha: 0.25)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(PhosphorIconsRegular.info, color: cs.tertiary, size: 18),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              text, 
              style: TextStyle(fontSize: 14, color: cs.onSurfaceVariant, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }
}

// ─── Items ───────────────────────────────────────────────────────────────────
class _ItemsCard extends ConsumerWidget {
  const _ItemsCard({required this.items});
  final List<OrderItemModel> items;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final locale = ref.watch(localeProvider);
    final countText = '${items.length} ${locale == 'ar' ? 'سلعة' : locale == 'en' ? 'item${items.length != 1 ? 's' : ''}' : 'article${items.length != 1 ? 's' : ''}'}';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SectionHeader(
              title: DriverCopy.get('delivery_detail_content', locale), 
              subtitle: countText,
            ),
            const SizedBox(height: 14),
            ...items.asMap().entries.map((e) {
              final isLast = e.key == items.length - 1;
              return Column(
                children: [
                  Row(
                    children: [
                      Container(
                        width: 32,
                        height: 32,
                        decoration: BoxDecoration(
                          color: cs.surfaceContainerHighest,
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(color: cs.outlineVariant),
                        ),
                        child: Icon(PhosphorIconsRegular.package, size: 14, color: cs.onSurfaceVariant),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          e.value.name, 
                          style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: cs.onSurface),
                        ),
                      ),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                        decoration: BoxDecoration(
                          color: cs.surfaceContainerHighest,
                          borderRadius: BorderRadius.circular(6),
                          border: Border.all(color: cs.outlineVariant),
                        ),
                        child: Text(
                          'x${e.value.quantity}',
                          style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: cs.primary),
                        ),
                      ),
                    ],
                  ),
                  if (!isLast) ...[
                    const SizedBox(height: 10),
                    Divider(color: cs.outlineVariant, height: 1),
                    const SizedBox(height: 10),
                  ],
                ],
              );
            }),
          ],
        ),
      ),
    );
  }
}

// ─── Timestamps ───────────────────────────────────────────────────────────────
class _TimestampCard extends ConsumerWidget {
  const _TimestampCard({required this.delivery});
  final DriverDelivery delivery;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final locale = ref.watch(localeProvider);
    final entries = delivery.timestamps.entries
        .where((e) => e.value != null)
        .map((e) => (label: _keyLabel(e.key, locale), time: e.value!))
        .toList()
      ..sort((a, b) => a.time.compareTo(b.time));

    if (entries.isEmpty) return const SizedBox.shrink();

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SectionHeader(title: DriverCopy.get('delivery_detail_timeline', locale)),
            const SizedBox(height: 14),
            ...entries.map((e) {
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(
                  children: [
                    Icon(PhosphorIconsFill.circle, size: 6, color: cs.primary),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(e.label, style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant)),
                    ),
                    Text(
                      _fmtTs(e.time),
                      style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant, fontWeight: FontWeight.w500),
                    ),
                  ],
                ),
              );
            }),
          ],
        ),
      ),
    );
  }

  String _keyLabel(String key, String locale) {
    switch (key) {
      case 'scheduledAt': return DriverCopy.get('delivery_detail_ts_scheduled', locale);
      case 'pickedUpAt': return DriverCopy.get('delivery_detail_ts_picked_up', locale);
      case 'inTransitAt': return DriverCopy.get('delivery_detail_ts_in_transit', locale);
      case 'completedAt': return DriverCopy.get('delivery_detail_ts_delivered', locale);
      case 'failedAt': return DriverCopy.get('delivery_detail_ts_failed', locale);
      case 'cancelledAt': return DriverCopy.get('delivery_detail_ts_cancelled', locale);
      case 'createdAt': return DriverCopy.get('delivery_detail_ts_created', locale);
      default: return key;
    }
  }

  String _fmtTs(DateTime dt) {
    final d = dt.toLocal();
    return '${d.day}/${d.month} ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
  }
}

// ─── Action Panel ─────────────────────────────────────────────────────────────
class _ActionPanel extends ConsumerWidget {
  const _ActionPanel({
    required this.delivery,
    required this.isWorking,
    required this.onPickup,
    required this.onTransit,
    required this.onFail,
    required this.onPod,
    this.currentDriverId,
    this.onScanHandoff,
  });

  final DriverDelivery delivery;
  final bool isWorking;
  final Future<void> Function() onPickup;
  final Future<void> Function() onTransit;
  final Future<void> Function() onFail;
  final Future<void> Function() onPod;
  final String? currentDriverId;
  final Future<void> Function()? onScanHandoff;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final locale = ref.watch(localeProvider);

    // Handoff: the receiver (handoffToDriverId) is invited to scan the sender's QR,
    // but this is non-blocking — they can keep working their current stop. We surface
    // it as a banner above the normal actions instead of replacing them.
    final bool showHandoffBanner = delivery.requiresHandoff &&
        delivery.handoffConfirmedAt == null &&
        currentDriverId != null &&
        delivery.handoffToDriverId == currentDriverId;

    final buttons = <Widget>[];

    Future<void> launchNav() async {
      final lat = delivery.lat;
      final lng = delivery.lng;
      if (lat == null || lng == null) return;
      final googleUrl = 'https://www.google.com/maps/dir/?api=1&destination=$lat,$lng';
      try {
        await launchUrlString(googleUrl, mode: LaunchMode.externalApplication);
      } catch (_) {
        final geoUrl = 'geo:$lat,$lng?q=$lat,$lng';
        try {
          await launchUrlString(geoUrl, mode: LaunchMode.externalApplication);
        } catch (_) {}
      }
    }

    final hasGeo = delivery.lat != null && delivery.lng != null;

    switch (delivery.status) {
      case DeliveryStatus.unscheduled:
        buttons.add(
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(PhosphorIconsRegular.info, size: 16, color: cs.onSurfaceVariant),
                  const SizedBox(width: 8),
                  Text(
                    DriverCopy.get('delivery_detail_pending_dispatch', locale),
                    style: TextStyle(color: cs.onSurfaceVariant, fontSize: 13),
                  ),
                ],
              ),
            ),
          )
        );
        break;
      case DeliveryStatus.scheduled:
        buttons.addAll([
          SwipeButton(
            label: DriverCopy.get('delivery_detail_pickup_package', locale),
            onSwipe: isWorking ? null : onPickup,
            isWorking: isWorking,
            icon: PhosphorIconsBold.package,
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: TextButton.icon(
              onPressed: isWorking ? null : onFail,
              icon: const Icon(PhosphorIconsBold.flagPennant),
              label: Text(DriverCopy.get('delivery_detail_fail_report', locale)),
            ),
          ),
        ]);
        break;
      case DeliveryStatus.pickedUp:
        if (hasGeo) {
          buttons.add(
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: launchNav,
                icon: const Icon(PhosphorIconsBold.navigationArrow),
                label: Text(DriverCopy.get('delivery_detail_navigate', locale)),
              ),
            ),
          );
          buttons.add(const SizedBox(height: 10));
        }
        buttons.addAll([
          SwipeButton(
            label: DriverCopy.get('delivery_detail_start_transit', locale),
            onSwipe: isWorking ? null : onTransit,
            isWorking: isWorking,
            icon: PhosphorIconsBold.steeringWheel,
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: TextButton.icon(
              onPressed: isWorking ? null : onFail,
              icon: const Icon(PhosphorIconsBold.flagPennant),
              label: Text(DriverCopy.get('delivery_detail_fail_report', locale)),
            ),
          ),
          if (delivery.requiresHandoff && delivery.handoffConfirmedAt == null) ...[
            const SizedBox(height: 10),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: () => showModalBottomSheet(
                  context: context,
                  isScrollControlled: true,
                  builder: (_) => HandoffTokenSheet(deliveryId: delivery.id),
                ),
                icon: const Icon(PhosphorIconsBold.qrCode),
                label: Text(DriverCopy.get('delivery_detail_generate_handoff', locale)),
              ),
            ),
          ],
        ]);
        break;
      case DeliveryStatus.inTransit:
        if (hasGeo) {
          buttons.add(
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: launchNav,
                icon: const Icon(PhosphorIconsBold.navigationArrow),
                label: Text(DriverCopy.get('delivery_detail_navigate', locale)),
              ),
            ),
          );
          buttons.add(const SizedBox(height: 10));
        }
        buttons.addAll([
          SwipeButton(
            label: DriverCopy.get('delivery_detail_submit_pod', locale),
            onSwipe: isWorking ? null : onPod,
            isWorking: isWorking,
            icon: PhosphorIconsBold.sealCheck,
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: TextButton.icon(
              onPressed: isWorking ? null : onFail,
              icon: const Icon(PhosphorIconsBold.flagPennant),
              label: Text(DriverCopy.get('delivery_detail_fail_report', locale)),
              style: TextButton.styleFrom(foregroundColor: cs.error),
            ),
          ),
          if (delivery.requiresHandoff && delivery.handoffConfirmedAt == null) ...[
            const SizedBox(height: 10),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: () => showModalBottomSheet(
                  context: context,
                  isScrollControlled: true,
                  builder: (_) => HandoffTokenSheet(deliveryId: delivery.id),
                ),
                icon: const Icon(PhosphorIconsBold.qrCode),
                label: Text(DriverCopy.get('delivery_detail_generate_handoff', locale)),
              ),
            ),
          ],
        ]);
        break;
      case DeliveryStatus.delivered:
      case DeliveryStatus.partially_delivered:
      case DeliveryStatus.failed:
      case DeliveryStatus.cancelled:
        buttons.add(
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(PhosphorIconsFill.lock, size: 16, color: cs.onSurfaceVariant),
                  const SizedBox(width: 8),
                  Text(
                    DriverCopy.get('delivery_detail_locked', locale),
                    style: TextStyle(color: cs.onSurfaceVariant, fontSize: 13),
                  ),
                ],
              ),
            ),
          )
        );
        break;
    }

    if (buttons.isEmpty && !showHandoffBanner) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (showHandoffBanner) ...[
          _HandoffBanner(onScan: onScanHandoff),
          const SizedBox(height: 12),
        ],
        ...buttons,
      ],
    );
  }
}

// ─── Bon de Livraison Card ────────────────────────────────────────────────────
class _BonLivraisonCard extends ConsumerStatefulWidget {
  const _BonLivraisonCard({required this.deliveryId});
  final String deliveryId;

  @override
  ConsumerState<_BonLivraisonCard> createState() => _BonLivraisonCardState();
}

class _BonLivraisonCardState extends ConsumerState<_BonLivraisonCard> {
  bool _loading = false;

  Future<void> _open() async {
    final locale = ref.read(localeProvider);
    if (_loading) return;

    final isOnline = await ref.read(connectivityServiceProvider).isOnline;
    if (!isOnline) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Non disponible hors ligne')),
        );
      }
      return;
    }

    setState(() => _loading = true);
    try {
      final ok = await ref.read(pdfServiceProvider).downloadAndOpen(
        '/api/driver/deliveries/${widget.deliveryId}/bon-livraison',
        fileName: 'bon-livraison-${widget.deliveryId}.pdf',
      );
      if (!ok && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(DriverCopy.get('pod_pdf_open_error', locale))),
        );
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(DriverCopy.get('pod_pdf_download_error', locale))),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final locale = ref.watch(localeProvider);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: cs.tertiary.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(PhosphorIconsRegular.filePdf, color: cs.tertiary, size: 20),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    DriverCopy.get('pod_view_print_bl', locale),
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w700,
                          color: cs.onSurface,
                        ),
                  ),
                ],
              ),
            ),
            TextButton(
              onPressed: _loading ? null : _open,
              child: _loading
                  ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(locale == 'ar' ? 'عرض' : locale == 'en' ? 'Open' : 'Ouvrir'),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Section Header ───────────────────────────────────────────────────────────
class SectionHeader extends StatelessWidget {
  const SectionHeader({super.key, required this.title, this.subtitle});
  final String title;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: Theme.of(context).textTheme.titleMedium?.copyWith(color: cs.onSurface, fontWeight: FontWeight.bold)),
        if (subtitle != null) ...[
          const SizedBox(height: 2),
          Text(subtitle!, style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
        ],
      ],
    );
  }
}

// ─── Handoff Banner (non-blocking) ────────────────────────────────────────────
// Informs the receiving driver that a package was transferred to them and offers
// a "scan QR" action — but does NOT block the rest of the stop's actions.
class _HandoffBanner extends ConsumerWidget {
  const _HandoffBanner({this.onScan});
  final Future<void> Function()? onScan;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final locale = ref.watch(localeProvider);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: cs.secondary.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: cs.secondary.withValues(alpha: 0.35)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(PhosphorIconsRegular.package, color: cs.secondary, size: 20),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      locale == 'ar'
                          ? 'طرد محوّل إليك'
                          : locale == 'en'
                              ? 'Package transferred to you'
                              : 'Colis transféré vers vous',
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                            fontSize: 13,
                            fontWeight: FontWeight.w800,
                            color: cs.secondary,
                          ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      locale == 'ar'
                          ? 'عند الاستلام، امسح رمز QR الخاص بالسائق المرسل لتأكيد الاستلام. يمكنك متابعة عملك في هذه الأثناء.'
                          : locale == 'en'
                              ? 'When you receive it, scan the sender driver\'s QR to confirm. You can keep working in the meantime.'
                              : 'À la réception, scannez le QR du chauffeur expéditeur pour confirmer. Vous pouvez continuer votre travail entre-temps.',
                      style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant, height: 1.4),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: onScan,
              icon: const Icon(PhosphorIconsBold.qrCode, size: 18),
              label: Text(
                locale == 'ar'
                    ? 'امسح رمز المرسل'
                    : locale == 'en'
                        ? 'Scan sender\'s QR'
                        : 'Scanner le QR de l\'expéditeur',
              ),
            ),
          ),
        ],
      ),
    );
  }
}
