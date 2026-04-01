import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:signature/signature.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
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
  final _signatureController = SignatureController(penStrokeWidth: 3, penColor: Colors.white);
  final _notesController = TextEditingController();
  final _picker = ImagePicker();
  final _locationService = LocationService();

  Uint8List? _photoBytes;
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
    _signatureController.dispose();
    _notesController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        title: const Text('Proof of Delivery'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new_rounded, size: 18),
          onPressed: () => Navigator.of(context).pop(),
        ),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Text(
              'Collect signature, capture visual evidence and transmit it back to HQ.',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: AppColors.muted),
            ),
            const SizedBox(height: 20),
            _buildSignaturePad(context),
            const SizedBox(height: 20),
            _buildPhotoBlock(context),
            const SizedBox(height: 20),
            _buildNotesField(context),
            const SizedBox(height: 20),
            _buildLocationToggle(context),
            const SizedBox(height: 20),
            _buildPartialDeliveryToggle(context),
            const SizedBox(height: 24),
            AsmDriveButton(
              label: 'Submit proof',
              icon: Icons.check,
              isLoading: _submitting,
              onPressed: _submitting ? null : _submit,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSignaturePad(BuildContext context) {
    return AsmDriveCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Recipient signature', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 12),
          ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: Container(
              color: Colors.black,
              height: 220,
              child: Signature(
                controller: _signatureController,
                backgroundColor: Colors.transparent,
              ),
            ),
          ),
          const SizedBox(height: 12),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              onPressed: () => _signatureController.clear(),
              icon: const Icon(Icons.restart_alt),
              label: const Text('Clear strokes'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPhotoBlock(BuildContext context) {
    return AsmDriveCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Visual evidence', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 12),
          if (_photoBytes != null)
            ClipRRect(
              borderRadius: BorderRadius.circular(14),
              child: Image.memory(
                _photoBytes!,
                height: 180,
                width: double.infinity,
                fit: BoxFit.cover,
              ),
            )
          else
            Container(
              height: 180,
              decoration: BoxDecoration(
                color: AppColors.surfaceElevated,
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: AppColors.border),
              ),
              child: const Center(
                child: Text('No photo attached'),
              ),
            ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 12,
            runSpacing: 8,
            children: [
              OutlinedButton.icon(
                onPressed: () => _pickPhoto(ImageSource.camera),
                icon: const Icon(Icons.camera_alt_outlined),
                label: const Text('Camera'),
              ),
              OutlinedButton.icon(
                onPressed: () => _pickPhoto(ImageSource.gallery),
                icon: const Icon(Icons.photo_outlined),
                label: const Text('Library'),
              ),
              if (_photoBytes != null)
                TextButton.icon(
                  onPressed: () => setState(() => _photoBytes = null),
                  icon: const Icon(Icons.delete_outline),
                  label: const Text('Remove'),
                ),
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
          labelText: 'Comments (door code, person name, etc.)',
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
                const Text('Attach GPS snapshot'),
                Text(
                  'We send coordinates once when you submit the proof.',
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
                    const Text('Partial Delivery'),
                    Text(
                      'Mark if not all items were delivered.',
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
            Text('Adjust delivered quantities:', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 12),
            ...widget.args.delivery.items.map((item) {
              final key = item.sku ?? item.name;
              final currentQty = _itemsDone[key] ?? item.quantity;
              return Row(
                children: [
                  Expanded(
                    child: Text(item.name, maxLines: 1, overflow: TextOverflow.ellipsis),
                  ),
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

  Future<void> _pickPhoto(ImageSource source) async {
    try {
      final file = await _picker.pickImage(source: source, imageQuality: 80);
      if (file == null) return;
      final bytes = await file.readAsBytes();
      if (!mounted) return;
      setState(() => _photoBytes = bytes);
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Unable to access ${source.name}')),
      );
    }
  }

  Future<void> _submit() async {
    if (_signatureController.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Capture recipient signature before submitting.')));
      return;
    }
    setState(() => _submitting = true);
    try {
      final signatureBytes = await _signatureController.toPngBytes();
      if (signatureBytes == null) {
        throw Exception('Signature missing');
      }
      final signatureBase64 = base64Encode(signatureBytes);
      final photoBase64 = _photoBytes != null ? base64Encode(_photoBytes!) : null;
      double? lat;
      double? lng;
      if (_attachLocation) {
        final point = await _locationService.currentPosition();
        lat = point?.lat;
        lng = point?.lng;
      }

      List<PartialDeliveryItem>? itemsArray;
      if (_isPartial) {
        itemsArray = _itemsDone.entries.map((e) => PartialDeliveryItem(sku: e.key, quantityDone: e.value)).toList();
      }

      final payload = PodPayload(
        signatureBase64: signatureBase64,
        photoBase64: photoBase64,
        comment: _notesController.text.trim().isEmpty ? null : _notesController.text.trim(),
        lat: lat,
        lng: lng,
        isPartial: _isPartial,
        itemsDone: itemsArray,
      );
      await ref.read(deliveryRepositoryProvider).submitPod(widget.args.delivery.id, payload);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Proof transmitted.')));
      Navigator.of(context).pop(true);
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Failed to submit POD: $error')));
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }
}
