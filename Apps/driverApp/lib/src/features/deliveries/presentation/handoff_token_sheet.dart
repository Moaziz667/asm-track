import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';
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
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.fromLTRB(24, 12, 24, 40),
      decoration: BoxDecoration(
        color: cs.surface,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(4)), // Tactical 4px
        border: Border(top: BorderSide(color: cs.outlineVariant, width: 2)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 40,
            height: 4,
            decoration: BoxDecoration(color: cs.outlineVariant, borderRadius: BorderRadius.circular(2)),
          ),
          const SizedBox(height: 24),
          Icon(LucideIcons.arrowLeftRight, color: cs.primary, size: 32),
          const SizedBox(height: 16),
          Text(
            'Authentification du transfert',
            style: Theme.of(context).textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.w900,
              color: cs.onSurface,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            'Demandez à l\'autre chauffeur de scanner ce code pour confirmer le transfert de responsabilité.',
            style: TextStyle(color: cs.onSurfaceVariant, fontSize: 13),
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
          SizedBox(
            width: double.infinity,
            child: TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Fermer'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildQr(String token) {
    final cs = Theme.of(context).colorScheme;
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
            color: cs.surfaceContainerLow,
            borderRadius: BorderRadius.circular(4),
            border: Border.all(color: cs.primary, width: 1),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                'Jeton : ',
                style: Theme.of(context).textTheme.labelLarge?.copyWith(fontSize: 12, fontWeight: FontWeight.w700, color: cs.onSurfaceVariant),
              ),
              Text(
                token,
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontSize: 22, fontWeight: FontWeight.w900, color: cs.primary),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(LucideIcons.clock, size: 14, color: cs.onSurfaceVariant),
            const SizedBox(width: 6),
            Text('Expire dans 5 minutes', style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12)),
          ],
        ),
      ],
    );
  }

  Widget _buildError() {
    final cs = Theme.of(context).colorScheme;
    return Column(
      children: [
        Icon(LucideIcons.alertCircle, color: cs.error, size: 48),
        const SizedBox(height: 16),
        Text(_error!, style: TextStyle(color: cs.onSurfaceVariant), textAlign: TextAlign.center),
        const SizedBox(height: 24),
        SizedBox(
          width: double.infinity,
          height: 36,
          child: FilledButton(
            onPressed: _fetchToken,
            child: const Text('Réessayer'),
          ),
        ),
      ],
    );
  }
}
