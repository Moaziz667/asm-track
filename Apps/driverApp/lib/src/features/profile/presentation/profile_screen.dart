import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';
import '../../../services/locale_provider.dart';
import '../../../services/location_service.dart';
import '../../../theme/widgets.dart';

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
            backgroundColor: Theme.of(context).colorScheme.error,
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
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final locale = ref.read(localeProvider);
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(DriverCopy.get('logout_confirm_title', locale)),
        content: Text(DriverCopy.get('logout_confirm_body', locale), style: TextStyle(color: colorScheme.onSurfaceVariant)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(DriverCopy.get('cancel', locale)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(DriverCopy.get('logout', locale), style: TextStyle(color: colorScheme.error)),
          ),
        ],
      ),
    );
    if (confirm != true) return;
    // Navigation to login is handled centrally by DriverApp's auth listener.
    await ref.read(authControllerProvider.notifier).logout();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
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
            style: theme.textTheme.headlineSmall,
          ),
        ),
        profileAsync.when(
          data: (profile) => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Card(
                child: Column(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(20),
                      color: colorScheme.surfaceContainerHighest,
                      child: Row(
                        children: [
                          CircleAvatar(
                            radius: 30,
                            backgroundColor: colorScheme.primaryContainer,
                            child: Text(
                              profile.name.isNotEmpty ? profile.name[0].toUpperCase() : 'D',
                              style: theme.textTheme.headlineSmall?.copyWith(color: colorScheme.primary),
                            ),
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  profile.name,
                                  style: theme.textTheme.titleMedium,
                                ),
                                const SizedBox(height: 2),
                                Text(profile.phone, style: theme.textTheme.bodySmall),
                                if (profile.city != null) ...[
                                  const SizedBox(height: 2),
                                  Row(
                                    children: [
                                      Icon(Icons.location_on_outlined, size: 12, color: colorScheme.onSurfaceVariant),
                                      const SizedBox(width: 4),
                                      Text(profile.city!, style: theme.textTheme.bodySmall?.copyWith(color: colorScheme.onSurfaceVariant)),
                                    ],
                                  ),
                                ],
                              ],
                            ),
                          ),
                          Chip(
                            label: Text(
                              switch (profile.onlineStatus) {
                                'ONLINE' => DriverCopy.get('status_online_upper', locale),
                                'ON_BREAK' => DriverCopy.get('status_on_break_upper', locale),
                                _ => DriverCopy.get('status_offline_upper', locale),
                              },
                              style: TextStyle(fontSize: 9, fontWeight: FontWeight.w700, color: switch (profile.onlineStatus) {
                                'ONLINE' => const Color(0xFF16A34A),
                                'ON_BREAK' => const Color(0xFFD97706),
                                _ => const Color(0xFF6B7280),
                              }),
                            ),
                            backgroundColor: switch (profile.onlineStatus) {
                              'ONLINE' => const Color(0xFFDCFCE7),
                              'ON_BREAK' => const Color(0xFFFEF9C3),
                              _ => const Color(0xFFF3F4F6),
                            },
                            side: BorderSide.none,
                            visualDensity: VisualDensity.compact,
                            materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                          ),
                        ],
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.all(20),
                      child: Column(
                        children: [
                          _infoRow(DriverCopy.get('driver_id', locale), profile.id, theme),
                          const Divider(height: 20),
                          _infoRow(DriverCopy.get('phone', locale), profile.phone, theme),
                          if (profile.lastLocationAt != null) ...[
                            const Divider(height: 20),
                            _infoRow(
                              DriverCopy.get('last_ping', locale),
                              DateFormat('MMM d \u00b7 HH:mm').format(profile.lastLocationAt!.toLocal()),
                              theme,
                            ),
                          ],
                          if (profile.currentLat != null && profile.currentLng != null) ...[
                            const Divider(height: 20),
                            _infoRow(
                              DriverCopy.get('gps', locale),
                              '${profile.currentLat!.toStringAsFixed(4)}, ${profile.currentLng!.toStringAsFixed(4)}',
                              theme,
                              valueColor: colorScheme.tertiary,
                            ),
                          ],
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              _ShiftControls(
                status: profile.onlineStatus,
                loading: _availabilityLoading,
                onSetStatus: _setAvailability,
                locale: locale,
              ),
              const SizedBox(height: 16),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        DriverCopy.get('language_setting', locale),
                        style: theme.textTheme.labelMedium,
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
              ),
              const SizedBox(height: 16),
              statsAsync.when(
                data: (stats) => Row(
                  children: [
                    Expanded(child: _MetricTile(label: DriverCopy.get('metric_delivered', locale), value: '${stats.delivered}', accentColor: colorScheme.tertiary, icon: Icons.check_circle_outline_rounded)),
                    const SizedBox(width: 10),
                    Expanded(child: _MetricTile(label: DriverCopy.get('metric_failed', locale), value: '${stats.failed}', accentColor: colorScheme.error, icon: PhosphorIconsBold.xCircle)),
                    const SizedBox(width: 10),
                    Expanded(child: _MetricTile(label: DriverCopy.get('metric_total', locale), value: '${stats.totalDeliveries}', accentColor: colorScheme.primary, icon: PhosphorIconsBold.package)),
                  ],
                ),
                loading: () => const SizedBox(height: 80, child: Center(child: CircularProgressIndicator())),
                error: (_, __) => const SizedBox.shrink(),
              ),
              const SizedBox(height: 20),
              const SizedBox(height: 32),
              Text(DriverCopy.get('section_actions', locale), style: theme.textTheme.labelMedium),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: _locationSending ? null : _sendLocation,
                  icon: _locationSending
                      ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(LucideIcons.mapPin),
                  label: Text(DriverCopy.get('send_location', locale)),
                ),
              ),
              const SizedBox(height: 32),
              Text(DriverCopy.get('section_security', locale), style: theme.textTheme.labelMedium),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: () => Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const ChangePasswordScreen()),
                  ),
                  icon: const Icon(LucideIcons.lock),
                  label: Text(DriverCopy.get('change_password', locale)),
                ),
              ),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: TextButton.icon(
                  onPressed: _logout,
                  icon: const Icon(LucideIcons.logOut),
                  label: Text(DriverCopy.get('logout', locale)),
                  style: TextButton.styleFrom(foregroundColor: colorScheme.error),
                ),
              ),
            ],
          ),
          loading: () => LoadingState(message: locale == 'ar' ? 'جاري تحميل الملف الشخصي\u2026' : (locale == 'en' ? 'Loading profile\u2026' : 'Chargement du profil\u2026')),
          error: (_, __) => EmptyState(
            icon: PhosphorIconsRegular.userCircleMinus,
            title: locale == 'ar' ? 'الملف الشخصي غير متاح' : (locale == 'en' ? 'Profile unavailable' : 'Profil indisponible'),
            action: () => ref.invalidate(driverProfileProvider),
            actionLabel: locale == 'ar' ? 'إعادة المحاولة' : (locale == 'en' ? 'Retry' : 'Reessayer'),
          ),
        ),
      ],
    );
  }

  Widget _infoRow(String label, String value, ThemeData theme, {Color? valueColor}) {
    final colorScheme = theme.colorScheme;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 110,
          child: Text(label, style: theme.textTheme.bodySmall?.copyWith(color: colorScheme.onSurfaceVariant)),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(value, style: TextStyle(fontWeight: FontWeight.w500, color: valueColor ?? colorScheme.onSurface)),
        ),
      ],
    );
  }
}

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
        child: const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
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
      child: FilledButton.icon(
        icon: const Icon(LucideIcons.play),
        label: Text(DriverCopy.get('action_start_shift', locale)),
        onPressed: () => onSetStatus('ONLINE'),
      ),
    );
  }

  Widget _onlineControls(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        OutlinedButton.icon(
          icon: const Icon(LucideIcons.coffee, size: 16),
          label: Text(DriverCopy.get('action_take_break', locale)),
          style: OutlinedButton.styleFrom(
            foregroundColor: const Color(0xFFD97706),
            side: const BorderSide(color: Color(0xFFD97706)),
            padding: const EdgeInsets.symmetric(vertical: 12),
          ),
          onPressed: () => onSetStatus('ON_BREAK'),
        ),
        const SizedBox(height: 8),
        TextButton(
          onPressed: () => onSetStatus('OFFLINE'),
          child: Text(DriverCopy.get('action_end_day', locale), style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
        ),
      ],
    );
  }

  Widget _onBreakControls(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        FilledButton.icon(
          icon: const Icon(LucideIcons.play),
          label: Text(DriverCopy.get('action_resume_service', locale)),
          onPressed: () => onSetStatus('ONLINE'),
        ),
        const SizedBox(height: 8),
        TextButton(
          onPressed: () => onSetStatus('OFFLINE'),
          child: Text(DriverCopy.get('action_end_day', locale), style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
        ),
      ],
    );
  }
}

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
    final theme = Theme.of(context);
    return FilterChip(
      label: Text(label, style: TextStyle(
        fontWeight: FontWeight.w700,
        color: active ? theme.colorScheme.onPrimary : theme.colorScheme.onSurface,
      )),
      selected: active,
      onSelected: (_) => onPressed(),
      showCheckmark: false,
    );
  }
}

class _MetricTile extends StatelessWidget {
  const _MetricTile({
    required this.label,
    required this.value,
    required this.accentColor,
    required this.icon,
  });

  final String label;
  final String value;
  final Color accentColor;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
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
            Text(value, style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w900)),
            const SizedBox(height: 2),
            Text(label, style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ],
        ),
      ),
    );
  }
}
