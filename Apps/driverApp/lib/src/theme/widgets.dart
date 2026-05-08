import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

import 'app_theme.dart';

// ─── Button ───────────────────────────────────────────────────────────────────
enum DriveButtonVariant { primary, secondary, ghost, danger, success }
enum DriveButtonSize { sm, md, lg }

class DriveButton extends StatelessWidget {
  const DriveButton({
    super.key,
    required this.label,
    this.onPressed,
    this.icon,
    this.variant = DriveButtonVariant.primary,
    this.size = DriveButtonSize.md,
    this.isLoading = false,
    this.fullWidth = false,
  });

  final String label;
  final VoidCallback? onPressed;
  final IconData? icon;
  final DriveButtonVariant variant;
  final DriveButtonSize size;
  final bool isLoading;
  final bool fullWidth;

  @override
  Widget build(BuildContext context) {
    final disabled = onPressed == null || isLoading;

    Color bg, fg, borderColor;
    switch (variant) {
      case DriveButtonVariant.primary:
        bg = disabled ? AppColors.accent.withValues(alpha: 0.45) : AppColors.accent;
        fg = Colors.black;
        borderColor = Colors.transparent;
        break;
      case DriveButtonVariant.secondary:
        bg = AppColors.surfaceElevated;
        fg = Colors.white;
        borderColor = AppColors.borderDark;
        break;
      case DriveButtonVariant.ghost:
        bg = Colors.transparent;
        fg = disabled ? AppColors.muted : AppColors.textPrimary;
        borderColor = AppColors.border;
        break;
      case DriveButtonVariant.danger:
        bg = disabled ? AppColors.danger.withValues(alpha: 0.45) : AppColors.danger;
        fg = Colors.white;
        borderColor = Colors.transparent;
        break;
      case DriveButtonVariant.success:
        bg = disabled ? AppColors.success.withValues(alpha: 0.45) : AppColors.success;
        fg = Colors.black;
        borderColor = Colors.transparent;
        break;
    }

    final double vp, hp, fs, radius;
    switch (size) {
      case DriveButtonSize.sm:
        vp = 9; hp = 14; fs = 13; radius = 4; // Tactical 4px
        break;
      case DriveButtonSize.md:
        vp = 13; hp = 20; fs = 14.5; radius = 4;
        break;
      case DriveButtonSize.lg:
        vp = 16; hp = 24; fs = 16; radius = 4;
        break;
    }

    final child = isLoading
        ? SizedBox(
            width: 18,
            height: 18,
            child: CircularProgressIndicator(strokeWidth: 2, color: fg),
          )
        : Row(
            mainAxisSize: MainAxisSize.min,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              if (icon != null) ...[
                Icon(icon, size: fs + 1, color: fg),
                const SizedBox(width: 7),
              ],
              Text(
                label.toUpperCase(),
                style: GoogleFonts.spaceGrotesk(
                  fontSize: fs,
                  fontWeight: FontWeight.w900,
                  color: fg,
                  letterSpacing: 0.5,
                ),
              ),
            ],
          );

    return SizedBox(
      width: fullWidth ? double.infinity : null,
      child: InkWell(
        onTap: disabled ? null : onPressed,
        borderRadius: BorderRadius.circular(radius),
        child: Ink(
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(radius),
            border: borderColor != Colors.transparent
                ? Border.all(color: borderColor, width: 1.5)
                : null,
          ),
          child: Container(
            padding: EdgeInsets.symmetric(horizontal: hp, vertical: vp),
            alignment: Alignment.center,
            child: child,
          ),
        ),
      ),
    );
  }
}

// Keep old name as alias for backwards compat
typedef AsmDriveButton = DriveButton;
typedef AsmDriveButtonVariant = DriveButtonVariant;
typedef AsmDriveButtonSize = DriveButtonSize;

// ─── Card ─────────────────────────────────────────────────────────────────────
class DriveCard extends StatelessWidget {
  const DriveCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(20),
    this.color = AppColors.surface,
    this.onTap,
    this.shadow = true,
  });

  final Widget child;
  final EdgeInsets padding;
  final Color color;
  final VoidCallback? onTap;
  final bool shadow;

  @override
  Widget build(BuildContext context) {
    final decoration = BoxDecoration(
      color: color,
      borderRadius: BorderRadius.circular(4), // Tactical 4px
      border: Border.all(color: AppColors.border, width: 1),
      boxShadow: shadow
          ? [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.2),
                blurRadius: 12,
                offset: const Offset(0, 4),
              ),
            ]
          : null,
    );

    final content = Container(
      decoration: decoration,
      padding: padding,
      child: child,
    );

    if (onTap == null) return content;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(4),
      child: content,
    );
  }
}

typedef AsmDriveCard = DriveCard;

// ─── Status Badge ─────────────────────────────────────────────────────────────
class StatusBadge extends StatelessWidget {
  const StatusBadge({
    super.key,
    required this.label,
    this.color = AppColors.accent,
    this.dot = true,
  });

  final String label;
  final Color color;
  final bool dot;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(2), // Sharp status
        border: Border.all(color: color.withValues(alpha: 0.25), width: 1),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (dot) ...[
            Container(
              width: 5,
              height: 5,
              decoration: BoxDecoration(color: color, shape: BoxShape.circle),
            ),
            const SizedBox(width: 5),
          ],
          Text(
            label.toUpperCase(),
            style: GoogleFonts.spaceGrotesk(
              fontSize: 10,
              fontWeight: FontWeight.w900,
              color: color,
              letterSpacing: 1,
            ),
          ),
        ],
      ),
    );
  }
}

// ─── Metric Tile ──────────────────────────────────────────────────────────────
class MetricTile extends StatelessWidget {
  const MetricTile({
    super.key,
    required this.label,
    required this.value,
    this.accentColor = AppColors.accent,
    this.icon,
  });

  final String label;
  final String value;
  final Color accentColor;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    return DriveCard(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (icon != null) ...[
            Container(
              width: 32,
              height: 32,
              decoration: BoxDecoration(
                color: accentColor.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Icon(icon, size: 16, color: accentColor),
            ),
            const SizedBox(height: 10),
          ],
          Text(
            value,
            style: GoogleFonts.spaceGrotesk(
              fontSize: 28,
              fontWeight: FontWeight.w900,
              color: AppColors.textPrimary,
              letterSpacing: -0.5,
            ),
          ),
          const SizedBox(height: 2),
          Text(label.toUpperCase(), style: GoogleFonts.spaceGrotesk(fontSize: 10, color: AppColors.muted, fontWeight: FontWeight.w900, letterSpacing: 1)),
        ],
      ),
    );
  }
}

// ─── Section Header ───────────────────────────────────────────────────────────
class SectionHeader extends StatelessWidget {
  const SectionHeader({super.key, required this.title, this.subtitle, this.trailing});

  final String title;
  final String? subtitle;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              if (subtitle != null)
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text(subtitle!, style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
                ),
            ],
          ),
        ),
        if (trailing != null) trailing!,
      ],
    );
  }
}

// ─── Divider ─────────────────────────────────────────────────────────────────
class DarkDivider extends StatelessWidget {
  const DarkDivider({super.key, this.indent = 0});
  final double indent;

  @override
  Widget build(BuildContext context) =>
      Divider(color: AppColors.border, thickness: 1, indent: indent, height: 1);
}

// ─── Info Row ─────────────────────────────────────────────────────────────────
class InfoRow extends StatelessWidget {
  const InfoRow({super.key, required this.label, required this.value, this.valueColor});

  final String label;
  final String value;
  final Color? valueColor;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 7),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 110,
            child: Text(label, style: const TextStyle(fontSize: 12, color: AppColors.muted, fontWeight: FontWeight.w500)),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              value,
              style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: valueColor ?? AppColors.textPrimary),
            ),
          ),
        ],
      ),
    );
  }
}

// ─── Loading State ────────────────────────────────────────────────────────────
class LoadingState extends StatelessWidget {
  const LoadingState({super.key, this.message});
  final String? message;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const SizedBox(
            width: 28,
            height: 28,
            child: CircularProgressIndicator(strokeWidth: 2.5, color: AppColors.accent),
          ),
          if (message != null) ...[
            const SizedBox(height: 14),
            Text(message!, style: const TextStyle(fontSize: 13, color: AppColors.muted)),
          ],
        ],
      ),
    );
  }
}

// ─── Empty State ──────────────────────────────────────────────────────────────
class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.icon,
    required this.title,
    this.subtitle,
    this.action,
    this.actionLabel,
  });

  final IconData icon;
  final String title;
  final String? subtitle;
  final VoidCallback? action;
  final String? actionLabel;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(40),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                color: AppColors.surfaceElevated,
                borderRadius: BorderRadius.circular(4), // Tactical 4px
                border: Border.all(color: AppColors.border),
              ),
              child: Icon(icon, size: 28, color: AppColors.muted),
            ),
            const SizedBox(height: 18),
            Text(title,
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.textPrimary),
                textAlign: TextAlign.center),
            if (subtitle != null) ...[
              const SizedBox(height: 6),
              Text(subtitle!,
                  style: const TextStyle(fontSize: 13, color: AppColors.muted, height: 1.5),
                  textAlign: TextAlign.center),
            ],
            if (action != null && actionLabel != null) ...[
              const SizedBox(height: 20),
              DriveButton(label: actionLabel!, onPressed: action, size: DriveButtonSize.sm),
            ],
          ],
        ),
      ),
    );
  }
}
