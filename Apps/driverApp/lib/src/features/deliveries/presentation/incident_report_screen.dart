import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:image_picker/image_picker.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';

class IncidentReportScreen extends ConsumerStatefulWidget {
  const IncidentReportScreen({super.key, this.deliveryId});
  final String? deliveryId;

  @override
  ConsumerState<IncidentReportScreen> createState() => _IncidentReportScreenState();
}

class _IncidentReportScreenState extends ConsumerState<IncidentReportScreen> {
  final _formKey = GlobalKey<FormState>();
  final _descriptionController = TextEditingController();
  final List<XFile> _images = [];
  final _picker = ImagePicker();
  
  String _reportType = 'ACCIDENT';
  bool _isWorking = false;

  final List<String> _reportTypes = [
    'ACCIDENT',
    'VEHICLE_FAILURE',
    'TRAFFIC_BLOCK',
    'POLICE_STOP',
    'PACKAGE_DAMAGED',
    'SECURITY_ISSUE',
    'OTHER',
  ];

  @override
  void dispose() {
    _descriptionController.dispose();
    super.dispose();
  }

  Future<void> _pickImage() async {
    final XFile? image = await _picker.pickImage(
      source: ImageSource.camera,
      imageQuality: 60,
    );
    if (image != null) {
      setState(() => _images.add(image));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('INCIDENT REPORTING'),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _buildTypeSelector(),
              const SizedBox(height: 32),
              
              Text('DESCRIPTION', style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.muted, letterSpacing: 1.5)),
              const SizedBox(height: 12),
              TextFormField(
                controller: _descriptionController,
                maxLines: 4,
                style: GoogleFonts.spaceGrotesk(color: AppColors.textPrimary, fontWeight: FontWeight.w600),
                decoration: const InputDecoration(
                  hintText: 'Provide as much detail as possible about the incident...',
                ),
                validator: (v) => v == null || v.length < 10 ? 'Detailed description required' : null,
              ),
              
              const SizedBox(height: 32),
              Text('EVIDENCE (PHOTOS)', style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.muted, letterSpacing: 1.5)),
              const SizedBox(height: 12),
              _buildImageGrid(),
              
              const SizedBox(height: 40),
              DriveButton(
                label: 'SUBMIT INCIDENT REPORT',
                variant: DriveButtonVariant.danger,
                fullWidth: true,
                size: DriveButtonSize.lg,
                isLoading: _isWorking,
                onPressed: _submit,
              ),
              const SizedBox(height: 12),
              const Center(
                child: Text(
                  'GPS metadata will be attached automatically.',
                  style: TextStyle(color: AppColors.muted, fontSize: 11),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTypeSelector() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('INCIDENT TYPE', style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.muted, letterSpacing: 1.5)),
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: _reportTypes.map((type) {
            final isSelected = _reportType == type;
            return ChoiceChip(
              label: Text(type.replaceAll('_', ' ')),
              selected: isSelected,
              onSelected: (val) => setState(() => _reportType = type),
              selectedColor: AppColors.danger.withValues(alpha: 0.15),
              labelStyle: GoogleFonts.spaceGrotesk(
                color: isSelected ? AppColors.danger : AppColors.textSecondary,
                fontSize: 11,
                fontWeight: isSelected ? FontWeight.w900 : FontWeight.w700,
                letterSpacing: 0.5,
              ),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(2)),
              side: BorderSide(color: isSelected ? AppColors.danger : AppColors.border, width: isSelected ? 1.5 : 1),
              backgroundColor: AppColors.surface,
            );
          }).toList(),
        ),
      ],
    );
  }

  Widget _buildImageGrid() {
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 3,
        crossAxisSpacing: 10,
        mainAxisSpacing: 10,
      ),
      itemCount: _images.length + 1,
      itemBuilder: (context, index) {
        if (index == _images.length) {
          return GestureDetector(
            onTap: _pickImage,
            child: Container(
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(4), // Tactical 4px
                border: Border.all(color: AppColors.border),
              ),
              child: const Icon(LucideIcons.camera, color: AppColors.muted),
            ),
          );
        }
        return Stack(
          children: [
            Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(4), // Tactical 4px
                border: Border.all(color: AppColors.border),
                image: DecorationImage(
                  image: FileImage(File(_images[index].path)),
                  fit: BoxFit.cover,
                ),
              ),
            ),
            Positioned(
              top: 4,
              right: 4,
              child: GestureDetector(
                onTap: () => setState(() => _images.removeAt(index)),
                child: Container(
                  padding: const EdgeInsets.all(4),
                  decoration: const BoxDecoration(color: AppColors.background, shape: BoxShape.rectangle),
                  child: const Icon(LucideIcons.x, size: 12, color: AppColors.danger),
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_images.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('At least one photo of evidence is required.'), backgroundColor: AppColors.danger),
      );
      return;
    }

    setState(() => _isWorking = true);
    try {
      final point = await LocationService().currentPosition();
      
      // Convert images to base64
      final List<String> base64Photos = [];
      for (final image in _images) {
        final bytes = await File(image.path).readAsBytes();
        base64Photos.add(base64Encode(bytes));
      }

      await ref.read(deliveryRepositoryProvider).reportIncident(
        reportType: _reportType,
        description: _descriptionController.text,
        photosBase64: base64Photos,
        deliveryId: widget.deliveryId,
        lat: point?.lat,
        lng: point?.lng,
      );

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Incident report submitted. Ops team has been notified.')),
        );
        Navigator.pop(context);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Submission failed: $e'), backgroundColor: AppColors.danger),
        );
      }
    } finally {
      if (mounted) setState(() => _isWorking = false);
    }
  }
}
