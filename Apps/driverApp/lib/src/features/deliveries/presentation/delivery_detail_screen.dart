import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';
import '../../pod/presentation/pod_form_screen.dart';
import '../models/delivery_models.dart';

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
    setState(() => _isWorking = true);
    try {
      await task();
      await _refresh();
    } finally {
      if (mounted) setState(() => _isWorking = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final asyncDetail = ref.watch(deliveryDetailProvider(widget.args.deliveryId));
    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        backgroundColor: AppColors.background,
        leading: IconButton(
          icon: const Icon(PhosphorIconsBold.caretLeft, size: 18),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: const Text('Delivery Detail'),
      ),
      body: asyncDetail.when(
        data: (delivery) => RefreshIndicator(
          color: AppColors.accent,
          backgroundColor: AppColors.surface,
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
              const SizedBox(height: 24),
              _ActionPanel(
                delivery: delivery,
                isWorking: _isWorking,
                onAccept: () => _perform(() => ref.read(deliveryRepositoryProvider).accept(delivery.id)),
                onPickup: () => _perform(() => ref.read(deliveryRepositoryProvider).pickup(delivery.id)),
                onTransit: () => _perform(() async {
                  final point = await _locationService.currentPosition();
                  return ref.read(deliveryRepositoryProvider).startTransit(
                        delivery.id,
                        lat: point?.lat,
                        lng: point?.lng,
                      );
                }),
                onComplete: () => _perform(() => ref.read(deliveryRepositoryProvider).complete(delivery.id)),
                onFail: () async {
                  final reason = await _showFailSheet(context);
                  if (reason == null) return;
                  await _perform(() => ref.read(deliveryRepositoryProvider).fail(
                        delivery.id,
                        reason: reason.$1,
                        comment: reason.$2,
                      ));
                },
                onCancel: () async {
                  final confirm = await _confirmDialog(context, 'Cancel assignment?', 'This will release the package back to the pool.');
                  if (confirm != true) return;
                  await _perform(() => ref.read(deliveryRepositoryProvider).cancel(delivery.id));
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
        loading: () => const LoadingState(message: 'Loading delivery…'),
        error: (_, __) => EmptyState(
          icon: PhosphorIconsRegular.warningCircle,
          title: 'Failed to load',
          action: _refresh,
          actionLabel: 'Retry',
        ),
      ),
    );
  }

  Future<(FailureReason, String?)?> _showFailSheet(BuildContext context) async {
    FailureReason selected = FailureReason.clientAbsent;
    final commentCtrl = TextEditingController();
    final confirmed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surfaceElevated,
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
                      decoration: BoxDecoration(color: AppColors.border, borderRadius: BorderRadius.circular(2)),
                    ),
                  ),
                  const SizedBox(height: 20),
                  Text('Report Failure', style: GoogleFonts.plusJakartaSans(fontSize: 20, fontWeight: FontWeight.w700, color: AppColors.textPrimary)),
                  const SizedBox(height: 4),
                  const Text('Select the reason for this delivery failure.', style: TextStyle(color: AppColors.muted, fontSize: 13)),
                  const SizedBox(height: 20),
                  ...FailureReason.values.map((reason) {
                    final isSelected = reason == selected;
                    return GestureDetector(
                      onTap: () => setModal(() => selected = reason),
                      child: Container(
                        margin: const EdgeInsets.only(bottom: 8),
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                        decoration: BoxDecoration(
                          color: isSelected ? AppColors.dangerSubtle : AppColors.surface,
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(
                            color: isSelected ? AppColors.danger.withValues(alpha: 0.4) : AppColors.border,
                          ),
                        ),
                        child: Row(
                          children: [
                            Icon(
                              isSelected ? PhosphorIconsFill.radioButton : PhosphorIconsRegular.circle,
                              color: isSelected ? AppColors.danger : AppColors.muted,
                              size: 18,
                            ),
                            const SizedBox(width: 12),
                            Text(
                              reason.label,
                              style: TextStyle(
                                color: isSelected ? AppColors.textPrimary : AppColors.textSecondary,
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
                    style: const TextStyle(color: AppColors.textPrimary),
                    decoration: const InputDecoration(
                      hintText: 'Additional comment (optional)',
                      prefixIcon: Icon(PhosphorIconsRegular.notePencil, size: 18),
                    ),
                  ),
                  const SizedBox(height: 20),
                  SizedBox(
                    width: double.infinity,
                    child: AsmDriveButton(
                      label: 'Submit Failure Report',
                      variant: AsmDriveButtonVariant.danger,
                      icon: PhosphorIconsBold.flagPennant,
                      onPressed: () => Navigator.pop(context, true),
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
      return (selected, commentCtrl.text.trim().isEmpty ? null : commentCtrl.text.trim());
    }
    return null;
  }

  Future<bool?> _confirmDialog(BuildContext context, String title, String message) {
    return showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(title),
        content: Text(message, style: const TextStyle(color: AppColors.muted)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Keep', style: TextStyle(color: AppColors.muted)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Confirm', style: TextStyle(color: AppColors.danger)),
          ),
        ],
      ),
    );
  }
}

// ─── Hero Card ───────────────────────────────────────────────────────────────
class _HeroCard extends StatelessWidget {
  const _HeroCard({required this.delivery});
  final DriverDelivery delivery;

  @override
  Widget build(BuildContext context) {
    final statusColor = delivery.status.badgeColor;
    return Container(
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppColors.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Color bar
          Container(
            height: 4,
            decoration: BoxDecoration(
              color: statusColor,
              borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
            ),
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
                        color: statusColor.withValues(alpha: 0.15),
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: statusColor.withValues(alpha: 0.3)),
                      ),
                      child: Text(
                        delivery.status.label.toUpperCase(),
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 10,
                          fontWeight: FontWeight.w700,
                          letterSpacing: 0.8,
                          color: statusColor,
                        ),
                      ),
                    ),
                    const Spacer(),
                    if (delivery.orderId != null)
                      Text(
                        '#${delivery.orderId}',
                        style: const TextStyle(fontSize: 13, color: AppColors.muted, fontWeight: FontWeight.w500),
                      ),
                  ],
                ),
                const SizedBox(height: 16),
                Text(
                  delivery.address ?? 'No address provided',
                  style: GoogleFonts.plusJakartaSans(fontSize: 20, fontWeight: FontWeight.w700, color: AppColors.textPrimary),
                ),
                if (delivery.city != null) ...[
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      const Icon(Icons.location_on_outlined, size: 14, color: AppColors.muted),
                      const SizedBox(width: 4),
                      Text(delivery.city!, style: const TextStyle(fontSize: 14, color: AppColors.muted)),
                    ],
                  ),
                ],
                const SizedBox(height: 16),
                const Divider(color: AppColors.border, height: 1),
                const SizedBox(height: 16),
                Row(
                  children: [
                    _StatBox(
                      label: 'ITEMS',
                      value: '${delivery.items.length}',
                    ),
                    if (delivery.scheduledAt != null) ...[
                      const SizedBox(width: 12),
                      _StatBox(
                        label: 'SCHEDULED',
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
    return Expanded(
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: AppColors.surfaceElevated,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.border),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: const TextStyle(fontSize: 10, color: AppColors.muted, fontWeight: FontWeight.w600, letterSpacing: 0.8)),
            const SizedBox(height: 4),
            Text(value, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.textPrimary)),
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
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.infoSubtle,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.info.withValues(alpha: 0.25)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.info_outline_rounded, color: AppColors.info, size: 18),
          const SizedBox(width: 12),
          Expanded(
            child: Text(text, style: const TextStyle(fontSize: 14, color: AppColors.textSecondary, height: 1.5)),
          ),
        ],
      ),
    );
  }
}

// ─── Items ───────────────────────────────────────────────────────────────────
class _ItemsCard extends StatelessWidget {
  const _ItemsCard({required this.items});
  final List<OrderItemModel> items;

  @override
  Widget build(BuildContext context) {
    return AsmDriveCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionHeader(title: 'Package Contents', subtitle: '${items.length} item${items.length != 1 ? 's' : ''}'),
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
                        color: AppColors.surfaceElevated,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: AppColors.border),
                      ),
                      child: const Icon(Icons.inventory_2_outlined, size: 14, color: AppColors.muted),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(e.value.name, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: AppColors.textPrimary)),
                    ),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                      decoration: BoxDecoration(
                        color: AppColors.surfaceElevated,
                        borderRadius: BorderRadius.circular(6),
                        border: Border.all(color: AppColors.border),
                      ),
                      child: Text(
                        'x${e.value.quantity}',
                        style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.accent),
                      ),
                    ),
                  ],
                ),
                if (!isLast) ...[
                  const SizedBox(height: 10),
                  const Divider(color: AppColors.border, height: 1),
                  const SizedBox(height: 10),
                ],
              ],
            );
          }),
        ],
      ),
    );
  }
}

// ─── Timestamps ───────────────────────────────────────────────────────────────
class _TimestampCard extends StatelessWidget {
  const _TimestampCard({required this.delivery});
  final DriverDelivery delivery;

  @override
  Widget build(BuildContext context) {
    final entries = delivery.timestamps.entries
        .where((e) => e.value != null)
        .map((e) => (label: _keyLabel(e.key), time: e.value!))
        .toList()
      ..sort((a, b) => a.time.compareTo(b.time));

    if (entries.isEmpty) return const SizedBox.shrink();

    return AsmDriveCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionHeader(title: 'Timeline'),
          const SizedBox(height: 14),
          ...entries.map((e) {
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: 5),
              child: Row(
                children: [
                  const Icon(Icons.circle, size: 6, color: AppColors.accent),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(e.label, style: const TextStyle(fontSize: 13, color: AppColors.textSecondary)),
                  ),
                  Text(
                    _fmtTs(e.time),
                    style: const TextStyle(fontSize: 12, color: AppColors.muted, fontWeight: FontWeight.w500),
                  ),
                ],
              ),
            );
          }),
        ],
      ),
    );
  }

  String _keyLabel(String key) {
    switch (key) {
      case 'assignedAt': return 'Assigned';
      case 'pickedUpAt': return 'Picked up';
      case 'inTransitAt': return 'In transit';
      case 'completedAt': return 'Delivered';
      case 'failedAt': return 'Failed';
      case 'cancelledAt': return 'Cancelled';
      case 'createdAt': return 'Created';
      default: return key;
    }
  }

  String _fmtTs(DateTime dt) {
    final d = dt.toLocal();
    return '${d.day}/${d.month} ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
  }
}

// ─── Action Panel ─────────────────────────────────────────────────────────────
class _ActionPanel extends StatelessWidget {
  const _ActionPanel({
    required this.delivery,
    required this.isWorking,
    required this.onAccept,
    required this.onPickup,
    required this.onTransit,
    required this.onComplete,
    required this.onFail,
    required this.onCancel,
    required this.onPod,
  });

  final DriverDelivery delivery;
  final bool isWorking;
  final Future<void> Function() onAccept;
  final Future<void> Function() onPickup;
  final Future<void> Function() onTransit;
  final Future<void> Function() onComplete;
  final Future<void> Function() onFail;
  final Future<void> Function() onCancel;
  final Future<void> Function() onPod;

  @override
  Widget build(BuildContext context) {
    final buttons = <Widget>[];

    switch (delivery.status) {
      case DeliveryStatus.waitingDriver:
        buttons.add(SizedBox(
          width: double.infinity,
          child: AsmDriveButton(
            label: 'Accept Mission',
              icon: PhosphorIconsBold.checkCircle,
            variant: AsmDriveButtonVariant.success,
            isLoading: isWorking,
            onPressed: isWorking ? null : onAccept,
          ),
        ));
        break;
      case DeliveryStatus.assigned:
        buttons.addAll([
          SizedBox(
            width: double.infinity,
            child: AsmDriveButton(
              label: 'Mark Picked Up',
              icon: PhosphorIconsBold.package,
              isLoading: isWorking,
              onPressed: isWorking ? null : onPickup,
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: AsmDriveButton(
              label: 'Cancel Assignment',
              icon: PhosphorIconsBold.x,
              variant: AsmDriveButtonVariant.ghost,
              onPressed: isWorking ? null : onCancel,
            ),
          ),
        ]);
        break;
      case DeliveryStatus.pickedUp:
        buttons.addAll([
          SizedBox(
            width: double.infinity,
            child: AsmDriveButton(
              label: 'Start Transit',
              icon: PhosphorIconsBold.steeringWheel,
              isLoading: isWorking,
              onPressed: isWorking ? null : onTransit,
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: AsmDriveButton(
              label: 'Report Failure',
              icon: PhosphorIconsBold.flagPennant,
              variant: AsmDriveButtonVariant.ghost,
              onPressed: isWorking ? null : onFail,
            ),
          ),
        ]);
        break;
      case DeliveryStatus.inTransit:
        buttons.addAll([
          SizedBox(
            width: double.infinity,
            child: AsmDriveButton(
              label: 'Submit Proof of Delivery',
              icon: PhosphorIconsBold.sealCheck,
              variant: AsmDriveButtonVariant.success,
              isLoading: isWorking,
              onPressed: isWorking ? null : onPod,
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: AsmDriveButton(
              label: 'Report Failure',
              icon: PhosphorIconsBold.flagPennant,
              variant: AsmDriveButtonVariant.danger,
              onPressed: isWorking ? null : onFail,
            ),
          ),
        ]);
        break;
      case DeliveryStatus.delivered:
      case DeliveryStatus.partially_delivered:
      case DeliveryStatus.failed:
      case DeliveryStatus.cancelled:
        buttons.add(
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: AppColors.border),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Icon(Icons.lock_rounded, size: 16, color: AppColors.muted),
                const SizedBox(width: 8),
                Text(
                  'Mission closed — no further actions',
                  style: const TextStyle(color: AppColors.muted, fontSize: 13),
                ),
              ],
            ),
          ),
        );
        break;
    }

    if (buttons.isEmpty) return const SizedBox.shrink();
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: buttons);
  }
}
