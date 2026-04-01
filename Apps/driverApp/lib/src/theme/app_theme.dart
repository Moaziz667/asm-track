import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';

class AppColors {
  // ─── Core palette: black / white / royal red ──────────────────────────────
  static const background      = Color(0xFFF6F6F8);
  static const surface         = Color(0xFFFFFFFF);
  static const surfaceElevated = Color(0xFFF0F0F3);
  static const surfaceDim      = Color(0xFFE3E3E8);

  static const ink             = Color(0xFF101014);
  static const inkSoft         = Color(0xFF1A1A22);
  static const inkLight        = Color(0xFF2A2A35);

  static const royalRed        = Color(0xFFB11226);
  static const royalRedBright  = Color(0xFFD61F3A);
  static const royalRedSubtle  = Color(0xFFFEEDF0);
  static const royalRedBorder  = Color(0xFFF5B9C2);

  // ─── Text ──────────────────────────────────────────────────────────────────
  static const textPrimary     = ink;
  static const textSecondary   = Color(0xFF3D3D48);
  static const muted           = Color(0xFF767684);

  // ─── Border ────────────────────────────────────────────────────────────────
  static const border          = Color(0xFFD9D9E1);
  static const borderDark      = Color(0xFFC7C7D2);

  // ─── Semantic ──────────────────────────────────────────────────────────────
  static const success         = Color(0xFF0A7A46);
  static const successSubtle   = Color(0xFFEAF8F1);
  static const successBorder   = Color(0xFF9BDCB9);
  static const danger          = royalRed;
  static const dangerSubtle    = royalRedSubtle;
  static const dangerBorder    = royalRedBorder;
  static const warning         = Color(0xFFA76A00);
  static const warningSubtle   = Color(0xFFFFF7E8);
  static const warningBorder   = Color(0xFFF3DAA9);
  static const info            = Color(0xFF174A9C);
  static const infoSubtle      = Color(0xFFECF3FF);
  static const infoBorder      = Color(0xFFC4D9FF);

  // ─── Legacy aliases ────────────────────────────────────────────────────────
  static const navy           = ink;
  static const navyMid        = inkSoft;
  static const navyLight      = inkLight;
  static const accent         = royalRed;
  static const accentBright   = royalRedBright;
  static const accentSubtle   = royalRedSubtle;
  static const accentBorder   = royalRedBorder;
  static const primary        = royalRed;
  static const primaryAlt     = success;
  static const subtleBorder   = border;
  static const amber          = warning;
}

ThemeData buildAppTheme() {
  final base = ThemeData.light(useMaterial3: true);
  final tt = GoogleFonts.manropeTextTheme(base.textTheme).apply(
    bodyColor: AppColors.textPrimary,
    displayColor: AppColors.textPrimary,
  );

  return base.copyWith(
    scaffoldBackgroundColor: AppColors.background,
    colorScheme: ColorScheme.fromSeed(
      brightness: Brightness.light,
      seedColor: AppColors.royalRed,
      primary: AppColors.royalRed,
      secondary: AppColors.ink,
      surface: AppColors.surface,
      error: AppColors.danger,
    ),
    textTheme: tt.copyWith(
      displayLarge:  GoogleFonts.sora(fontSize: tt.displayLarge?.fontSize, fontWeight: FontWeight.w800, letterSpacing: -1.3, color: AppColors.textPrimary),
      headlineLarge: GoogleFonts.sora(fontSize: tt.headlineLarge?.fontSize, fontWeight: FontWeight.w800, letterSpacing: -1, color: AppColors.textPrimary),
      headlineMedium:GoogleFonts.sora(fontSize: tt.headlineMedium?.fontSize, fontWeight: FontWeight.w700, letterSpacing: -0.45, color: AppColors.textPrimary),
      headlineSmall: GoogleFonts.sora(fontSize: tt.headlineSmall?.fontSize, fontWeight: FontWeight.w700, letterSpacing: -0.2, color: AppColors.textPrimary),
      titleLarge:    GoogleFonts.sora(fontSize: tt.titleLarge?.fontSize, fontWeight: FontWeight.w700, letterSpacing: -0.15, color: AppColors.textPrimary),
      titleMedium:   GoogleFonts.sora(fontSize: tt.titleMedium?.fontSize, fontWeight: FontWeight.w600, color: AppColors.textPrimary),
      titleSmall:    GoogleFonts.sora(fontSize: tt.titleSmall?.fontSize, fontWeight: FontWeight.w600, color: AppColors.textPrimary),
      bodyLarge:     tt.bodyLarge?.copyWith(color: AppColors.textPrimary, height: 1.6),
      bodyMedium:    tt.bodyMedium?.copyWith(color: AppColors.textSecondary, height: 1.5),
      bodySmall:     tt.bodySmall?.copyWith(color: AppColors.muted, height: 1.4),
      labelLarge:    tt.labelLarge?.copyWith(fontWeight: FontWeight.w600, letterSpacing: 0.1, color: AppColors.textPrimary),
      labelSmall:    tt.labelSmall?.copyWith(letterSpacing: 1, color: AppColors.muted),
    ),
    appBarTheme: AppBarTheme(
      backgroundColor: AppColors.ink,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      centerTitle: false,
      systemOverlayStyle: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light,
        statusBarBrightness: Brightness.dark,
      ),
      iconTheme: const IconThemeData(color: Colors.white),
      titleTextStyle: GoogleFonts.sora(
        color: Colors.white,
        fontSize: 17,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.2,
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: AppColors.surface,
      border: _inputBorder(),
      enabledBorder: _inputBorder(),
      focusedBorder: _inputBorder(color: AppColors.royalRed),
      errorBorder: _inputBorder(color: AppColors.danger),
      focusedErrorBorder: _inputBorder(color: AppColors.danger),
      labelStyle: const TextStyle(color: AppColors.textSecondary, fontSize: 14),
      hintStyle: const TextStyle(color: AppColors.muted, fontSize: 14),
      prefixIconColor: AppColors.textSecondary,
      suffixIconColor: AppColors.textSecondary,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
    ),
    dividerColor: AppColors.border,
    cardTheme: CardThemeData(
      color: AppColors.surface,
      margin: EdgeInsets.zero,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: const BorderSide(color: AppColors.border, width: 1.2),
      ),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: AppColors.surfaceElevated,
      labelStyle: const TextStyle(color: AppColors.textPrimary, fontSize: 12, fontWeight: FontWeight.w500),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      side: const BorderSide(color: AppColors.border),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
    ),
    iconTheme: const IconThemeData(color: AppColors.textPrimary, size: 20),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: AppColors.royalRed,
        foregroundColor: Colors.white,
        elevation: 0,
        textStyle: GoogleFonts.manrope(fontWeight: FontWeight.w700, fontSize: 15, letterSpacing: 0),
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 15),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: AppColors.surface,
      elevation: 24,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      titleTextStyle: GoogleFonts.sora(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.textPrimary),
      contentTextStyle: GoogleFonts.manrope(fontSize: 14, color: AppColors.textSecondary),
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      backgroundColor: AppColors.surface,
      modalBackgroundColor: AppColors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: AppColors.ink,
      contentTextStyle: GoogleFonts.manrope(color: Colors.white, fontSize: 13),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      behavior: SnackBarBehavior.floating,
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(color: AppColors.accent),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? AppColors.accent : AppColors.muted),
      trackColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? AppColors.accentBorder : AppColors.surfaceDim),
    ),
  );
}

OutlineInputBorder _inputBorder({Color color = AppColors.border}) => OutlineInputBorder(
  borderSide: BorderSide(color: color, width: 1.5),
  borderRadius: BorderRadius.circular(12),
);
