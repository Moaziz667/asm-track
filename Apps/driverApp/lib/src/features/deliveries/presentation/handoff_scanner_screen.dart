import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../../theme/widgets.dart';

/// Phases of the scan flow. The screen deliberately moves through a short
/// "locking → validating → success" sequence rather than popping the instant a
/// code lands in frame — a smoother, more trustworthy hand-off experience.
enum _ScanPhase { scanning, locked, validating, success }

class HandoffScannerScreen extends ConsumerStatefulWidget {
  const HandoffScannerScreen({super.key});

  @override
  ConsumerState<HandoffScannerScreen> createState() => _HandoffScannerScreenState();
}

class _HandoffScannerScreenState extends ConsumerState<HandoffScannerScreen>
    with TickerProviderStateMixin {
  static const double _frameSize = 260;

  final MobileScannerController _controller = MobileScannerController(
    detectionSpeed: DetectionSpeed.noDuplicates,
  );

  // Smooth vertical sweep of the scan line.
  late final AnimationController _sweep =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 2200))
        ..repeat(reverse: true);

  // One-shot pop animation for the success checkmark.
  late final AnimationController _successPop =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 520));

  _ScanPhase _phase = _ScanPhase.scanning;
  bool _ready = false;
  bool _torchOn = false;

  @override
  void initState() {
    super.initState();
    // Brief delay so the camera initialises fully before accepting any scan —
    // prevents firing on whatever happens to be in frame when the screen opens.
    Future.delayed(const Duration(milliseconds: 1200), () {
      if (mounted) setState(() => _ready = true);
    });
  }

  @override
  void dispose() {
    _sweep.dispose();
    _successPop.dispose();
    _controller.dispose();
    super.dispose();
  }

  bool get _busy => _phase != _ScanPhase.scanning;

  void _onDetect(BarcodeCapture capture) {
    if (!_ready || _busy) return;
    final String? code = capture.barcodes.firstOrNull?.rawValue;
    if (code != null) _processToken(code);
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
            onDetect: _onDetect,
            errorBuilder: (context, error, _) => _CameraError(error: error),
          ),

          // Dimmed surround with a clear cut-out window over the frame.
          _ScrimOverlay(frameSize: _frameSize),

          // Animated frame + sweeping scan line.
          Center(
            child: SizedBox(
              width: _frameSize,
              height: _frameSize,
              child: _buildFrame(cs),
            ),
          ),

          // Top bar: close + torch.
          Positioned(
            top: 50,
            left: 12,
            right: 12,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                IconButton(
                  icon: const Icon(LucideIcons.x, color: Colors.white, size: 28),
                  onPressed: () => Navigator.pop(context),
                ),
                IconButton(
                  icon: Icon(
                    _torchOn ? LucideIcons.zap : LucideIcons.zapOff,
                    color: _torchOn ? cs.primary : Colors.white,
                    size: 26,
                  ),
                  onPressed: () async {
                    await _controller.toggleTorch();
                    if (mounted) setState(() => _torchOn = !_torchOn);
                  },
                ),
              ],
            ),
          ),

          // Bottom guidance.
          Positioned(
            bottom: 56,
            left: 30,
            right: 30,
            child: Column(
              children: [
                Icon(LucideIcons.scanLine, color: cs.primary, size: 30),
                const SizedBox(height: 14),
                const Text(
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

          if (_phase == _ScanPhase.validating) _buildValidating(),
          if (_phase == _ScanPhase.success) _buildSuccess(cs),
        ],
      ),
    );
  }

  // ── Frame + scan line ──────────────────────────────────────────────────────

  Widget _buildFrame(ColorScheme cs) {
    final bool locked = _phase == _ScanPhase.locked || _phase == _ScanPhase.validating;
    final Color accent = _phase == _ScanPhase.success ? Colors.green.shade400 : cs.primary;
    return Stack(
      children: [
        // Outline.
        AnimatedContainer(
          duration: const Duration(milliseconds: 220),
          decoration: BoxDecoration(
            border: Border.all(color: accent.withValues(alpha: locked ? 0.9 : 0.3), width: 1),
            borderRadius: BorderRadius.circular(4),
          ),
        ),
        // Corners.
        Positioned(top: 0, left: 0, child: _corner(accent, 0)),
        Positioned(top: 0, right: 0, child: _corner(accent, 1.5708)),
        Positioned(bottom: 0, right: 0, child: _corner(accent, 3.14159)),
        Positioned(bottom: 0, left: 0, child: _corner(accent, 4.71239)),
        // Sweeping scan line — only while actively scanning.
        if (_phase == _ScanPhase.scanning)
          AnimatedBuilder(
            animation: _sweep,
            builder: (context, _) {
              final t = Curves.easeInOut.transform(_sweep.value);
              final y = 12 + t * (_frameSize - 24);
              return Positioned(
                left: 14,
                right: 14,
                top: y,
                child: Container(
                  height: 2.5,
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [
                        cs.primary.withValues(alpha: 0),
                        cs.primary,
                        cs.primary.withValues(alpha: 0),
                      ],
                    ),
                    boxShadow: [
                      BoxShadow(color: cs.primary.withValues(alpha: 0.6), blurRadius: 10, spreadRadius: 1),
                    ],
                  ),
                ),
              );
            },
          ),
      ],
    );
  }

  Widget _corner(Color color, double angle) {
    return Transform.rotate(
      angle: angle,
      child: Container(
        width: 38,
        height: 38,
        decoration: BoxDecoration(
          border: Border(
            top: BorderSide(color: color, width: 5),
            left: BorderSide(color: color, width: 5),
          ),
          borderRadius: const BorderRadius.only(topLeft: Radius.circular(2)),
        ),
      ),
    );
  }

  // ── Overlays ───────────────────────────────────────────────────────────────

  Widget _buildValidating() {
    return Container(
      color: Colors.black.withValues(alpha: 0.82),
      child: const LoadingState(message: 'Validation du transfert…'),
    );
  }

  Widget _buildSuccess(ColorScheme cs) {
    return Container(
      color: Colors.black.withValues(alpha: 0.9),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ScaleTransition(
              scale: CurvedAnimation(parent: _successPop, curve: Curves.elasticOut),
              child: Container(
                width: 96,
                height: 96,
                decoration: BoxDecoration(
                  color: Colors.green.shade400.withValues(alpha: 0.15),
                  shape: BoxShape.circle,
                  border: Border.all(color: Colors.green.shade400, width: 2),
                ),
                child: Icon(LucideIcons.check, color: Colors.green.shade400, size: 52),
              ),
            ),
            const SizedBox(height: 24),
            const Text(
              'Transfert confirmé',
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, color: Colors.white, fontFamily: 'Inter'),
            ),
            const SizedBox(height: 6),
            Text(
              'Le colis vous a été transféré.',
              style: TextStyle(color: Colors.white.withValues(alpha: 0.7), fontSize: 13),
            ),
          ],
        ),
      ),
    );
  }

  // ── Flow ─────────────────────────────────────────────────────────────────

  Future<void> _processToken(String raw) async {
    // 1) Lock: visually confirm the code was captured + a short haptic tick.
    HapticFeedback.mediumImpact();
    setState(() => _phase = _ScanPhase.locked);
    await Future.delayed(const Duration(milliseconds: 360));

    final parts = raw.split('|');
    if (parts.length != 2 || parts[0].isEmpty || parts[1].isEmpty) {
      await _failGracefully('Ce QR code n\'est pas un jeton de transfert valide.');
      return;
    }
    final deliveryId = parts[0];
    final token = parts[1];

    // 2) Validate against the server. Capture GPS for the custody audit trail —
    //    best-effort, never blocks the confirm if the fix is slow/denied.
    setState(() => _phase = _ScanPhase.validating);
    try {
      final pos = await LocationService().currentPosition();
      await ref
          .read(deliveryRepositoryProvider)
          .confirmHandoff(deliveryId, token, lat: pos?.lat, lng: pos?.lng);
      await _controller.stop();

      // 3) Deliberate success beat, then return.
      HapticFeedback.heavyImpact();
      if (!mounted) return;
      setState(() => _phase = _ScanPhase.success);
      _successPop.forward(from: 0);
      await Future.delayed(const Duration(milliseconds: 1100));
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      await _failGracefully(_friendlyError(e));
    }
  }

  Future<void> _failGracefully(String message) async {
    HapticFeedback.vibrate();
    if (!mounted) return;
    final cs = Theme.of(context).colorScheme;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: cs.error,
        behavior: SnackBarBehavior.floating,
      ),
    );
    // Resume scanning after a short cooldown so the same bad code doesn't re-fire instantly.
    await Future.delayed(const Duration(milliseconds: 700));
    if (mounted) setState(() => _phase = _ScanPhase.scanning);
  }

  /// Maps backend / transport failures to a driver-friendly message —
  /// never surfaces a raw exception string.
  String _friendlyError(Object e) {
    final s = e.toString().toLowerCase();
    if (s.contains('expired') || s.contains('expir')) {
      return 'Ce jeton a expiré. Demandez à l\'expéditeur d\'en générer un nouveau.';
    }
    if (s.contains('not found') || s.contains('404') || s.contains('invalid')) {
      return 'Jeton invalide ou déjà utilisé. Réessayez avec un nouveau code.';
    }
    if (s.contains('403') || s.contains('forbidden') || s.contains('not authorized')) {
      return 'Ce transfert ne vous est pas destiné.';
    }
    if (s.contains('concurrent') || s.contains('409') || s.contains('conflict')) {
      return 'Ce transfert vient d\'être mis à jour. Actualisez puis réessayez.';
    }
    if (s.contains('socket') || s.contains('timeout') || s.contains('connection') || s.contains('network')) {
      return 'Connexion impossible. Vérifiez votre réseau et réessayez.';
    }
    return 'Échec du transfert. Veuillez réessayer.';
  }
}

/// Dark scrim with a transparent square window over the scan frame.
class _ScrimOverlay extends StatelessWidget {
  const _ScrimOverlay({required this.frameSize});
  final double frameSize;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: ColorFiltered(
        colorFilter: ColorFilter.mode(Colors.black.withValues(alpha: 0.55), BlendMode.srcOut),
        child: Stack(
          children: [
            Container(
              decoration: const BoxDecoration(color: Colors.black, backgroundBlendMode: BlendMode.dstOut),
            ),
            Center(
              child: Container(
                width: frameSize,
                height: frameSize,
                decoration: BoxDecoration(borderRadius: BorderRadius.circular(4), color: Colors.black),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Shown when the camera cannot start (permission denied, no camera, etc.).
class _CameraError extends StatelessWidget {
  const _CameraError({required this.error});
  final MobileScannerException error;

  @override
  Widget build(BuildContext context) {
    final denied = error.errorCode == MobileScannerErrorCode.permissionDenied;
    return Container(
      color: Colors.black,
      padding: const EdgeInsets.all(32),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(LucideIcons.cameraOff, color: Colors.white70, size: 48),
            const SizedBox(height: 16),
            Text(
              denied ? 'Accès à la caméra refusé' : 'Caméra indisponible',
              style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w800),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              denied
                  ? 'Autorisez l\'accès à la caméra dans les réglages pour scanner les transferts.'
                  : 'Impossible de démarrer la caméra. Réessayez plus tard.',
              style: const TextStyle(color: Colors.white70, fontSize: 13),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}
