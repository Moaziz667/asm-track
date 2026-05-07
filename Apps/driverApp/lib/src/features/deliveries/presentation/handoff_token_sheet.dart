import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';

class HandoffTokenSheet extends ConsumerStatefulWidget {
  const HandoffTokenSheet({super.key, required this.deliveryId});
  final String deliveryId;

  @override
  ConsumerState<HandoffTokenSheet> createState() => _HandoffTokenSheetState();
}

class _HandoffTokenSheetState extends ConsumerState<HandoffTokenSheet> {
  String? _token;
  String? _error;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _fetchToken();
  }

  Future<void> _fetchToken() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      final token = await ref.read(deliveryRepositoryProvider).getHandoffToken(widget.deliveryId);
      if (mounted) setState(() => _token = token);
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(24, 12, 24, 40),
      decoration: const BoxDecoration(
        color: AppColors.background,
        borderRadius: BorderRadius.vertical(top: Radius.circular(4)), // Tactical 4px
        border: Border(top: BorderSide(color: AppColors.border, width: 2)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 40,
            height: 4,
            decoration: BoxDecoration(color: AppColors.border, borderRadius: BorderRadius.circular(2)),
          ),
          const SizedBox(height: 24),
          const Icon(LucideIcons.arrowLeftRight, color: AppColors.neonYellow, size: 32),
          const SizedBox(height: 16),
          Text(
            'AUTHENTIFICATION DU TRANSFERT',
            style: GoogleFonts.spaceGrotesk(fontSize: 18, fontWeight: FontWeight.w900, color: AppColors.textPrimary, letterSpacing: 0.5),
          ),
          const SizedBox(height: 4),
          const Text(
            'Demandez à l\'autre chauffeur de scanner ce code pour confirmer le transfert de responsabilité.',
            style: TextStyle(color: AppColors.muted, fontSize: 13),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 32),
          
          if (_isLoading)
            const SizedBox(height: 200, child: LoadingState(message: 'Génération du jeton sécurisé…'))
          else if (_error != null)
            _buildError()
          else if (_token != null)
            _buildQr(_token!)
          else
            const SizedBox(height: 200),

          const SizedBox(height: 32),
          DriveButton(
            label: 'FERMER',
            variant: DriveButtonVariant.ghost,
            fullWidth: true,
            onPressed: () => Navigator.pop(context),
          ),
        ],
      ),
    );
  }

  Widget _buildQr(String token) {
    return Column(
      children: [
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(4), // Sharp corners
          ),
          child: QrImageView(
            data: '${widget.deliveryId}|$token',
            version: QrVersions.auto,
            size: 200.0,
            gapless: false,
          ),
        ),
        const SizedBox(height: 24),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(4),
            border: Border.all(color: AppColors.neonYellow, width: 1),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                'JETON : ',
                style: GoogleFonts.spaceGrotesk(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.muted, letterSpacing: 1),
              ),
              Text(
                token,
                style: GoogleFonts.spaceGrotesk(fontSize: 22, fontWeight: FontWeight.w900, color: AppColors.neonYellow, letterSpacing: 4),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        const Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(LucideIcons.clock, size: 14, color: AppColors.muted),
            SizedBox(width: 6),
            Text('Expire dans 5 minutes', style: TextStyle(color: AppColors.muted, fontSize: 12)),
          ],
        ),
      ],
    );
  }

  Widget _buildError() {
    return Column(
      children: [
        const Icon(LucideIcons.alertCircle, color: AppColors.danger, size: 48),
        const SizedBox(height: 16),
        Text(_error!, style: const TextStyle(color: AppColors.textSecondary), textAlign: TextAlign.center),
        const SizedBox(height: 24),
        DriveButton(label: 'RÉESSAYER', onPressed: _fetchToken, size: DriveButtonSize.sm),
      ],
    );
  }
}
