import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app.dart';
import '../../../app_providers.dart';
import '../../../services/location_service.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';
import '../../auth/models/auth_models.dart';
import '../../deliveries/presentation/incident_report_screen.dart';
import 'change_password_screen.dart';

class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});

  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> {
  bool _locationSending = false;

  Future<void> _sendLocation() async {
    setState(() => _locationSending = true);
    try {
      final point = await LocationService().currentPosition();
      if (point != null) {
        await ref.read(profileRepositoryProvider).updateLocation(point.lat, point.lng);
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: const Text('Location broadcasted to ops.'),
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
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Sign out?'),
        content: const Text('You will need to sign in again to access your deliveries.', style: TextStyle(color: AppColors.muted)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel', style: TextStyle(color: AppColors.muted))),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Sign out', style: TextStyle(color: AppColors.danger))),
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

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 40),
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 20, bottom: 20),
          child: Text(
            'PROFILE',
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
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                            decoration: BoxDecoration(
                              color: AppColors.neonYellowSubtle,
                              borderRadius: BorderRadius.circular(2),
                              border: Border.all(color: AppColors.neonYellow.withValues(alpha: 0.3)),
                            ),
                            child: const Text('ACTIVE', style: TextStyle(fontSize: 10, fontWeight: FontWeight.w900, color: AppColors.neonYellow, letterSpacing: 1)),
                          ),
                        ],
                      ),
                    ),
                    // Details
                    Padding(
                      padding: const EdgeInsets.all(20),
                      child: Column(
                        children: [
                          InfoRow(label: 'Driver ID', value: profile.id),
                          const Divider(color: AppColors.border, height: 20),
                          InfoRow(label: 'Phone', value: profile.phone),
                          if (profile.lastLocationAt != null) ...[
                            const Divider(color: AppColors.border, height: 20),
                            InfoRow(
                              label: 'Last ping',
                              value: DateFormat('MMM d · HH:mm').format(profile.lastLocationAt!.toLocal()),
                            ),
                          ],
                          if (profile.currentLat != null && profile.currentLng != null) ...[
                            const Divider(color: AppColors.border, height: 20),
                            InfoRow(
                              label: 'GPS',
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
              // Stats
              statsAsync.when(
                data: (stats) => Row(
                  children: [
                    Expanded(child: MetricTile(label: 'Delivered', value: '${stats.delivered}', accentColor: AppColors.success, icon: Icons.check_circle_outline_rounded)),
                    const SizedBox(width: 10),
                    Expanded(child: MetricTile(label: 'Failed', value: '${stats.failed}', accentColor: AppColors.danger, icon: PhosphorIconsBold.xCircle)),
                    const SizedBox(width: 10),
                    Expanded(child: MetricTile(label: 'Total', value: '${stats.totalDeliveries}', accentColor: AppColors.accent, icon: PhosphorIconsBold.package)),
                  ],
                ),
                loading: () => const SizedBox(height: 80, child: Center(child: CircularProgressIndicator(color: AppColors.accent))),
                error: (_, __) => const SizedBox.shrink(),
              ),
              const SizedBox(height: 20),
              const SizedBox(height: 32),
              Text('ACTIONS', style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.muted, letterSpacing: 1.5)),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: DriveButton(
                  label: 'Broadcast Location',
                  icon: LucideIcons.mapPin,
                  isLoading: _locationSending,
                  onPressed: _locationSending ? null : _sendLocation,
                ),
              ),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: DriveButton(
                  label: 'Report General Incident',
                  icon: LucideIcons.alertTriangle,
                  variant: DriveButtonVariant.secondary,
                  onPressed: () => Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const IncidentReportScreen()),
                  ),
                ),
              ),
              const SizedBox(height: 32),
              Text('SECURITY', style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.muted, letterSpacing: 1.5)),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: DriveButton(
                  label: 'Change Password',
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
                  label: 'Sign Out',
                  icon: LucideIcons.logOut,
                  variant: DriveButtonVariant.danger,
                  onPressed: _logout,
                ),
              ),
            ],
          ),
          loading: () => const LoadingState(message: 'Loading profile…'),
          error: (_, __) => EmptyState(
            icon: PhosphorIconsRegular.userCircleMinus,
            title: 'Profile unavailable',
            action: () => ref.invalidate(driverProfileProvider),
            actionLabel: 'Retry',
          ),
        ),
      ],
    );
  }
}
