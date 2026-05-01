import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';

class VehicleInspectionScreen extends ConsumerStatefulWidget {
  const VehicleInspectionScreen({super.key});

  @override
  ConsumerState<VehicleInspectionScreen> createState() => _VehicleInspectionScreenState();
}

class _VehicleInspectionScreenState extends ConsumerState<VehicleInspectionScreen> {
  final _formKey = GlobalKey<FormState>();
  
  double _odometer = 0;
  double _fuelLevel = 0.5;
  bool _tiresOk = false;
  bool _brakesOk = false;
  bool _lightsOk = false;
  String _comments = '';
  bool _isSubmitting = false;

  @override
  Widget build(BuildContext context) {
    final vehicleAsync = ref.watch(myVehicleProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('VEHICLE SAFETY CHECK'),
        actions: [
          IconButton(
            icon: const Icon(LucideIcons.helpCircle, size: 20),
            onPressed: () {},
          ),
        ],
      ),
      body: vehicleAsync.when(
        loading: () => const LoadingState(message: 'Identifying vehicle...'),
        error: (err, stack) => EmptyState(
          icon: LucideIcons.alertTriangle,
          title: 'NO VEHICLE ASSIGNED',
          subtitle: 'IDENTIFICATION FAILURE. STAND BY FOR DISPATCH COMMANDS.',
          actionLabel: 'RETRY SCAN',
          action: () => ref.refresh(myVehicleProvider),
        ),
        data: (vehicle) => _buildForm(vehicle),
      ),
    );
  }

  Widget _buildForm(Map<String, dynamic> vehicle) {
    return Column(
      children: [
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: Form(
              key: _formKey,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _buildVehicleHeader(vehicle),
                  const SizedBox(height: 32),
                  Text('SAFETY CHECKLIST', style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.muted, letterSpacing: 1.5)),
                  const SizedBox(height: 12),
                  
                  _buildCheckItem(
                    icon: LucideIcons.circleDot,
                    label: 'Tires Condition & Pressure',
                    subtitle: 'Check for wear and proper inflation',
                    value: _tiresOk,
                    onChanged: (v) => setState(() => _tiresOk = v!),
                  ),
                  _buildCheckItem(
                    icon: LucideIcons.shieldCheck,
                    label: 'Brakes & Fluid Levels',
                    subtitle: 'Ensure responsive stopping power',
                    value: _brakesOk,
                    onChanged: (v) => setState(() => _brakesOk = v!),
                  ),
                  _buildCheckItem(
                    icon: LucideIcons.sun,
                    label: 'Exterior & Interior Lights',
                    subtitle: 'Headlights, indicators, and brake lights',
                    value: _lightsOk,
                    onChanged: (v) => setState(() => _lightsOk = v!),
                  ),
                  
                  const SizedBox(height: 32),
                  Text('METRICS', style: Theme.of(context).textTheme.labelSmall),
                  const SizedBox(height: 16),
                  
                  _buildOdometerField(),
                  const SizedBox(height: 24),
                  
                  _buildFuelLevelSelector(),
                  
                  const SizedBox(height: 32),
                  Text('ADDITIONAL COMMENTS', style: Theme.of(context).textTheme.labelSmall),
                  const SizedBox(height: 12),
                  
                  TextFormField(
                    decoration: const InputDecoration(
                      hintText: 'Any issues or notes...',
                    ),
                    maxLines: 3,
                    onChanged: (v) => _comments = v,
                  ),
                  const SizedBox(height: 40),
                ],
              ),
            ),
          ),
        ),
        _buildBottomAction(vehicle['id']),
      ],
    );
  }

  Widget _buildVehicleHeader(Map<String, dynamic> vehicle) {
    return DriveCard(
      color: AppColors.surfaceElevated,
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: AppColors.neonYellow.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(4),
            ),
            child: const Icon(LucideIcons.truck, color: AppColors.neonYellow, size: 24),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  (vehicle['model'] ?? 'STANDARD UNIT').toUpperCase(),
                  style: GoogleFonts.spaceGrotesk(fontWeight: FontWeight.w900, fontSize: 16, letterSpacing: 0.5),
                ),
                const SizedBox(height: 2),
                Text(
                  vehicle['plate'] ?? 'No Plate',
                  style: const TextStyle(color: AppColors.muted, fontSize: 13, fontWeight: FontWeight.w600, letterSpacing: 1),
                ),
              ],
            ),
          ),
          StatusBadge(label: 'ASSIGNED', color: AppColors.neonYellow),
        ],
      ),
    );
  }

  Widget _buildCheckItem({
    required IconData icon,
    required String label,
    required String subtitle,
    required bool value,
    required ValueChanged<bool?> onChanged,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: DriveCard(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        onTap: () => onChanged(!value),
        child: Row(
          children: [
            Icon(icon, size: 20, color: value ? AppColors.neonYellow : AppColors.muted),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(label, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
                  Text(subtitle, style: const TextStyle(color: AppColors.muted, fontSize: 12)),
                ],
              ),
            ),
            Checkbox(
              value: value,
              onChanged: onChanged,
              activeColor: AppColors.neonYellow,
              checkColor: Colors.black,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildOdometerField() {
    return TextFormField(
      keyboardType: TextInputType.number,
      decoration: const InputDecoration(
        labelText: 'ODOMETER READING (KM)',
        prefixIcon: Icon(LucideIcons.gauge),
      ),
      validator: (v) {
        if (v == null || v.isEmpty) return 'Current mileage is required';
        if (double.tryParse(v) == null) return 'Invalid number';
        return null;
      },
      onChanged: (v) => _odometer = double.tryParse(v) ?? 0,
    );
  }

  Widget _buildFuelLevelSelector() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text('FUEL LEVEL', style: GoogleFonts.spaceGrotesk(fontSize: 12, fontWeight: FontWeight.w800, color: AppColors.textPrimary)),
            Text('${(_fuelLevel * 100).toInt()}%', style: GoogleFonts.spaceGrotesk(color: AppColors.neonYellow, fontWeight: FontWeight.w900, fontSize: 14)),
          ],
        ),
        const SizedBox(height: 8),
        SliderTheme(
          data: SliderTheme.of(context).copyWith(
            activeTrackColor: AppColors.neonYellow,
            inactiveTrackColor: AppColors.surfaceDim,
            thumbColor: AppColors.neonYellow,
            overlayColor: AppColors.neonYellow.withValues(alpha: 0.1),
          ),
          child: Slider(
            value: _fuelLevel,
            onChanged: (v) => setState(() => _fuelLevel = v),
          ),
        ),
      ],
    );
  }

  Widget _buildBottomAction(String vehicleId) {
    final canSubmit = _tiresOk && _brakesOk && _lightsOk && _odometer > 0;

    return Container(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
      decoration: BoxDecoration(
        color: AppColors.surface,
        border: Border(top: BorderSide(color: AppColors.border, width: 1.5)),
      ),
      child: DriveButton(
        label: 'SUBMIT INSPECTION',
        fullWidth: true,
        size: DriveButtonSize.lg,
        isLoading: _isSubmitting,
        onPressed: canSubmit ? () => _submit(vehicleId) : null,
      ),
    );
  }

  Future<void> _submit(String vehicleId) async {
    if (!_formKey.currentState!.validate()) return;
    
    setState(() => _isSubmitting = true);
    try {
      await ref.read(vehicleServiceProvider).submitInspection(
        vehicleId: vehicleId,
        odometer: _odometer,
        fuelLevel: _fuelLevel,
        tiresOk: _tiresOk,
        brakesOk: _brakesOk,
        lightsOk: _lightsOk,
        comments: _comments,
      );
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Safety check submitted. Drive safe!')),
        );
        Navigator.pop(context, true); // Return true to indicate success
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Submission failed: $e'), backgroundColor: AppColors.danger),
        );
      }
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }
}
