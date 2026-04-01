import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';

import '../../../app.dart';
import '../../../app_providers.dart';
import '../../../theme/app_theme.dart';

class SplashScreen extends ConsumerWidget {
  const SplashScreen({super.key});
  static const routeName = '/';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.listen(authControllerProvider, (prev, next) {
      if (next.status != prev?.status && !next.isLoading) {
        navigateToHome(context, next.status);
      }
    });

    return Scaffold(
      backgroundColor: AppColors.navy,
      body: Stack(
        children: [
          // Subtle pattern — concentric circles
          Positioned(
            right: -120,
            bottom: -120,
            child: Container(
              width: 400,
              height: 400,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(color: Colors.white.withValues(alpha: 0.04), width: 60),
              ),
            ),
          ),
          Positioned(
            right: -40,
            bottom: -40,
            child: Container(
              width: 220,
              height: 220,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(color: Colors.white.withValues(alpha: 0.06), width: 40),
              ),
            ),
          ),
          // Content
          Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // Logo
                Container(
                  width: 76,
                  height: 76,
                  decoration: BoxDecoration(
                    color: AppColors.accent,
                    borderRadius: BorderRadius.circular(22),
                  ),
                  child: const Icon(Icons.local_shipping_rounded, color: Colors.white, size: 36),
                ),
                const SizedBox(height: 24),
                Text(
                  'asmDrive',
                  style: GoogleFonts.inter(
                    fontSize: 30,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                    letterSpacing: -1,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  'Driver Operations Platform',
                  style: GoogleFonts.inter(
                    fontSize: 13,
                    color: Colors.white.withValues(alpha: 0.5),
                    fontWeight: FontWeight.w400,
                    letterSpacing: 0.2,
                  ),
                ),
                const SizedBox(height: 52),
                SizedBox(
                  width: 22,
                  height: 22,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Colors.white.withValues(alpha: 0.5),
                  ),
                ),
              ],
            ),
          ),
          Positioned(
            bottom: 36,
            left: 0,
            right: 0,
            child: Text(
              'ASM LOGISTICS',
              textAlign: TextAlign.center,
              style: GoogleFonts.inter(
                fontSize: 10,
                letterSpacing: 3,
                color: Colors.white.withValues(alpha: 0.2),
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
