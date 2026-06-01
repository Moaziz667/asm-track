import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';
import '../../../theme/widgets.dart';

class HandoffScannerScreen extends ConsumerStatefulWidget {
  const HandoffScannerScreen({super.key});

  @override
  ConsumerState<HandoffScannerScreen> createState() => _HandoffScannerScreenState();
}

class _HandoffScannerScreenState extends ConsumerState<HandoffScannerScreen> {
  final MobileScannerController _controller = MobileScannerController();
  bool _isProcessing = false;
  bool _ready = false;

  @override
  void initState() {
    super.initState();
    // Brief delay so camera initialises fully before accepting any scan.
    // Prevents firing on whatever happens to be in frame when the screen opens.
    Future.delayed(const Duration(milliseconds: 1500), () {
      if (mounted) setState(() => _ready = true);
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          MobileScanner(
            controller: _controller,
            onDetect: (capture) {
              if (!_ready || _isProcessing) return;
              final String? code = capture.barcodes.firstOrNull?.rawValue;
              if (code != null) _processToken(code);
            },
          ),
          
          // Tactical Overlay
          _buildOverlay(),
          
          // Back Button
          Positioned(
            top: 50,
            left: 20,
            child: IconButton(
              icon: const Icon(LucideIcons.x, color: Colors.white, size: 28),
              onPressed: () => Navigator.pop(context),
            ),
          ),

          // Bottom Info
          Positioned(
            bottom: 60,
            left: 30,
            right: 30,
            child: Column(
              children: [
                Icon(LucideIcons.scan, color: cs.primary, size: 32),
                const SizedBox(height: 16),
                Text(
                  'Scanner le QR de l\'expéditeur',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900, color: Colors.white, fontFamily: 'Inter'),
                ),
                const SizedBox(height: 8),
                Text(
                  'Alignez le code QR de l\'autre chauffeur dans le cadre pour confirmer le transfert de responsabilité.',
                  style: TextStyle(color: Colors.white.withValues(alpha: 0.7), fontSize: 13),
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
          
          if (_isProcessing)
            Container(
              color: Colors.black87,
              child: const LoadingState(message: 'Validation du transfert…'),
            ),
        ],
      ),
    );
  }

  Widget _buildOverlay() {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Container(
        width: 250,
        height: 250,
        decoration: BoxDecoration(
          border: Border.all(color: cs.primary.withValues(alpha: 0.3), width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Stack(
          children: [
            Positioned(top: 0, left: 0, child: _corner(0)),
            Positioned(top: 0, right: 0, child: _corner(1.5708)),
            Positioned(bottom: 0, right: 0, child: _corner(3.14159)),
            Positioned(bottom: 0, left: 0, child: _corner(4.71239)),
          ],
        ),
      ),
    );
  }

  Widget _corner(double angle) {
    final cs = Theme.of(context).colorScheme;
    return Transform.rotate(
      angle: angle,
      child: Container(
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          border: Border(
            top: BorderSide(color: cs.primary, width: 6),
            left: BorderSide(color: cs.primary, width: 6),
          ),
          borderRadius: const BorderRadius.only(topLeft: Radius.circular(2)),
        ),
      ),
    );
  }

  Future<void> _processToken(String token) async {
    setState(() => _isProcessing = true);
    try {
      String deliveryId = "";
      String cleanToken = token;
      
      if (token.contains('|')) {
        final parts = token.split('|');
        deliveryId = parts[0];
        cleanToken = parts[1];
      } else {
        throw Exception('Invalid QR format. Expected deliveryId|token');
      }

      await ref.read(deliveryRepositoryProvider).confirmHandoff(deliveryId, cleanToken);

      // Stop scanner immediately to prevent duplicate detections
      await _controller.stop();

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Transfert réussi ! Le colis a été transféré.')),
        );
        Navigator.pop(context, true);
      }
    } catch (e) {
      if (mounted) {
        final cs = Theme.of(context).colorScheme;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Échec du transfert : $e'), backgroundColor: cs.error),
        );
        setState(() => _isProcessing = false);
      }
    }
  }
}
