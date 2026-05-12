import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';

class HandoffScannerScreen extends ConsumerStatefulWidget {
  const HandoffScannerScreen({super.key});

  @override
  ConsumerState<HandoffScannerScreen> createState() => _HandoffScannerScreenState();
}

class _HandoffScannerScreenState extends ConsumerState<HandoffScannerScreen> {
  final MobileScannerController _controller = MobileScannerController();
  bool _isProcessing = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          MobileScanner(
            controller: _controller,
            onDetect: (capture) {
              final List<Barcode> barcodes = capture.barcodes;
              if (barcodes.isNotEmpty) {
                final String? code = barcodes.first.rawValue;
                if (code != null && !_isProcessing) {
                  _processToken(code);
                }
              }
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
                const Icon(LucideIcons.scan, color: AppColors.neonYellow, size: 32),
                const SizedBox(height: 16),
                Text(
                  'SCANNER LE QR DE L\'EXPÉDITEUR',
                  style: GoogleFonts.spaceGrotesk(fontSize: 18, fontWeight: FontWeight.w900, color: Colors.white, letterSpacing: 1),
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
    return Center(
      child: Container(
        width: 250,
        height: 250,
        decoration: BoxDecoration(
          border: Border.all(color: AppColors.neonYellow.withValues(alpha: 0.3), width: 1),
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
    return Transform.rotate(
      angle: angle,
      child: Container(
        width: 40,
        height: 40,
        decoration: const BoxDecoration(
          border: Border(
            top: BorderSide(color: AppColors.neonYellow, width: 6),
            left: BorderSide(color: AppColors.neonYellow, width: 6),
          ),
          borderRadius: BorderRadius.only(topLeft: Radius.circular(2)),
        ),
      ),
    );
  }

  Future<void> _processToken(String token) async {
    setState(() => _isProcessing = true);
    try {
      // In a real app, the QR might contain more than just the token (e.g. deliveryId:token)
      // For now we assume token is the 6-char code.
      // We need to know which delivery it is. 
      // Option A: QR contains "deliveryId|token"
      // Option B: QR contains only "token" and we try to find a matching delivery in the active list.
      
      String deliveryId = "";
      String cleanToken = token;
      
      if (token.contains('|')) {
        final parts = token.split('|');
        deliveryId = parts[0];
        cleanToken = parts[1];
      } else {
        // Fallback: try to find a delivery that matches if possible, but backend needs deliveryId.
        // Let's assume the QR format is "deliveryId|token"
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
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Échec du transfert : $e'), backgroundColor: AppColors.danger),
        );
        setState(() => _isProcessing = false);
      }
    }
  }
}
