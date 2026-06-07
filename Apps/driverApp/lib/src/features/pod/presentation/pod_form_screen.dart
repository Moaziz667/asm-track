import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../../../services/locale_provider.dart';
import '../../../services/location_service.dart';
import '../../deliveries/models/delivery_models.dart';

// ─── Outcome descriptor ───────────────────────────────────────────────────────
class _OutcomeOption {
  const _OutcomeOption(this.value, this.icon, this.colorKey);
  final String value;
  final IconData icon;
  final String colorKey;

  Color resolve(ColorScheme cs) {
    switch (colorKey) {
      case 'success': return cs.primary;
      case 'danger':  return cs.error;
      case 'warning': return cs.secondary;
      case 'info':    return cs.tertiary;
      default:        return cs.onSurface;
    }
  }
}

class PodFormArgs {
  const PodFormArgs({required this.delivery});

  final DriverDelivery delivery;
}

class PodFormScreen extends ConsumerStatefulWidget {
  const PodFormScreen({super.key, required this.args});

  static const routeName = '/delivery/pod';
  final PodFormArgs args;

  @override
  ConsumerState<PodFormScreen> createState() => _PodFormScreenState();
}

class _PodFormScreenState extends ConsumerState<PodFormScreen> {
  final _notesController = TextEditingController();
  final _picker = ImagePicker();
  final _locationService = LocationService();

  Uint8List? _bonLivraisonBytes;
  Uint8List? _packageBytes;
  bool _submitting = false;
  bool _attachLocation = true;
  bool _isPartial = false;

  /// Quantity actually delivered per item key (sku or name).
  late Map<String, int> _itemsDone;

  /// Per-item delivery outcome: DELIVERED | REFUSED | DAMAGED.
  late Map<String, String> _itemOutcomes;

  /// Per-item reason code — shown for REFUSED, DAMAGED, or partial qty.
  late Map<String, String?> _itemReasons;

  /// Per-item comment — driver can add a note per item.
  late Map<String, TextEditingController> _itemCommentControllers;

  static const _outcomes = [
    _OutcomeOption('DELIVERED', PhosphorIconsRegular.checkCircle, 'success'),
    _OutcomeOption('REFUSED',   PhosphorIconsRegular.xCircle,     'danger'),
    _OutcomeOption('DAMAGED',   PhosphorIconsRegular.warning,     'warning'),
    _OutcomeOption('MISSING',   PhosphorIconsRegular.magnifyingGlassMinus, 'info'),
  ];

  static const _reasonsByOutcome = {
    'REFUSED': [
      'CLIENT_ABSENT',
      'CLIENT_REJECTED',
      'WRONG_ADDRESS',
      'POSTPONED',
      'OTHER',
    ],
    'DAMAGED': [
      'DAMAGED_IN_TRANSIT',
      'DAMAGED_AT_PICKUP',
      'PACKAGING_BROKEN',
      'WRONG_ITEM',
    ],
    'DELIVERED': [
      'OUT_OF_STOCK',
      'WRONG_ITEM',
      'OTHER',
    ],
    'MISSING': [
      'NOT_LOADED',
      'LOST_IN_TRANSIT',
      'WRONG_ITEM',
      'OTHER',
    ],
  };

  @override
  void initState() {
    super.initState();
    _itemsDone              = {};
    _itemOutcomes           = {};
    _itemReasons            = {};
    _itemCommentControllers = {};
    for (final item in widget.args.delivery.items) {
      final key = item.sku ?? item.name;
      _itemsDone[key]              = item.quantity;
      _itemOutcomes[key]           = 'DELIVERED';
      _itemReasons[key]            = null;
      _itemCommentControllers[key] = TextEditingController();
    }
  }

  @override
  void dispose() {
    _notesController.dispose();
    for (final ctrl in _itemCommentControllers.values) {
      ctrl.dispose();
    }
    super.dispose();
  }

  static const _requiresReason = {'REFUSED', 'DAMAGED', 'MISSING'};

  bool get _canSubmit {
    if (_bonLivraisonBytes == null || _packageBytes == null) return false;
    if (!_isPartial) return true;
    for (final item in widget.args.delivery.items) {
      final key     = item.sku ?? item.name;
      final outcome = _itemOutcomes[key] ?? 'DELIVERED';
      final isPartialQty = outcome == 'DELIVERED' && (_itemsDone[key] ?? item.quantity) < item.quantity;
      if ((_requiresReason.contains(outcome) || isPartialQty) && _itemReasons[key] == null) {
        return false;
      }
    }
    return true;
  }

  // Returns the first item missing a reason — used to show a hint message.
  String? get _missingReasonItem {
    if (!_isPartial) return null;
    for (final item in widget.args.delivery.items) {
      final key     = item.sku ?? item.name;
      final outcome = _itemOutcomes[key] ?? 'DELIVERED';
      final isPartialQty = outcome == 'DELIVERED' && (_itemsDone[key] ?? item.quantity) < item.quantity;
      if ((_requiresReason.contains(outcome) || isPartialQty) && _itemReasons[key] == null) {
        return item.name;
      }
    }
    return null;
  }

  bool _openingBl = false;

  Future<void> _openBonLivraison() async {
    final locale = ref.read(localeProvider);
    if (_openingBl) return;
    setState(() => _openingBl = true);
    try {
      final deliveryId = widget.args.delivery.id;
      final ok = await ref.read(pdfServiceProvider).downloadAndOpen(
        '/api/driver/deliveries/$deliveryId/bon-livraison',
        fileName: 'bon-livraison-$deliveryId.pdf',
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
      if (mounted) setState(() => _openingBl = false);
    }
  }

  String _translateOutcome(String outcome, String locale) {
    if (locale == 'ar') {
      switch (outcome) {
        case 'DELIVERED': return 'تم التوصيل';
        case 'REFUSED': return 'مرفوض';
        case 'DAMAGED': return 'تالف';
        case 'MISSING': return 'مفقود';
      }
    } else if (locale == 'en') {
      switch (outcome) {
        case 'DELIVERED': return 'Delivered';
        case 'REFUSED': return 'Refused';
        case 'DAMAGED': return 'Damaged';
        case 'MISSING': return 'Missing';
      }
    }
    switch (outcome) {
      case 'DELIVERED': return 'Livré';
      case 'REFUSED': return 'Refusé';
      case 'DAMAGED': return 'Endommagé';
      case 'MISSING': return 'Manquant';
    }
    return outcome;
  }

  String _translateReason(String reason, String locale) {
    if (locale == 'ar') {
      switch (reason) {
        case 'CLIENT_ABSENT': return 'العميل غائب';
        case 'CLIENT_REJECTED': return 'رفض العميل';
        case 'WRONG_ADDRESS': return 'عنوان خاطئ';
        case 'POSTPONED': return 'مؤجل';
        case 'OTHER': return 'آخر';
        case 'DAMAGED_IN_TRANSIT': return 'تالف أثناء النقل';
        case 'DAMAGED_AT_PICKUP': return 'تالف عند الاستلام';
        case 'PACKAGING_BROKEN': return 'التعبئة تالفة';
        case 'WRONG_ITEM': return 'سلعة خاطئة';
        case 'OUT_OF_STOCK': return 'نفذت الكمية';
        case 'NOT_LOADED': return 'لم يتم شحنها';
        case 'LOST_IN_TRANSIT': return 'مفقود أثناء النقل';
      }
    } else if (locale == 'en') {
      switch (reason) {
        case 'CLIENT_ABSENT': return 'Customer absent';
        case 'CLIENT_REJECTED': return 'Customer rejected';
        case 'WRONG_ADDRESS': return 'Wrong address';
        case 'POSTPONED': return 'Postponed';
        case 'OTHER': return 'Other';
        case 'DAMAGED_IN_TRANSIT': return 'Damaged in transit';
        case 'DAMAGED_AT_PICKUP': return 'Damaged at pickup';
        case 'PACKAGING_BROKEN': return 'Packaging broken';
        case 'WRONG_ITEM': return 'Wrong item';
        case 'OUT_OF_STOCK': return 'Out of stock';
        case 'NOT_LOADED': return 'Not loaded at depot';
        case 'LOST_IN_TRANSIT': return 'Lost in transit';
      }
    }
    switch (reason) {
      case 'CLIENT_ABSENT': return 'Client absent';
      case 'CLIENT_REJECTED': return 'Refus du client';
      case 'WRONG_ADDRESS': return 'Mauvaise adresse';
      case 'POSTPONED': return 'Reporté';
      case 'OTHER': return 'Autre';
      case 'DAMAGED_IN_TRANSIT': return 'Endommagé en transit';
      case 'DAMAGED_AT_PICKUP': return 'Endommagé à la collecte';
      case 'PACKAGING_BROKEN': return 'Emballage défectueux';
      case 'WRONG_ITEM': return 'Mauvais article';
      case 'OUT_OF_STOCK': return 'Rupture de stock';
      case 'NOT_LOADED': return 'Non chargé en dépôt';
      case 'LOST_IN_TRANSIT': return 'Perdu en transit';
    }
    return reason;
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final locale = ref.watch(localeProvider);
    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        title: Text(DriverCopy.get('pod_title', locale)),
        leading: IconButton(
          icon: const Icon(PhosphorIconsRegular.caretLeft, size: 18),
          onPressed: () => Navigator.of(context).pop(),
        ),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            // Instructions
            Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: cs.tertiary.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: cs.tertiary.withValues(alpha: 0.2)),
              ),
              child: Text(
                '${DriverCopy.get('pod_step_1', locale)}\n'
                '${DriverCopy.get('pod_step_2', locale)}\n'
                '${DriverCopy.get('pod_step_3', locale)}',
                style: TextStyle(fontSize: 13, color: cs.tertiary, height: 1.5),
              ),
            ),
            const SizedBox(height: 16),

            // View bon de livraison button
            OutlinedButton.icon(
              onPressed: _openingBl ? null : _openBonLivraison,
              icon: _openingBl
                  ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(PhosphorIconsRegular.filePdf),
              label: Text(_openingBl ? DriverCopy.get('pod_downloading', locale) : DriverCopy.get('pod_view_print_bl', locale)),
            ),
            const SizedBox(height: 20),

            // Photo 1: Bon de livraison signé
            _buildPhotoSection(
              context,
              title: DriverCopy.get('pod_photo_bl_title', locale),
              subtitle: DriverCopy.get('pod_photo_bl_sub', locale),
              bytes: _bonLivraisonBytes,
              required: true,
              onPick: (bytes) => setState(() => _bonLivraisonBytes = bytes),
              onClear: () => setState(() => _bonLivraisonBytes = null),
              locale: locale,
            ),
            const SizedBox(height: 16),

            // Photo 2: Package handover
            _buildPhotoSection(
              context,
              title: DriverCopy.get('pod_photo_pkg_title', locale),
              subtitle: DriverCopy.get('pod_photo_pkg_sub', locale),
              bytes: _packageBytes,
              required: true,
              onPick: (bytes) => setState(() => _packageBytes = bytes),
              onClear: () => setState(() => _packageBytes = null),
              locale: locale,
            ),
            const SizedBox(height: 20),

            _buildNotesField(context, locale),
            const SizedBox(height: 16),
            _buildLocationToggle(context, locale),
            const SizedBox(height: 16),
            _buildPartialDeliveryToggle(context, locale),
            const SizedBox(height: 24),

            if (!_canSubmit)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  _missingReasonItem != null
                      ? '${DriverCopy.get('pod_reason_mandatory', locale)} $_missingReasonItem'
                      : DriverCopy.get('pod_photos_mandatory', locale),
                  style: TextStyle(fontSize: 12, color: cs.error.withValues(alpha: 0.8)),
                  textAlign: TextAlign.center,
                ),
              ),

            SizedBox(
              width: double.infinity,
              height: 52,
              child: FilledButton.icon(
                onPressed: (_submitting || !_canSubmit) ? null : _submit,
                icon: _submitting
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(PhosphorIconsRegular.check),
                label: Text(DriverCopy.get('pod_confirm_delivery', locale)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPhotoSection(
    BuildContext context, {
    required String title,
    required String subtitle,
    required Uint8List? bytes,
    required bool required,
    required ValueChanged<Uint8List> onPick,
    required VoidCallback onClear,
    required String locale,
  }) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Text(title, style: Theme.of(context).textTheme.titleMedium),
                          if (required)
                            Text(' *', style: TextStyle(color: cs.error, fontWeight: FontWeight.bold)),
                        ],
                      ),
                      Text(subtitle, style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
                    ],
                  ),
                ),
                if (bytes != null)
                  Container(
                    width: 22,
                    height: 22,
                    decoration: BoxDecoration(color: cs.primary, shape: BoxShape.circle),
                    child: const Icon(PhosphorIconsRegular.check, size: 14, color: Colors.white),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            if (bytes != null)
              ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: Image.memory(bytes, height: 160, width: double.infinity, fit: BoxFit.cover),
              )
            else
              GestureDetector(
                onTap: () => _pickPhoto(onPick, locale),
                child: Container(
                  height: 120,
                  decoration: BoxDecoration(
                    color: cs.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(color: cs.outlineVariant),
                  ),
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(PhosphorIconsRegular.camera, size: 32, color: cs.onSurfaceVariant),
                        const SizedBox(height: 6),
                        Text(DriverCopy.get('pod_photo_tap_hint', locale), style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
                      ],
                    ),
                  ),
                ),
              ),
            const SizedBox(height: 10),
            Row(
              children: [
                OutlinedButton.icon(
                  onPressed: () => _pickPhoto(onPick, locale),
                  icon: const Icon(PhosphorIconsRegular.camera, size: 16),
                  label: Text(bytes == null ? DriverCopy.get('pod_photo_take', locale) : DriverCopy.get('pod_photo_retake', locale)),
                  style: OutlinedButton.styleFrom(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8)),
                ),
                if (bytes != null) ...[
                  const SizedBox(width: 8),
                  TextButton.icon(
                    onPressed: onClear,
                    icon: const Icon(PhosphorIconsRegular.trash, size: 16),
                    label: Text(DriverCopy.get('pod_photo_delete', locale)),
                    style: TextButton.styleFrom(foregroundColor: cs.error),
                  ),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildNotesField(BuildContext context, String locale) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: TextField(
          controller: _notesController,
          minLines: 2,
          maxLines: 5,
          decoration: InputDecoration(
            labelText: DriverCopy.get('pod_comments_label', locale),
          ),
        ),
      ),
    );
  }

  Widget _buildLocationToggle(BuildContext context, String locale) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(PhosphorIconsRegular.mapPin, color: cs.tertiary),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(DriverCopy.get('pod_gps_label', locale)),
                  Text(
                    DriverCopy.get('pod_gps_sub', locale),
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                  ),
                ],
              ),
            ),
            Switch(
              value: _attachLocation,
              onChanged: (value) => setState(() => _attachLocation = value),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPartialDeliveryToggle(BuildContext context, String locale) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(PhosphorIconsRegular.warning, color: cs.secondary),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(DriverCopy.get('pod_partial_label', locale)),
                      Text(
                        DriverCopy.get('pod_partial_sub', locale),
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                      ),
                    ],
                  ),
                ),
                Switch(
                  value: _isPartial,
                  onChanged: (value) => setState(() => _isPartial = value),
                ),
              ],
            ),
            if (_isPartial) ...[
              const Divider(height: 24),
              Text(DriverCopy.get('pod_item_outcome_header', locale), style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 12),
              ...widget.args.delivery.items.map((item) => _buildItemOutcomeRow(context, item, locale)),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildItemOutcomeRow(BuildContext context, dynamic item, String locale) {
    final cs = Theme.of(context).colorScheme;
    final key         = (item.sku ?? item.name) as String;
    final plannedQty  = item.quantity as int;
    final currentQty  = _itemsDone[key] ?? plannedQty;
    final outcome     = _itemOutcomes[key] ?? 'DELIVERED';
    final reason      = _itemReasons[key];
    final commentCtrl = _itemCommentControllers[key]!;

    final opt          = _outcomes.firstWhere((o) => o.value == outcome, orElse: () => _outcomes.first);
    final optColor     = opt.resolve(cs);
    final isPartialQty = outcome == 'DELIVERED' && currentQty < plannedQty;
    final needsExtra   = _requiresReason.contains(outcome) || isPartialQty;
    final borderColor  = isPartialQty ? cs.secondary : optColor;

    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      decoration: BoxDecoration(
        color: cs.surfaceContainerLow,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: borderColor.withValues(alpha: 0.4), width: 1.5),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 0),
            child: Row(
              children: [
                Container(
                  width: 32, height: 32,
                  decoration: BoxDecoration(
                    color: borderColor.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Icon(opt.icon, size: 16, color: borderColor),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        item.name as String,
                        style: TextStyle(fontWeight: FontWeight.w700, fontSize: 14, color: cs.onSurface),
                      ),
                      if (item.sku != null && item.sku != item.name)
                        Text(item.sku as String, style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant)),
                    ],
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                  decoration: BoxDecoration(
                    color: cs.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: cs.outlineVariant),
                  ),
                  child: Text(
                    outcome == 'DELIVERED' ? '$currentQty / $plannedQty' : '0 / $plannedQty',
                    style: TextStyle(
                      fontSize: 12, fontWeight: FontWeight.w700, fontFamily: 'monospace',
                      color: isPartialQty ? cs.secondary
                           : outcome != 'DELIVERED' ? cs.error
                           : cs.primary,
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: GridView.count(
              crossAxisCount: 2,
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              crossAxisSpacing: 8,
              mainAxisSpacing: 8,
              childAspectRatio: 2.8,
              children: _outcomes.map((o) {
                final oColor = o.resolve(cs);
                final selected = outcome == o.value;
                return GestureDetector(
                  onTap: () => setState(() {
                    _itemOutcomes[key] = o.value;
                    _itemReasons[key]  = null;
                    _itemsDone[key]    = o.value == 'DELIVERED' ? plannedQty : 0;
                  }),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 150),
                    decoration: BoxDecoration(
                      color: selected ? oColor.withValues(alpha: 0.18) : cs.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(
                        color: selected ? oColor : cs.outlineVariant,
                        width: selected ? 1.5 : 1,
                      ),
                    ),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(o.icon, size: 15, color: selected ? oColor : cs.onSurfaceVariant),
                        const SizedBox(width: 6),
                        Text(
                          _translateOutcome(o.value, locale),
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                            color: selected ? oColor : cs.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
          if (outcome == 'DELIVERED') ...[
            const SizedBox(height: 12),
            Divider(height: 1, color: cs.outlineVariant),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Row(
                children: [
                  Text(DriverCopy.get('pod_delivered_qty', locale),
                      style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant)),
                  const Spacer(),
                  GestureDetector(
                    onTap: currentQty > 0
                        ? () => setState(() => _itemsDone[key] = currentQty - 1)
                        : null,
                    child: Container(
                      width: 32, height: 32,
                      decoration: BoxDecoration(
                        color: currentQty > 0 ? cs.errorContainer : cs.surfaceContainerHighest,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: currentQty > 0 ? cs.error : cs.outlineVariant),
                      ),
                      child: Icon(PhosphorIconsRegular.minus, size: 16,
                          color: currentQty > 0 ? cs.error : cs.onSurfaceVariant),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 20),
                    child: Text(
                      '$currentQty',
                      style: TextStyle(
                        fontWeight: FontWeight.w800, fontSize: 18,
                        color: isPartialQty ? cs.secondary : cs.onSurface,
                      ),
                    ),
                  ),
                  GestureDetector(
                    onTap: currentQty < plannedQty
                        ? () => setState(() => _itemsDone[key] = currentQty + 1)
                        : null,
                    child: Container(
                      width: 32, height: 32,
                      decoration: BoxDecoration(
                        color: currentQty < plannedQty ? cs.primaryContainer : cs.surfaceContainerHighest,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(
                            color: currentQty < plannedQty ? cs.primary : cs.outlineVariant),
                      ),
                      child: Icon(PhosphorIconsRegular.plus, size: 16,
                          color: currentQty < plannedQty ? cs.primary : cs.onSurfaceVariant),
                    ),
                  ),
                ],
              ),
            ),
          ],
          if (needsExtra) ...[
            Divider(height: 1, color: cs.outlineVariant),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
              child: Text(
                isPartialQty ? DriverCopy.get('pod_reason_partial', locale) : DriverCopy.get('pod_reason_label', locale),
                style: TextStyle(
                    fontSize: 11, fontWeight: FontWeight.w700,
                    color: cs.onSurfaceVariant),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
              child: Wrap(
                spacing: 8,
                runSpacing: 8,
                children: (_reasonsByOutcome[outcome] ?? _reasonsByOutcome['REFUSED']!)
                    .map((r) {
                  final selected = reason == r;
                  return GestureDetector(
                    onTap: () => setState(() => _itemReasons[key] = r),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 120),
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
                      decoration: BoxDecoration(
                        color: selected ? optColor.withValues(alpha: 0.15) : cs.surfaceContainerHighest,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(
                          color: selected ? optColor : cs.outlineVariant,
                          width: selected ? 1.5 : 1,
                        ),
                      ),
                      child: Text(
                        _translateReason(r, locale),
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w500,
                          color: selected ? optColor : cs.onSurfaceVariant,
                        ),
                      ),
                    ),
                  );
                }).toList(),
              ),
            ),
          ],
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 14),
            child: TextField(
              controller: commentCtrl,
              minLines: 1,
              maxLines: 3,
              style: const TextStyle(fontSize: 13),
              decoration: InputDecoration(
                hintText: DriverCopy.get('pod_item_comment_hint', locale),
                hintStyle: TextStyle(fontSize: 13, color: cs.onSurfaceVariant),
                contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _pickPhoto(ValueChanged<Uint8List> onDone, String locale) async {
    try {
      final file = await _picker.pickImage(
        source: ImageSource.camera,
        maxWidth: 1024,
        maxHeight: 1024,
        imageQuality: 70,
      );
      if (file == null) return;
      final bytes = await file.readAsBytes();
      if (!mounted) return;
      onDone(bytes);
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(DriverCopy.get('pod_photo_access_error', locale))),
      );
    }
  }

  Future<void> _submit() async {
    final locale = ref.read(localeProvider);
    if (!_canSubmit) return;
    setState(() => _submitting = true);
    try {
      double? lat;
      double? lng;
      if (_attachLocation) {
        final point = await _locationService.currentPosition();
        if (point != null) {
          lat = point.lat;
          lng = point.lng;
        }
      }

      List<PartialDeliveryItem>? itemsArray;
      if (_isPartial) {
        itemsArray = _itemsDone.entries.map((e) {
          final outcome     = _itemOutcomes[e.key] ?? 'DELIVERED';
          final reason      = _itemReasons[e.key];
          final comment     = _itemCommentControllers[e.key]?.text.trim();
          final plannedQty  = widget.args.delivery.items
              .firstWhere((i) => (i.sku ?? i.name) == e.key,
                  orElse: () => widget.args.delivery.items.first)
              .quantity;
          final isPartialQty = outcome == 'DELIVERED' && e.value < plannedQty;
          final sendReason = _requiresReason.contains(outcome) || isPartialQty;
          return PartialDeliveryItem(
            sku: e.key,
            quantityDone: e.value,
            outcome: outcome,
            reason: sendReason ? reason : null,
            comment: comment?.isNotEmpty == true ? comment : null,
          );
        }).toList();
      }

      final payload = PodPayload(
        bonLivraisonPhotoBase64: base64Encode(_bonLivraisonBytes!),
        packagePhotoBase64: base64Encode(_packageBytes!),
        comment: _notesController.text.trim().isEmpty ? null : _notesController.text.trim(),
        lat: lat,
        lng: lng,
        isPartial: _isPartial,
        itemsDone: itemsArray,
      );

      try {
        await ref.read(deliveryRepositoryProvider).submitPod(widget.args.delivery.id, payload);
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(DriverCopy.get('pod_success_message', locale))));
        Navigator.of(context).pop(true);
      } catch (e) {
        if (e == 'OFFLINE_QUEUED') {
          if (!mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(DriverCopy.get('delivery_detail_offline_queue', locale))),
          );
          Navigator.of(context).pop(true);
        } else {
          rethrow;
        }
      }
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('${DriverCopy.get('delivery_detail_error_prefix', locale)}: $error')));
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }
}
