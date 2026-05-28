import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app.dart';
import '../../../app_providers.dart';
import '../../../services/locale_provider.dart';
import '../../../services/location_service.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';
import '../../auth/models/auth_models.dart';

import 'change_password_screen.dart';

class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});

  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> {
  bool _locationSending = false;
  bool _availabilityLoading = false;

  Future<void> _setAvailability(String status) async {
    setState(() => _availabilityLoading = true);
    try {
      await ref.read(profileRepositoryProvider).updateAvailability(status);
      ref.invalidate(driverProfileProvider);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Erreur : $e'),
            backgroundColor: AppColors.danger,
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _availabilityLoading = false);
    }
  }

  Future<void> _sendLocation() async {
    setState(() => _locationSending = true);
    try {
      final point = await LocationService().currentPosition();
      if (point != null) {
        await ref.read(profileRepositoryProvider).updateLocation(point.lat, point.lng);
        if (mounted) {
          final locale = ref.read(localeProvider);
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(DriverCopy.get('position_sent', locale)),
              backgroundColor: AppColors.ink,
              behavior: SnackBarBehavior.floating,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          );
        }
      }
    } finally {
      if (mounted) setState(() => _locationSending = false);
    }
  }

  Future<void> _logout() async {
    final locale = ref.read(localeProvider);
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(DriverCopy.get('logout_confirm_title', locale)),
        content: Text(DriverCopy.get('logout_confirm_body', locale), style: const TextStyle(color: AppColors.muted)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(DriverCopy.get('cancel', locale), style: const TextStyle(color: AppColors.muted)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(DriverCopy.get('logout', locale), style: const TextStyle(color: AppColors.danger)),
          ),
        ],
      ),
    );
    if (confirm != true) return;
    await ref.read(authControllerProvider.notifier).logout();
    if (mounted) navigateToHome(context, AuthStatus.unauthenticated);
  }

  @override
  Widget build(BuildContext context) {
    final profileAsync = ref.watch(driverProfileProvider);
    final statsAsync = ref.watch(driverStatsProvider);
    final locale = ref.watch(localeProvider);

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 40),
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 20, bottom: 20),
          child: Text(
            DriverCopy.get('profile_title', locale),
            style: GoogleFonts.spaceGrotesk(fontSize: 22, fontWeight: FontWeight.w900, color: AppColors.textPrimary, letterSpacing: -0.5),
          ),
        ),
        profileAsync.when(
          data: (profile) => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Identity card
              Container(
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: AppColors.border),
                ),
                child: Column(
                  children: [
                    // Top section with avatar
                    Container(
                      padding: const EdgeInsets.all(20),
                      decoration: const BoxDecoration(
                        color: AppColors.surfaceElevated,
                        borderRadius: BorderRadius.vertical(top: Radius.circular(4)), // Tactical 4px
                      ),
                      child: Row(
                        children: [
                          Container(
                            width: 60,
                            height: 60,
                            decoration: BoxDecoration(
                              color: AppColors.neonYellow.withValues(alpha: 0.1),
                              shape: BoxShape.rectangle,
                              borderRadius: BorderRadius.circular(4),
                              border: Border.all(color: AppColors.neonYellow.withValues(alpha: 0.3), width: 1.5),
                            ),
                            child: Center(
                              child: Text(
                                profile.name.isNotEmpty ? profile.name[0].toUpperCase() : 'D',
                                style: GoogleFonts.spaceGrotesk(fontSize: 26, fontWeight: FontWeight.w900, color: AppColors.neonYellow),
                              ),
                            ),
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  profile.name.toUpperCase(),
                                  style: GoogleFonts.spaceGrotesk(fontSize: 18, fontWeight: FontWeight.w900, color: AppColors.textPrimary, letterSpacing: 0.5),
                                ),
                                const SizedBox(height: 2),
                                Text(profile.phone, style: GoogleFonts.spaceGrotesk(fontSize: 13, color: AppColors.muted, fontWeight: FontWeight.w700)),
                                if (profile.city != null) ...[
                                  const SizedBox(height: 2),
                                  Row(
                                    children: [
                                      const Icon(Icons.location_on_outlined, size: 12, color: AppColors.muted),
                                      const SizedBox(width: 4),
                                      Text(profile.city!, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                                    ],
                                  ),
                                ],
                              ],
                            ),
                          ),
                          _StatusChip(status: profile.onlineStatus, locale: locale),
                        ],
                      ),
                    ),
                    // Details
                    Padding(
                      padding: const EdgeInsets.all(20),
                      child: Column(
                        children: [
                          InfoRow(label: DriverCopy.get('driver_id', locale), value: profile.id),
                          const Divider(color: AppColors.border, height: 20),
                          InfoRow(label: DriverCopy.get('phone', locale), value: profile.phone),
                          if (profile.lastLocationAt != null) ...[
                            const Divider(color: AppColors.border, height: 20),
                            InfoRow(
                              label: DriverCopy.get('last_ping', locale),
                              value: DateFormat('MMM d · HH:mm').format(profile.lastLocationAt!.toLocal()),
                            ),
                          ],
                          if (profile.currentLat != null && profile.currentLng != null) ...[
                            const Divider(color: AppColors.border, height: 20),
                            InfoRow(
                              label: DriverCopy.get('gps', locale),
                              value: '${profile.currentLat!.toStringAsFixed(4)}, ${profile.currentLng!.toStringAsFixed(4)}',
                              valueColor: AppColors.info,
                            ),
                          ],
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              // Shift controls
              _ShiftControls(
                status: profile.onlineStatus,
                loading: _availabilityLoading,
                onSetStatus: _setAvailability,
                locale: locale,
              ),
              const SizedBox(height: 16),
              // Language settings card (harmonized SaaS picker)
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: AppColors.border),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      DriverCopy.get('language_setting', locale).toUpperCase(),
                      style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.muted, letterSpacing: 1.5),
                    ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        Expanded(
                          child: _LanguageButton(
                            label: 'FR',
                            active: locale == 'fr',
                            onPressed: () => ref.read(localeProvider.notifier).setLocale('fr'),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: _LanguageButton(
                            label: 'EN',
                            active: locale == 'en',
                            onPressed: () => ref.read(localeProvider.notifier).setLocale('en'),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: _LanguageButton(
                            label: 'AR',
                            active: locale == 'ar',
                            onPressed: () => ref.read(localeProvider.notifier).setLocale('ar'),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              // Stats
              statsAsync.when(
                data: (stats) => Row(
                  children: [
                    Expanded(child: MetricTile(label: DriverCopy.get('metric_delivered', locale), value: '${stats.delivered}', accentColor: AppColors.success, icon: Icons.check_circle_outline_rounded)),
                    const SizedBox(width: 10),
                    Expanded(child: MetricTile(label: DriverCopy.get('metric_failed', locale), value: '${stats.failed}', accentColor: AppColors.danger, icon: PhosphorIconsBold.xCircle)),
                    const SizedBox(width: 10),
                    Expanded(child: MetricTile(label: DriverCopy.get('metric_total', locale), value: '${stats.totalDeliveries}', accentColor: AppColors.accent, icon: PhosphorIconsBold.package)),
                  ],
                ),
                loading: () => const SizedBox(height: 80, child: Center(child: CircularProgressIndicator(color: AppColors.accent))),
                error: (_, __) => const SizedBox.shrink(),
              ),
              const SizedBox(height: 20),
              const SizedBox(height: 32),
              Text(DriverCopy.get('section_actions', locale), style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.muted, letterSpacing: 1.5)),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: DriveButton(
                  label: DriverCopy.get('send_location', locale),
                  icon: LucideIcons.mapPin,
                  isLoading: _locationSending,
                  onPressed: _locationSending ? null : _sendLocation,
                ),
              ),
              const SizedBox(height: 32),
              Text(DriverCopy.get('section_security', locale), style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.muted, letterSpacing: 1.5)),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: DriveButton(
                  label: DriverCopy.get('change_password', locale),
                  icon: LucideIcons.lock,
                  variant: DriveButtonVariant.ghost,
                  onPressed: () => Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const ChangePasswordScreen()),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: DriveButton(
                  label: DriverCopy.get('logout', locale),
                  icon: LucideIcons.logOut,
                  variant: DriveButtonVariant.danger,
                  onPressed: _logout,
                ),
              ),
            ],
          ),
          loading: () => LoadingState(message: locale == 'ar' ? 'جاري تحميل الملف الشخصي…' : (locale == 'en' ? 'Loading profile…' : 'Chargement du profil…')),
          error: (_, __) => EmptyState(
            icon: PhosphorIconsRegular.userCircleMinus,
            title: locale == 'ar' ? 'الملف الشخصي غير متاح' : (locale == 'en' ? 'Profile unavailable' : 'Profil indisponible'),
            action: () => ref.invalidate(driverProfileProvider),
            actionLabel: locale == 'ar' ? 'إعادة المحاولة' : (locale == 'en' ? 'Retry' : 'Réessayer'),
          ),
        ),
      ],
    );
  }
}

// ─── Status chip shown in the identity card header ────────────────────────────

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.status, required this.locale});
  final String status;
  final String locale;

  @override
  Widget build(BuildContext context) {
    final (labelKey, bg, fg) = switch (status) {
      'ONLINE'   => ('status_online_upper', const Color(0xFFDCFCE7), const Color(0xFF16A34A)),
      'ON_BREAK' => ('status_on_break_upper', const Color(0xFFFEF9C3), const Color(0xFFD97706)),
      _          => ('status_offline_upper', const Color(0xFFF3F4F6), const Color(0xFF6B7280)),
    };
    final label = DriverCopy.get(labelKey, locale);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(2),
        border: Border.all(color: fg.withValues(alpha: 0.35)),
      ),
      child: Text(label, style: TextStyle(fontSize: 9, fontWeight: FontWeight.w900, color: fg, letterSpacing: 0.8)),
    );
  }
}

// ─── Shift control block ──────────────────────────────────────────────────────

class _ShiftControls extends StatelessWidget {
  const _ShiftControls({
    required this.status,
    required this.loading,
    required this.onSetStatus,
    required this.locale,
  });
  final String status;
  final bool loading;
  final Future<void> Function(String) onSetStatus;
  final String locale;

  @override
  Widget build(BuildContext context) {
    if (loading) {
      return Container(
        height: 52,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.border),
        ),
        child: const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.accent)),
      );
    }

    return switch (status) {
      'ONLINE'   => _onlineControls(context),
      'ON_BREAK' => _onBreakControls(context),
      _          => _offlineControls(context),
    };
  }

  Widget _offlineControls(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      child: DriveButton(
        label: DriverCopy.get('action_start_shift', locale),
        icon: LucideIcons.play,
        onPressed: () => onSetStatus('ONLINE'),
      ),
    );
  }

  Widget _onlineControls(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        OutlinedButton.icon(
          icon: const Icon(LucideIcons.coffee, size: 16, color: Color(0xFFD97706)),
          label: Text(DriverCopy.get('action_take_break', locale), style: const TextStyle(color: Color(0xFFD97706), fontWeight: FontWeight.w700)),
          style: OutlinedButton.styleFrom(
            side: const BorderSide(color: Color(0xFFD97706)),
            padding: const EdgeInsets.symmetric(vertical: 12),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
          ),
          onPressed: () => onSetStatus('ON_BREAK'),
        ),
        const SizedBox(height: 8),
        TextButton(
          onPressed: () => onSetStatus('OFFLINE'),
          child: Text(DriverCopy.get('action_end_day', locale), style: const TextStyle(color: AppColors.muted, fontSize: 13, fontWeight: FontWeight.w600)),
        ),
      ],
    );
  }

  Widget _onBreakControls(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        DriveButton(
          label: DriverCopy.get('action_resume_service', locale),
          icon: LucideIcons.play,
          onPressed: () => onSetStatus('ONLINE'),
        ),
        const SizedBox(height: 8),
        TextButton(
          onPressed: () => onSetStatus('OFFLINE'),
          child: Text(DriverCopy.get('action_end_day', locale), style: const TextStyle(color: AppColors.muted, fontSize: 13, fontWeight: FontWeight.w600)),
        ),
      ],
    );
  }
}

// ─── Language picker button widget ────────────────────────────────────────────

class _LanguageButton extends StatelessWidget {
  const _LanguageButton({
    required this.label,
    required this.active,
    required this.onPressed,
  });

  final String label;
  final bool active;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 38,
      child: OutlinedButton(
        style: OutlinedButton.styleFrom(
          backgroundColor: active ? AppColors.accent : Colors.transparent,
          side: BorderSide(
            color: active ? AppColors.accent : AppColors.border,
            width: active ? 1.5 : 1,
          ),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          padding: EdgeInsets.zero,
        ),
        onPressed: onPressed,
        child: Text(
          label,
          style: GoogleFonts.spaceGrotesk(
            fontSize: 12,
            fontWeight: FontWeight.w900,
            color: active ? Colors.black : AppColors.textPrimary,
          ),
        ),
      ),
    );
  }
}
