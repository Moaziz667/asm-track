import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';
import 'package:url_launcher/url_launcher_string.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../../services/offline_queue_service.dart';
import '../../../theme/widgets.dart';
import '../../pod/presentation/pod_form_screen.dart';
import '../models/delivery_models.dart';
import 'handoff_token_sheet.dart';
import 'handoff_scanner_screen.dart';

// ─── COD Collection Card ──────────────────────────────────────────────────────
class _CodCollectionCard extends ConsumerStatefulWidget {
  const _CodCollectionCard({required this.delivery, required this.onDone});
  final DriverDelivery delivery;
  final VoidCallback onDone;

  @override
  ConsumerState<_CodCollectionCard> createState() => _CodCollectionCardState();
}

class _CodCollectionCardState extends ConsumerState<_CodCollectionCard> {
  bool _submitting = false;
  final _amountCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    if (widget.delivery.totalAmount != null) {
      _amountCtrl.text = widget.delivery.totalAmount!.toStringAsFixed(3);
    }
  }

  @override
  void dispose() {
    _amountCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit(bool collected) async {
    if (_submitting) return;
    setState(() => _submitting = true);
    try {
      double? amount;
      if (collected) {
        amount = double.tryParse(_amountCtrl.text.replaceAll(',', '.'));
      }

      await ref.read(deliveryRepositoryProvider).recordCod(
        widget.delivery.id,
        collected: collected,
        amountCollected: amount,
      );
      widget.onDone();
    } catch (e) {
      if (e == 'OFFLINE_QUEUED') {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Hors ligne — sera envoyé à la reconnexion')),
          );
        }
        widget.onDone();
      } else {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Erreur: $e')),
          );
        }
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF1A1200),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0xFFFF8C00).withValues(alpha: 0.5), width: 1.5),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: const Color(0xFFFF8C00).withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Icon(Icons.payments_outlined, color: Color(0xFFFF8C00), size: 18),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Encaissement COD requis',
                        style: Theme.of(context).textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w800,
                          color: Theme.of(context).colorScheme.onSurface,
                        )),
                    Text('Confirmez la collecte du paiement en espèces',
                        style: TextStyle(fontSize: 11, color: Theme.of(context).colorScheme.onSurfaceVariant)),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          TextField(
            controller: _amountCtrl,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            style: Theme.of(context).textTheme.titleLarge?.copyWith(
              fontSize: 18,
              fontWeight: FontWeight.w900,
              color: Theme.of(context).colorScheme.onSurface,
            ),
            decoration: InputDecoration(
              labelText: 'Montant encaissé (TND)',
              suffixText: 'TND',
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _submitting ? null : () => _submit(false),
                  icon: Icon(Icons.close, size: 16, color: Theme.of(context).colorScheme.error),
                  label: Text('Non encaissé', style: TextStyle(color: Theme.of(context).colorScheme.error)),
                  style: OutlinedButton.styleFrom(
                    side: BorderSide(color: Theme.of(context).colorScheme.error),
                    padding: const EdgeInsets.symmetric(vertical: 12),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: ElevatedButton.icon(
                  onPressed: _submitting ? null : () => _submit(true),
                  icon: _submitting
                      ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.black))
                      : const Icon(Icons.check, size: 16, color: Colors.black),
                  label: Text(_submitting ? 'En cours…' : 'Encaissé',
                      style: const TextStyle(color: Colors.black, fontWeight: FontWeight.w700)),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: const Color(0xFFFF8C00),
                    padding: const EdgeInsets.symmetric(vertical: 12),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

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

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final asyncDetail = ref.watch(deliveryDetailProvider(widget.args.deliveryId));
    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        backgroundColor: cs.surface,
        leading: IconButton(
          icon: const Icon(PhosphorIconsBold.caretLeft, size: 18),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: const Text('Détails de la livraison'),
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
              if (delivery.needsCodConfirmation) ...[
                _CodCollectionCard(delivery: delivery, onDone: _refresh),
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
                  final reason = await _showFailSheet(context);
                  if (reason == null) return;
                  await _perform(() => ref.read(deliveryRepositoryProvider).fail(
                        delivery.id,
                        reason: reason.$1,
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
        loading: () => const LoadingState(message: 'Chargement de la livraison…'),
        error: (_, __) => EmptyState(
          icon: PhosphorIconsRegular.warningCircle,
          title: 'Échec du chargement',
          action: _refresh,
          actionLabel: 'Réessayer',
        ),
      ),
    );
  }

  Future<(FailureReason, String?)?> _showFailSheet(BuildContext context) async {
    final cs = Theme.of(context).colorScheme;
    FailureReason selected = FailureReason.clientAbsent;
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
                      decoration: BoxDecoration(color: cs.outlineVariant, borderRadius: BorderRadius.circular(2)),
                    ),
                  ),
                  const SizedBox(height: 20),
                  Text('Signaler un échec', style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700, color: cs.onSurface)),
                  const SizedBox(height: 4),
                  Text('Sélectionnez la raison de l\'échec de cette livraison.', style: TextStyle(color: cs.onSurfaceVariant, fontSize: 13)),
                  const SizedBox(height: 20),
                  ...FailureReason.values.map((reason) {
                    final isSelected = reason == selected;
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
                    decoration: const InputDecoration(
                      hintText: 'Commentaire supplémentaire (optionnel)',
                      prefixIcon: Icon(PhosphorIconsRegular.notePencil, size: 18),
                    ),
                  ),
                  const SizedBox(height: 20),
                  SizedBox(
                    width: double.infinity,
                    child: TextButton.icon(
                      onPressed: () => Navigator.pop(context, true),
                      icon: const Icon(PhosphorIconsBold.flagPennant),
                      label: const Text('Soumettre le rapport d\'échec'),
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
      return (selected, commentCtrl.text.trim().isEmpty ? null : commentCtrl.text.trim());
    }
    return null;
  }
}

// ─── Hero Card ───────────────────────────────────────────────────────────────
class _HeroCard extends StatelessWidget {
  const _HeroCard({required this.delivery});
  final DriverDelivery delivery;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final statusColor = delivery.status.badgeColor;
    return Container(
      decoration: BoxDecoration(
        color: cs.surfaceContainerLow,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: cs.outlineVariant),
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
                        delivery.status.label,
                        style: TextStyle(
                          fontSize: 10,
                          fontWeight: FontWeight.w700,
                          color: statusColor,
                        ),
                      ),
                    ),
                    const Spacer(),
                    if (delivery.isCod) ...[
                      _CodBadge(delivery.codCollected),
                      const SizedBox(width: 8),
                    ],
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
                      Icon(Icons.person_outline_rounded, size: 14, color: cs.onSurfaceVariant),
                      const SizedBox(width: 4),
                      Text(delivery.clientName!, style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: cs.onSurface)),
                      if (delivery.clientPhone != null) ...[
                        const SizedBox(width: 8),
                        GestureDetector(
                          onTap: () => launchUrlString('tel:${delivery.clientPhone}'),
                          child: Row(
                            children: [
                              Icon(Icons.phone_outlined, size: 13, color: cs.primary),
                              const SizedBox(width: 3),
                              Text(delivery.clientPhone!, style: TextStyle(fontSize: 13, color: cs.primary, fontWeight: FontWeight.w500)),
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
                      Icon(Icons.location_on_outlined, size: 14, color: cs.onSurfaceVariant),
                      const SizedBox(width: 4),
                      Text(delivery.city!, style: TextStyle(fontSize: 14, color: cs.onSurfaceVariant)),
                    ],
                  ),
                ],
                const SizedBox(height: 16),
                Divider(color: cs.outlineVariant, height: 1),
                const SizedBox(height: 16),
                Row(
                  children: [
                    _StatBox(
                      label: 'Articles',
                      value: '${delivery.items.length}',
                    ),
                    if (delivery.scheduledAt != null) ...[
                      const SizedBox(width: 12),
                      _StatBox(
                        label: 'Planifiée',
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
            Text(label, style: TextStyle(fontSize: 10, color: cs.onSurfaceVariant, fontWeight: FontWeight.w600)),
            const SizedBox(height: 4),
            Text(value, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: cs.onSurface)),
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
          Icon(Icons.info_outline_rounded, color: cs.tertiary, size: 18),
          const SizedBox(width: 12),
          Expanded(
            child: Text(text, style: TextStyle(fontSize: 14, color: cs.onSurfaceVariant, height: 1.5)),
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
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SectionHeader(title: 'Contenu du colis', subtitle: '${items.length} article${items.length != 1 ? 's' : ''}'),
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
                        child: Icon(Icons.inventory_2_outlined, size: 14, color: cs.onSurfaceVariant),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(e.value.name, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: cs.onSurface)),
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
class _TimestampCard extends StatelessWidget {
  const _TimestampCard({required this.delivery});
  final DriverDelivery delivery;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final entries = delivery.timestamps.entries
        .where((e) => e.value != null)
        .map((e) => (label: _keyLabel(e.key), time: e.value!))
        .toList()
      ..sort((a, b) => a.time.compareTo(b.time));

    if (entries.isEmpty) return const SizedBox.shrink();

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SectionHeader(title: 'Chronologie'),
            const SizedBox(height: 14),
            ...entries.map((e) {
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(
                  children: [
                    Icon(Icons.circle, size: 6, color: cs.primary),
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

  String _keyLabel(String key) {
    switch (key) {
      case 'scheduledAt': return 'Planifiée';
      case 'pickedUpAt': return 'Ramassée';
      case 'inTransitAt': return 'En cours';
      case 'completedAt': return 'Livrée';
      case 'failedAt': return 'Échouée';
      case 'cancelledAt': return 'Annulée';
      case 'createdAt': return 'Créée';
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
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    // Handoff lock: only blocks the receiver (handoffToDriverId) until QR is scanned
    if (delivery.requiresHandoff &&
        delivery.handoffConfirmedAt == null &&
        currentDriverId != null &&
        delivery.handoffToDriverId == currentDriverId) {
      return _HandoffLockPanel(onScan: onScanHandoff);
    }

    final buttons = <Widget>[];

    Future<void> launchNav() async {
      final lat = delivery.lat;
      final lng = delivery.lng;
      if (lat == null || lng == null) return;
      final googleUrl = 'https://www.google.com/maps/dir/?api=1&destination=$lat,$lng';
      try {
        await launchUrlString(googleUrl, mode: LaunchMode.externalApplication);
      } catch (_) {
        // Fallback to geo: URI
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
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: cs.surfaceContainerLow,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: cs.outlineVariant),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(Icons.info_outline_rounded, size: 16, color: cs.onSurfaceVariant),
                const SizedBox(width: 8),
                Text(
                  'En attente de dispatch',
                  style: TextStyle(color: cs.onSurfaceVariant, fontSize: 13),
                ),
              ],
            ),
          )
        );
        break;
      case DeliveryStatus.scheduled:
        buttons.addAll([
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: isWorking ? null : onPickup,
              icon: isWorking
                  ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(PhosphorIconsBold.package),
              label: const Text('Ramasser le colis'),
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: TextButton.icon(
              onPressed: isWorking ? null : onFail,
              icon: const Icon(PhosphorIconsBold.flagPennant),
              label: const Text('Signaler un échec'),
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
                label: const Text('Naviguer'),
              ),
            ),
          );
          buttons.add(const SizedBox(height: 10));
        }
        buttons.addAll([
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: isWorking ? null : onTransit,
              icon: isWorking
                  ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(PhosphorIconsBold.steeringWheel),
              label: const Text('Démarrer le trajet'),
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: TextButton.icon(
              onPressed: isWorking ? null : onFail,
              icon: const Icon(PhosphorIconsBold.flagPennant),
              label: const Text('Signaler un échec'),
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
                icon: const Icon(LucideIcons.qrCode),
                label: const Text('Générer le code de transfert'),
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
                label: const Text('Naviguer'),
              ),
            ),
          );
          buttons.add(const SizedBox(height: 10));
        }
        buttons.addAll([
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: isWorking ? null : onPod,
              icon: isWorking
                  ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(PhosphorIconsBold.sealCheck),
              label: const Text('Soumettre la preuve de livraison'),
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: TextButton.icon(
              onPressed: isWorking ? null : onFail,
              icon: const Icon(PhosphorIconsBold.flagPennant),
              label: const Text('Signaler un échec'),
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
                icon: const Icon(LucideIcons.qrCode),
                label: const Text('Générer le code de transfert'),
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
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: cs.surfaceContainerLow,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: cs.outlineVariant),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(Icons.lock_rounded, size: 16, color: cs.onSurfaceVariant),
                const SizedBox(width: 8),
                Text(
                  'Mission terminée — aucune action requise',
                  style: TextStyle(color: cs.onSurfaceVariant, fontSize: 13),
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

// ─── COD Badge ────────────────────────────────────────────────────────────────
class _CodBadge extends StatelessWidget {
  const _CodBadge(this.codCollected);
  final bool? codCollected;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final Color color;
    final String label;
    if (codCollected == true) {
      color = cs.tertiary; label = 'COD ✓';
    } else if (codCollected == false) {
      color = cs.error; label = 'COD ✗';
    } else {
      color = const Color(0xFFFF8C00); label = 'COD';
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: color.withValues(alpha: 0.4)),
      ),
      child: Text(label,
          style: Theme.of(context).textTheme.labelSmall?.copyWith(
            fontSize: 10,
            fontWeight: FontWeight.w800,
            color: color,
          )),
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
    if (_loading) return;

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

    setState(() => _loading = true);
    try {
      final ok = await ref.read(pdfServiceProvider).downloadAndOpen(
        '/api/driver/deliveries/${widget.deliveryId}/bon-livraison',
        fileName: 'bon-livraison-${widget.deliveryId}.pdf',
      );
      if (!ok && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Aucune application PDF installée.')),
        );
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Erreur téléchargement du bon de livraison.')),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: cs.surfaceContainerLow,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: cs.outlineVariant),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: cs.tertiary.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(Icons.picture_as_pdf_outlined, color: cs.tertiary, size: 20),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Bon de livraison',
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w700,
                      color: cs.onSurface,
                    )),
                const SizedBox(height: 2),
                Text('Ouvrir le PDF pour impression',
                    style: TextStyle(color: cs.onSurfaceVariant, fontSize: 11)),
              ],
            ),
          ),
          TextButton(
            onPressed: _loading ? null : _open,
            child: _loading
                ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Ouvrir'),
          ),
        ],
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
        Text(title, style: Theme.of(context).textTheme.titleMedium?.copyWith(color: cs.onSurface)),
        if (subtitle != null) ...[
          const SizedBox(height: 2),
          Text(subtitle!, style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
        ],
      ],
    );
  }
}

// ─── Handoff Lock Panel ───────────────────────────────────────────────────────
class _HandoffLockPanel extends StatelessWidget {
  const _HandoffLockPanel({this.onScan});
  final Future<void> Function()? onScan;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      children: [
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: cs.secondary.withValues(alpha: 0.1),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: cs.secondary.withValues(alpha: 0.4)),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(Icons.lock_outline_rounded, color: cs.secondary, size: 20),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Remise physique requise',
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        fontSize: 13,
                        fontWeight: FontWeight.w800,
                        color: cs.secondary,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Ce colis vous a été transféré. Scannez le QR du chauffeur expéditeur pour confirmer la réception et débloquer les actions.',
                      style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant, height: 1.4),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        SizedBox(
          width: double.infinity,
          child: FilledButton.icon(
            onPressed: onScan,
            icon: const Icon(PhosphorIconsBold.qrCode),
            label: const Text('Scanner le QR de l\'expéditeur'),
          ),
        ),
      ],
    );
  }
}
