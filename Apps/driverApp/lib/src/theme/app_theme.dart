import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';

class AppColors {
  // ─── Core palette: ASMONE High-Vis ──────────────────────────────
  static const background      = Color(0xFF000000); // Absolute Black
  static const surface         = Color(0xFF121212); // Deep Charcoal
  static const surfaceElevated = Color(0xFF1E1E1E); // Zinc 900-ish
  static const surfaceDim      = Color(0xFF27272A);

  static const neonYellow      = Color(0xFFDFFF00); // High-Vis Neon
  static const neonYellowDim   = Color(0xFFBFFF00);
  static const neonYellowSubtle = Color(0x1ADFFF00);
  static const neonYellowBorder = Color(0x4DDFFF00);

  // ─── Text ──────────────────────────────────────────────────────────────────
  static const textPrimary     = Color(0xFFFFFFFF);
  static const textSecondary   = Color(0xFFD4D4D8);
  static const muted           = Color(0xFF71717A);

  // ─── Border ────────────────────────────────────────────────────────────────
  static const border          = Color(0xFF27272A);
  static const borderDark      = Color(0xFF3F3F46);

  // ─── Semantic ──────────────────────────────────────────────────────────────
  static const success         = Color(0xFFDFFF00); // In High-Vis, Primary is Success
  static const successSubtle   = Color(0x1ADFFF00);
  static const successBorder   = Color(0x4DDFFF00);
  static const danger          = Color(0xFFFF0033); // Tactical Red
  static const dangerSubtle    = Color(0x1AFF0033);
  static const dangerBorder    = Color(0x4DFF0033);
  static const warning         = Color(0xFFFFCC00);
  static const info            = Color(0xFF00EEFF); // Cyber Cyan
  static const infoSubtle      = Color(0x1A00EEFF);

  // ─── Aliases & Legacy compatibility ──────────────────────────────────
  static const accent         = neonYellow;
  static const accentBright   = neonYellowDim;
  static const primary        = neonYellow;
  static const cyberLime      = neonYellow;      // Legacy mapping
  static const ink            = background;      // Legacy mapping
  static const navy           = surfaceElevated; // Legacy mapping
  static const accentSubtle   = neonYellowSubtle;
}

ThemeData buildAppTheme() {
  final base = ThemeData.dark(useMaterial3: true);
  final tt = GoogleFonts.interTextTheme(base.textTheme).apply(
    bodyColor: AppColors.textPrimary,
    displayColor: AppColors.textPrimary,
  );

  return base.copyWith(
    scaffoldBackgroundColor: AppColors.background,
    colorScheme: ColorScheme.fromSeed(
      brightness: Brightness.dark,
      seedColor: AppColors.neonYellow,
      primary: AppColors.neonYellow,
      secondary: AppColors.surfaceElevated,
      surface: AppColors.surface,
      error: AppColors.danger,
    ),
    textTheme: tt.copyWith(
      displayLarge:  GoogleFonts.spaceGrotesk(fontSize: tt.displayLarge?.fontSize, fontWeight: FontWeight.w800, letterSpacing: -1.5, color: AppColors.textPrimary),
      headlineLarge: GoogleFonts.spaceGrotesk(fontSize: tt.headlineLarge?.fontSize, fontWeight: FontWeight.w800, letterSpacing: -1, color: AppColors.textPrimary),
      headlineMedium:GoogleFonts.spaceGrotesk(fontSize: tt.headlineMedium?.fontSize, fontWeight: FontWeight.w700, letterSpacing: -0.5, color: AppColors.textPrimary),
      headlineSmall: GoogleFonts.spaceGrotesk(fontSize: tt.headlineSmall?.fontSize, fontWeight: FontWeight.w700, color: AppColors.textPrimary),
      titleLarge:    GoogleFonts.spaceGrotesk(fontSize: tt.titleLarge?.fontSize, fontWeight: FontWeight.w700, color: AppColors.textPrimary),
      titleMedium:   GoogleFonts.spaceGrotesk(fontSize: tt.titleMedium?.fontSize, fontWeight: FontWeight.w600, color: AppColors.textPrimary),
      titleSmall:    GoogleFonts.spaceGrotesk(fontSize: tt.titleSmall?.fontSize, fontWeight: FontWeight.w600, color: AppColors.textPrimary),
      bodyLarge:     tt.bodyLarge?.copyWith(color: AppColors.textPrimary, height: 1.5),
      bodyMedium:    tt.bodyMedium?.copyWith(color: AppColors.textSecondary, height: 1.4),
      bodySmall:     tt.bodySmall?.copyWith(color: AppColors.muted, height: 1.3),
      labelLarge:    tt.labelLarge?.copyWith(fontWeight: FontWeight.w700, letterSpacing: 0.5, color: AppColors.textPrimary),
      labelSmall:    tt.labelSmall?.copyWith(letterSpacing: 1, color: AppColors.muted),
    ),
    appBarTheme: AppBarTheme(
      backgroundColor: AppColors.background,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      centerTitle: false,
      systemOverlayStyle: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light,
        statusBarBrightness: Brightness.dark,
      ),
      iconTheme: const IconThemeData(color: AppColors.textPrimary),
      titleTextStyle: GoogleFonts.spaceGrotesk(
        color: AppColors.textPrimary,
        fontSize: 18,
        fontWeight: FontWeight.w800,
        letterSpacing: -0.2,
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: AppColors.surface,
      border: _inputBorder(),
      enabledBorder: _inputBorder(),
      focusedBorder: _inputBorder(color: AppColors.neonYellow),
      errorBorder: _inputBorder(color: AppColors.danger),
      focusedErrorBorder: _inputBorder(color: AppColors.danger),
      labelStyle: const TextStyle(color: AppColors.textSecondary, fontSize: 14),
      hintStyle: const TextStyle(color: AppColors.muted, fontSize: 14),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
    ),
    dividerColor: AppColors.border,
    cardTheme: CardThemeData(
      color: AppColors.surface,
      margin: EdgeInsets.zero,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(4), // Tactical 4px
        side: const BorderSide(color: AppColors.border, width: 1),
      ),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: AppColors.surfaceElevated,
      labelStyle: const TextStyle(color: AppColors.textPrimary, fontSize: 12, fontWeight: FontWeight.bold),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(2)),
      side: const BorderSide(color: AppColors.border),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: AppColors.neonYellow,
        foregroundColor: Colors.black,
        elevation: 0,
        textStyle: GoogleFonts.spaceGrotesk(fontWeight: FontWeight.w900, fontSize: 15, letterSpacing: 0.5),
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)), // Tactical 4px
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: AppColors.background,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(4),
        side: const BorderSide(color: AppColors.border, width: 1.5),
      ),
      titleTextStyle: GoogleFonts.spaceGrotesk(fontSize: 20, fontWeight: FontWeight.w800, color: AppColors.textPrimary),
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(color: AppColors.neonYellow),
  );
}

OutlineInputBorder _inputBorder({Color color = AppColors.border}) => OutlineInputBorder(
  borderSide: BorderSide(color: color, width: 2.0), // Heavier industrial borders
  borderRadius: BorderRadius.circular(4),
);
