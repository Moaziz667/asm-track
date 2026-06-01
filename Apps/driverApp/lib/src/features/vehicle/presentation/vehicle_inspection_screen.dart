import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';
import '../../../theme/widgets.dart';

class VehicleInspectionScreen extends ConsumerStatefulWidget {
  const VehicleInspectionScreen({super.key});

  @override
  ConsumerState<VehicleInspectionScreen> createState() => _VehicleInspectionScreenState();
}

class _VehicleInspectionScreenState extends ConsumerState<VehicleInspectionScreen> {
  final _formKey = GlobalKey<FormState>(debugLabel: 'vehicle_inspection_form');
  
  double _odometer = 0;
  double _fuelLevel = 0.5;
  bool _tiresOk = false;
  bool _brakesOk = false;
  bool _lightsOk = false;
  String _comments = '';
  bool _isSubmitting = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final vehicleAsync = ref.watch(myVehicleProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Controle de securite du vehicule'),
        actions: [
          IconButton(
            icon: const Icon(LucideIcons.helpCircle, size: 20),
            onPressed: () {},
          ),
        ],
      ),
      body: vehicleAsync.when(
        loading: () => const LoadingState(message: 'Identification du vehicule...'),
        error: (err, stack) => EmptyState(
          icon: LucideIcons.alertTriangle,
          title: 'Aucun vehicule assigne',
          subtitle: 'Echec d\'identification. En attente des instructions du dispatch.',
          actionLabel: 'Reessayer le scan',
          action: () => ref.refresh(myVehicleProvider),
        ),
        data: (vehicle) => _buildForm(vehicle, theme, colorScheme),
      ),
    );
  }

  Widget _buildForm(Map<String, dynamic> vehicle, ThemeData theme, ColorScheme colorScheme) {
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
                  _buildVehicleHeader(vehicle, theme, colorScheme),
                  const SizedBox(height: 32),
                  Text('Liste de controle', style: theme.textTheme.labelMedium),
                  const SizedBox(height: 12),
                  
                  _buildCheckItem(
                    icon: LucideIcons.circleDot,
                    label: 'Etat et pression des pneus',
                    subtitle: 'Verifier l\'usure et le gonflage',
                    value: _tiresOk,
                    onChanged: (v) => setState(() => _tiresOk = v!),
                    colorScheme: colorScheme,
                  ),
                  _buildCheckItem(
                    icon: LucideIcons.shieldCheck,
                    label: 'Freins et niveaux de fluides',
                    subtitle: 'S\'assurer de la bonne reactivite',
                    value: _brakesOk,
                    onChanged: (v) => setState(() => _brakesOk = v!),
                    colorScheme: colorScheme,
                  ),
                  _buildCheckItem(
                    icon: LucideIcons.sun,
                    label: 'Eclairage exterieur et interieur',
                    subtitle: 'Phares, clignotants et feux de stop',
                    value: _lightsOk,
                    onChanged: (v) => setState(() => _lightsOk = v!),
                    colorScheme: colorScheme,
                  ),
                  
                  const SizedBox(height: 32),
                  Text('Metriques', style: theme.textTheme.labelMedium),
                  const SizedBox(height: 16),
                  
                  _buildOdometerField(theme, colorScheme),
                  const SizedBox(height: 24),
                  
                  _buildFuelLevelSelector(colorScheme),
                  
                  const SizedBox(height: 32),
                  Text('Commentaires additionnels', style: theme.textTheme.labelMedium),
                  const SizedBox(height: 12),
                  
                  TextFormField(
                    decoration: const InputDecoration(
                      hintText: 'Problemes eventuels ou remarques...',
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
        _buildBottomAction(vehicle['id'], colorScheme),
      ],
    );
  }

  Widget _buildVehicleHeader(Map<String, dynamic> vehicle, ThemeData theme, ColorScheme colorScheme) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(LucideIcons.truck, color: colorScheme.primary, size: 24),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    vehicle['model'] ?? 'Unite standard',
                    style: theme.textTheme.titleMedium,
                  ),
                  const SizedBox(height: 2),
                  Text(
                    vehicle['plate'] ?? 'Sans plaque',
                    style: theme.textTheme.bodySmall?.copyWith(color: colorScheme.onSurfaceVariant),
                  ),
                ],
              ),
            ),
            Chip(
              label: Text('Assigné', style: TextStyle(fontSize: 11, color: colorScheme.primary)),
              backgroundColor: colorScheme.primaryContainer,
              side: BorderSide.none,
              visualDensity: VisualDensity.compact,
              materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCheckItem({
    required IconData icon,
    required String label,
    required String subtitle,
    required bool value,
    required ValueChanged<bool?> onChanged,
    required ColorScheme colorScheme,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Card(
        child: InkWell(
          onTap: () => onChanged(!value),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            child: Row(
              children: [
                Icon(icon, size: 20, color: value ? colorScheme.primary : colorScheme.onSurfaceVariant),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(label, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
                      Text(subtitle, style: TextStyle(color: colorScheme.onSurfaceVariant, fontSize: 12)),
                    ],
                  ),
                ),
                Checkbox(
                  value: value,
                  onChanged: onChanged,
                  activeColor: colorScheme.primary,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildOdometerField(ThemeData theme, ColorScheme colorScheme) {
    return TextFormField(
      keyboardType: TextInputType.number,
      decoration: InputDecoration(
        labelText: 'Kilometrage (km)',
        prefixIcon: const Icon(LucideIcons.gauge),
      ),
      validator: (v) {
        if (v == null || v.isEmpty) return 'Le kilometrage actuel est requis';
        if (double.tryParse(v) == null) return 'Nombre invalide';
        return null;
      },
      onChanged: (v) => _odometer = double.tryParse(v) ?? 0,
    );
  }

  Widget _buildFuelLevelSelector(ColorScheme colorScheme) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text('Niveau de carburant'),
            Text('${(_fuelLevel * 100).toInt()}%', style: TextStyle(color: colorScheme.primary, fontWeight: FontWeight.w900)),
          ],
        ),
        const SizedBox(height: 8),
        Slider(
          value: _fuelLevel,
          onChanged: (v) => setState(() => _fuelLevel = v),
          activeColor: colorScheme.primary,
        ),
      ],
    );
  }

  Widget _buildBottomAction(String vehicleId, ColorScheme colorScheme) {
    final canSubmit = _tiresOk && _brakesOk && _lightsOk && _odometer > 0;

    return Container(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
      decoration: BoxDecoration(
        border: Border(top: BorderSide(color: colorScheme.outlineVariant, width: 1)),
      ),
      child: SizedBox(
        width: double.infinity,
        height: 52,
        child: FilledButton.icon(
          onPressed: canSubmit ? () => _submit(vehicleId) : null,
          icon: _isSubmitting
              ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(LucideIcons.checkCircle),
          label: Text(_isSubmitting ? 'Soumission...' : 'Soumettre le controle'),
        ),
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
          const SnackBar(content: Text('Controle de securite soumis. Bonne route !')),
        );
        Navigator.pop(context, true);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Echec de la soumission : $e'), backgroundColor: Theme.of(context).colorScheme.error),
        );
      }
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }
}
