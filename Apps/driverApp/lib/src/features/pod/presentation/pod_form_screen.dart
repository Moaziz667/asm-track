import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../../services/offline_queue_service.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';
import '../../deliveries/models/delivery_models.dart';

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

  static const _outcomeLabels = {
    'DELIVERED': 'Livré',
    'REFUSED':   'Refusé',
    'DAMAGED':   'Endommagé',
  };

  static const _reasonsByOutcome = {
    'REFUSED': {
      'CLIENT_ABSENT':   'Client absent',
      'CLIENT_REJECTED': 'Client a refusé',
      'WRONG_ITEM':      'Mauvais article',
      'POSTPONED':       'Reporté',
    },
    'DAMAGED': {
      'DAMAGED': 'Endommagé en transit',
    },
    'DELIVERED': {
      'WRONG_ITEM':  'Mauvais article',
      'OUT_OF_STOCK': 'Rupture de stock',
    },
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

  bool get _canSubmit {
    if (_bonLivraisonBytes == null || _packageBytes == null) return false;
    if (!_isPartial) return true;
    for (final item in widget.args.delivery.items) {
      final key     = item.sku ?? item.name;
      final outcome = _itemOutcomes[key] ?? 'DELIVERED';
      if ((outcome == 'REFUSED' || outcome == 'DAMAGED') && _itemReasons[key] == null) {
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
      if ((outcome == 'REFUSED' || outcome == 'DAMAGED') && _itemReasons[key] == null) {
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
    return Scaffold(
      backgroundColor: AppColors.background,
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
                color: AppColors.info.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.info.withValues(alpha: 0.2)),
              ),
              child: const Text(
                '1. Imprimez le bon de livraison et faites-le signer par le client.\n'
                '2. Photographiez le bon signé.\n'
                '3. Photographiez la remise du colis.',
                style: TextStyle(fontSize: 13, color: AppColors.info, height: 1.5),
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
                  style: TextStyle(fontSize: 12, color: AppColors.danger.withValues(alpha: 0.8)),
                  textAlign: TextAlign.center,
                ),
              ),

            AsmDriveButton(
              label: 'Confirmer la livraison',
              icon: Icons.check,
              isLoading: _submitting,
              onPressed: (_submitting || !_canSubmit) ? null : _submit,
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
    return AsmDriveCard(
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
                          const Text(' *', style: TextStyle(color: AppColors.danger, fontWeight: FontWeight.bold)),
                      ],
                    ),
                    Text(subtitle, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                  ],
                ),
              ),
              if (bytes != null)
                Container(
                  width: 22,
                  height: 22,
                  decoration: const BoxDecoration(color: AppColors.success, shape: BoxShape.circle),
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
                  color: AppColors.surfaceElevated,
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: AppColors.border, style: BorderStyle.solid),
                ),
                child: const Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.camera_alt_outlined, size: 32, color: AppColors.muted),
                      SizedBox(height: 6),
                      Text('Appuyer pour photographier', style: TextStyle(fontSize: 12, color: AppColors.muted)),
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
                  style: TextButton.styleFrom(foregroundColor: AppColors.danger),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildNotesField(BuildContext context) {
    return AsmDriveCard(
      child: TextField(
        controller: _notesController,
        minLines: 2,
        maxLines: 5,
        decoration: const InputDecoration(
          labelText: 'Commentaires (code porte, nom personne, etc.)',
        ),
      ),
    );
  }

  Widget _buildLocationToggle(BuildContext context) {
    return AsmDriveCard(
      child: Row(
        children: [
          const Icon(Icons.my_location, color: AppColors.success),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Joindre la position GPS'),
                Text(
                  'Coordonnées envoyées une seule fois à la soumission.',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(color: AppColors.muted),
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
    );
  }

  Widget _buildPartialDeliveryToggle(BuildContext context) {
    return AsmDriveCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ── Toggle header ──────────────────────────────────────────────
          Row(
            children: [
              const Icon(Icons.warning_amber_rounded, color: AppColors.warning),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('Livraison partielle'),
                    Text(
                      'Activez si certains articles n\'ont pas été livrés.',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(color: AppColors.muted),
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
    );
  }

  Widget _buildItemOutcomeRow(BuildContext context, dynamic item) {
    final key        = (item.sku ?? item.name) as String;
    final plannedQty = item.quantity as int;
    final currentQty = _itemsDone[key] ?? plannedQty;
    final outcome    = _itemOutcomes[key] ?? 'DELIVERED';
    final reason     = _itemReasons[key];
    final commentCtrl = _itemCommentControllers[key]!;

    // Show reason + comment when: refused, damaged, or delivered but qty < planned
    final isPartialQty  = outcome == 'DELIVERED' && currentQty < plannedQty;
    final needsExtra    = outcome == 'REFUSED' || outcome == 'DAMAGED' || isPartialQty;

    final Color outcomeColor = outcome == 'DELIVERED'
        ? (isPartialQty ? AppColors.warning : AppColors.success)
        : AppColors.danger;

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: outcomeColor.withValues(alpha: 0.35), width: 1.5),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [

          // ── Header: item name + outcome chips ────────────────────────
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 14, 14, 10),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  item.name as String,
                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14, color: AppColors.textPrimary),
                ),
                const SizedBox(height: 10),
                Row(
                  children: _outcomeLabels.entries.map((e) {
                    final selected = outcome == e.key;
                    final chipColor = e.key == 'DELIVERED' ? AppColors.success : AppColors.danger;
                    return Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: GestureDetector(
                        onTap: () => setState(() {
                          _itemOutcomes[key] = e.key;
                          _itemReasons[key]  = null;
                          _itemsDone[key]    = e.key == 'DELIVERED' ? plannedQty : 0;
                        }),
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
                          decoration: BoxDecoration(
                            color: selected ? chipColor : AppColors.surfaceElevated,
                            borderRadius: BorderRadius.circular(20),
                            border: Border.all(color: selected ? chipColor : AppColors.border),
                          ),
                          child: Text(
                            e.value,
                            style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                              color: selected ? Colors.white : AppColors.textSecondary,
                            ),
                          ),
                        ),
                      ),
                    );
                  }).toList(),
                ),
              ],
            ),
          ),

          const Divider(height: 1, color: AppColors.border),

          // ── Quantity stepper (DELIVERED only) ────────────────────────
          if (outcome == 'DELIVERED')
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              child: Row(
                children: [
                  const Text('Quantité livrée', style: TextStyle(fontSize: 13, color: AppColors.textSecondary)),
                  const Spacer(),
                  IconButton(
                    icon: const Icon(Icons.remove_circle_outline, size: 22),
                    color: currentQty > 0 ? AppColors.danger : AppColors.border,
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(),
                    onPressed: currentQty > 0
                        ? () => setState(() => _itemsDone[key] = currentQty - 1)
                        : null,
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Text(
                      '$currentQty / $plannedQty',
                      style: TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 15,
                        color: isPartialQty ? AppColors.warning : AppColors.textPrimary,
                      ),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.add_circle_outline, size: 22),
                    color: currentQty < plannedQty ? AppColors.success : AppColors.border,
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(),
                    onPressed: currentQty < plannedQty
                        ? () => setState(() => _itemsDone[key] = currentQty + 1)
                        : null,
                  ),
                ],
              ),
            )
          else
            // Refused / Damaged — qty locked at 0
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              child: Row(
                children: [
                  const Text('Quantité livrée', style: TextStyle(fontSize: 13, color: AppColors.textSecondary)),
                  const Spacer(),
                  Text('0 / $plannedQty', style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15, color: AppColors.danger)),
                ],
              ),
            ),

          // ── Reason + comment (refused, damaged, or partial qty) ──────
          if (needsExtra) ...[
            const Divider(height: 1, color: AppColors.border),
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 12, 14, 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    isPartialQty ? 'Raison (livraison partielle)' : 'Raison',
                    style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.muted),
                  ),
                  const SizedBox(height: 8),
                  // Scrollable reason chips
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: (_reasonsByOutcome[outcome] ?? _reasonsByOutcome['REFUSED']!).entries.map((e) {
                        final selected = reason == e.key;
                        return Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: GestureDetector(
                            onTap: () => setState(() => _itemReasons[key] = e.key),
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                              decoration: BoxDecoration(
                                color: selected ? AppColors.accent.withValues(alpha: 0.15) : AppColors.surfaceElevated,
                                borderRadius: BorderRadius.circular(20),
                                border: Border.all(color: selected ? AppColors.accent : AppColors.border),
                              ),
                              child: Text(
                                e.value,
                                style: TextStyle(
                                  fontSize: 12,
                                  fontWeight: FontWeight.w500,
                                  color: selected ? AppColors.accent : AppColors.textSecondary,
                                ),
                              ),
                            ),
                          ),
                        );
                      }).toList(),
                    ),
                  ),
                  const SizedBox(height: 10),
                  TextField(
                    controller: commentCtrl,
                    minLines: 1,
                    maxLines: 3,
                    style: const TextStyle(fontSize: 13),
                    decoration: InputDecoration(
                      hintText: 'Commentaire sur cet article (optionnel)',
                      hintStyle: const TextStyle(fontSize: 13, color: AppColors.muted),
                      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                      border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
                    ),
                  ),
                ],
              ),
            ),
          ],
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
          // DISABLED FOR TESTING \u2014 re-enable before production
          // // P0: Accuracy Check
          // if (point.accuracy > 100) {
          //   throw 'Pr\u00e9cision GPS insuffisante (${point.accuracy.toInt()}m). Veuillez vous d\u00e9placer vers un endroit d\u00e9gag\u00e9.';
          // }

          // // P0: Geofence Check
          // final destLat = widget.args.delivery.lat;
          // final destLng = widget.args.delivery.lng;
          // if (destLat != null && destLng != null) {
          //   final distance = _locationService.calculateDistance(
          //     point.lat, point.lng, destLat.toDouble(), destLng.toDouble());
          //   if (distance > 250) {
          //     throw 'Vous \u00eates trop loin du point de livraison (${distance.toInt()}m). Distance max autoris\u00e9e : 250m.';
          //   }
          // }

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
          // Send reason when: refused, damaged, or delivered with less than planned qty
          final sendReason = outcome == 'REFUSED' || outcome == 'DAMAGED' || isPartialQty;
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
