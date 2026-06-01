import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../deliveries/models/delivery_models.dart';

// ─── Outcome descriptor ───────────────────────────────────────────────────────
class _OutcomeOption {
  const _OutcomeOption(this.value, this.label, this.icon, this.colorKey);
  final String value;
  final String label;
  final IconData icon;
  final String colorKey;

  Color resolve(ColorScheme cs) {
    switch (colorKey) {
      case 'success': return cs.tertiary;
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
    _OutcomeOption('DELIVERED', 'Livré',     Icons.check_circle_outline_rounded, 'success'),
    _OutcomeOption('REFUSED',   'Refusé',    Icons.cancel_outlined,              'danger'),
    _OutcomeOption('DAMAGED',   'Endommagé', Icons.warning_amber_rounded,        'warning'),
    _OutcomeOption('MISSING',   'Manquant',  Icons.search_off_rounded,           'info'),
  ];

  static const _reasonsByOutcome = {
    'REFUSED': [
      ('CLIENT_ABSENT',   'Client absent'),
      ('CLIENT_REJECTED', 'Refus du client'),
      ('WRONG_ADDRESS',   'Mauvaise adresse'),
      ('POSTPONED',       'Reporté'),
      ('OTHER',           'Autre'),
    ],
    'DAMAGED': [
      ('DAMAGED_IN_TRANSIT', 'Endommagé en transit'),
      ('DAMAGED_AT_PICKUP',  'Endommagé à la collecte'),
      ('PACKAGING_BROKEN',   'Emballage défectueux'),
      ('WRONG_ITEM',         'Mauvais article'),
    ],
    'DELIVERED': [
      ('OUT_OF_STOCK', 'Rupture de stock'),
      ('WRONG_ITEM',   'Mauvais article'),
      ('OTHER',        'Autre'),
    ],
    'MISSING': [
      ('NOT_LOADED',      'Non chargé en dépôt'),
      ('LOST_IN_TRANSIT', 'Perdu en transit'),
      ('WRONG_ITEM',      'Mauvais article'),
      ('OTHER',           'Autre'),
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
          const SnackBar(content: Text('Impossible d\'ouvrir le PDF. Aucune application PDF installée.')),
        );
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Erreur lors du téléchargement du bon de livraison.')),
        );
      }
    } finally {
      if (mounted) setState(() => _openingBl = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        title: const Text('Preuve de livraison'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new_rounded, size: 18),
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
                '1. Imprimez le bon de livraison et faites-le signer par le client.\n'
                '2. Photographiez le bon signé.\n'
                '3. Photographiez la remise du colis.',
                style: TextStyle(fontSize: 13, color: cs.tertiary, height: 1.5),
              ),
            ),
            const SizedBox(height: 16),

            // View bon de livraison button
            OutlinedButton.icon(
              onPressed: _openingBl ? null : _openBonLivraison,
              icon: _openingBl
                  ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.picture_as_pdf_outlined),
              label: Text(_openingBl ? 'Téléchargement…' : 'Voir / Imprimer bon de livraison'),
            ),
            const SizedBox(height: 20),

            // Photo 1: Bon de livraison signé
            _buildPhotoSection(
              context,
              title: '📄 Bon de livraison signé',
              subtitle: 'Photographiez le bon signé par le client',
              bytes: _bonLivraisonBytes,
              required: true,
              onPick: (bytes) => setState(() => _bonLivraisonBytes = bytes),
              onClear: () => setState(() => _bonLivraisonBytes = null),
            ),
            const SizedBox(height: 16),

            // Photo 2: Package handover
            _buildPhotoSection(
              context,
              title: '📦 Remise du colis',
              subtitle: 'Photographiez le colis au moment de la remise',
              bytes: _packageBytes,
              required: true,
              onPick: (bytes) => setState(() => _packageBytes = bytes),
              onClear: () => setState(() => _packageBytes = null),
            ),
            const SizedBox(height: 20),

            _buildNotesField(context),
            const SizedBox(height: 16),
            _buildLocationToggle(context),
            const SizedBox(height: 16),
            _buildPartialDeliveryToggle(context),
            const SizedBox(height: 24),

            if (!_canSubmit)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  _missingReasonItem != null
                      ? 'Raison obligatoire pour : $_missingReasonItem'
                      : 'Les 2 photos sont obligatoires.',
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
                    : const Icon(Icons.check),
                label: const Text('Confirmer la livraison'),
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
                    decoration: BoxDecoration(color: cs.tertiary, shape: BoxShape.circle),
                    child: const Icon(Icons.check, size: 14, color: Colors.white),
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
                onTap: () => _pickPhoto(onPick),
                child: Container(
                  height: 120,
                  decoration: BoxDecoration(
                    color: cs.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(color: cs.outlineVariant, style: BorderStyle.solid),
                  ),
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.camera_alt_outlined, size: 32, color: cs.onSurfaceVariant),
                        const SizedBox(height: 6),
                        Text('Appuyer pour photographier', style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
                      ],
                    ),
                  ),
                ),
              ),
            const SizedBox(height: 10),
            Row(
              children: [
                OutlinedButton.icon(
                  onPressed: () => _pickPhoto(onPick),
                  icon: const Icon(Icons.camera_alt_outlined, size: 16),
                  label: Text(bytes == null ? 'Prendre une photo' : 'Reprendre'),
                  style: OutlinedButton.styleFrom(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8)),
                ),
                if (bytes != null) ...[
                  const SizedBox(width: 8),
                  TextButton.icon(
                    onPressed: onClear,
                    icon: const Icon(Icons.delete_outline, size: 16),
                    label: const Text('Supprimer'),
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

  Widget _buildNotesField(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: TextField(
          controller: _notesController,
          minLines: 2,
          maxLines: 5,
          decoration: const InputDecoration(
            labelText: 'Commentaires (code porte, nom personne, etc.)',
          ),
        ),
      ),
    );
  }

  Widget _buildLocationToggle(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(Icons.my_location, color: cs.tertiary),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Joindre la position GPS'),
                  Text(
                    'Coordonnées envoyées une seule fois à la soumission.',
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

  Widget _buildPartialDeliveryToggle(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // ── Toggle header ──────────────────────────────────────────────
            Row(
              children: [
                Icon(Icons.warning_amber_rounded, color: cs.secondary),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('Livraison partielle'),
                      Text(
                        'Activez si certains articles n\'ont pas été livrés.',
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

            // ── Per-item outcome section ───────────────────────────────────
            if (_isPartial) ...[
              const Divider(height: 24),
              Text('Résultat par article :', style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 12),
              ...widget.args.delivery.items.map((item) => _buildItemOutcomeRow(context, item)),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildItemOutcomeRow(BuildContext context, dynamic item) {
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

          // ── Item header ───────────────────────────────────────────────
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
                // Qty badge
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
                           : cs.tertiary,
                    ),
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 12),

          // ── Outcome grid — 2×2 ───────────────────────────────────────
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
                          o.label,
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

          // ── Quantity stepper (DELIVERED only) ────────────────────────
          if (outcome == 'DELIVERED') ...[
            const SizedBox(height: 12),
            Divider(height: 1, color: cs.outlineVariant),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Row(
                children: [
                  Text('Quantité livrée',
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
                      child: Icon(Icons.remove, size: 16,
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
                        color: currentQty < plannedQty ? cs.tertiaryContainer : cs.surfaceContainerHighest,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(
                            color: currentQty < plannedQty ? cs.tertiary : cs.outlineVariant),
                      ),
                      child: Icon(Icons.add, size: 16,
                          color: currentQty < plannedQty ? cs.tertiary : cs.onSurfaceVariant),
                    ),
                  ),
                ],
              ),
            ),
          ],

          // ── Reason chips (wrapped grid) ───────────────────────────────
          if (needsExtra) ...[
            Divider(height: 1, color: cs.outlineVariant),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
              child: Text(
                isPartialQty ? 'Motif — livraison partielle *' : 'Motif *',
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
                  final selected = reason == r.$1;
                  return GestureDetector(
                    onTap: () => setState(() => _itemReasons[key] = r.$1),
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
                        r.$2,
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

          // ── Per-item comment ──────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 14),
            child: TextField(
              controller: commentCtrl,
              minLines: 1,
              maxLines: 3,
              style: const TextStyle(fontSize: 13),
              decoration: InputDecoration(
                hintText: 'Commentaire sur cet article (optionnel)',
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

  Future<void> _pickPhoto(ValueChanged<Uint8List> onDone) async {
    try {
      final file = await _picker.pickImage(source: ImageSource.camera, imageQuality: 80);
      if (file == null) return;
      final bytes = await file.readAsBytes();
      if (!mounted) return;
      onDone(bytes);
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Impossible d\'accéder à l\'appareil photo.')),
      );
    }
  }

  Future<void> _submit() async {
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
          // Send reason when: refused, damaged, missing, or delivered with less than planned qty
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
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('POD transmis avec succ\u00e8s.')));
        Navigator.of(context).pop(true);
      } catch (e) {
        if (e == 'OFFLINE_QUEUED') {
          if (!mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Hors ligne : POD enregistr\u00e9 pour synchronisation ult\u00e9rieure')),
          );
          Navigator.of(context).pop(true);
        } else {
          rethrow;
        }
      }
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('\u00c9chec de soumission du POD : $error')));
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }
}
