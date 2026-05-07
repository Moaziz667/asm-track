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
  late Map<String, int> _itemsDone;

  @override
  void initState() {
    super.initState();
    _itemsDone = {};
    for (final item in widget.args.delivery.items) {
      final key = item.sku ?? item.name;
      _itemsDone[key] = item.quantity;
    }
  }

  @override
  void dispose() {
    _notesController.dispose();
    super.dispose();
  }

  bool get _canSubmit => _bonLivraisonBytes != null && _packageBytes != null;

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
                  'Les deux photos sont obligatoires pour soumettre le POD.',
                  style: TextStyle(fontSize: 12, color: AppColors.danger.withValues(alpha: 0.8)),
                  textAlign: TextAlign.center,
                ),
              ),

            AsmDriveButton(
              label: 'Soumettre le POD',
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
                      'Cochez si tous les articles n\'ont pas été livrés.',
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
          if (_isPartial) ...[
            const Divider(height: 24),
            Text('Ajuster les quantités livrées :', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 12),
            ...widget.args.delivery.items.map((item) {
              final key = item.sku ?? item.name;
              final currentQty = _itemsDone[key] ?? item.quantity;
              return Row(
                children: [
                  Expanded(child: Text(item.name, maxLines: 1, overflow: TextOverflow.ellipsis)),
                  Row(
                    children: [
                      IconButton(
                        icon: const Icon(Icons.remove_circle_outline),
                        onPressed: currentQty > 0
                            ? () => setState(() => _itemsDone[key] = currentQty - 1)
                            : null,
                      ),
                      Text('$currentQty', style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                      IconButton(
                        icon: const Icon(Icons.add_circle_outline),
                        onPressed: currentQty < item.quantity
                            ? () => setState(() => _itemsDone[key] = currentQty + 1)
                            : null,
                      ),
                    ],
                  ),
                ],
              );
            }),
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
        lat = point?.lat;
        lng = point?.lng;
      }

      List<PartialDeliveryItem>? itemsArray;
      if (_isPartial) {
        itemsArray = _itemsDone.entries
            .map((e) => PartialDeliveryItem(sku: e.key, quantityDone: e.value))
            .toList();
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

      // Offline check
      final isOnline = await ref.read(connectivityServiceProvider).isOnline;
      if (!isOnline) {
        ref.read(offlineQueueProvider.notifier).enqueueRequest(
          path: '/api/driver/deliveries/${widget.args.delivery.id}/pod',
          method: 'POST',
          data: payload.toJson(),
        );
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Preuve de livraison enregistr\u00e9e hors ligne')),
        );
        Navigator.of(context).pop(true);
        return;
      }

      await ref.read(deliveryRepositoryProvider).submitPod(widget.args.delivery.id, payload);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('POD transmis avec succ\u00e8s.')));
      Navigator.of(context).pop(true);
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('\u00c9chec de soumission du POD : $error')));
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }
}
