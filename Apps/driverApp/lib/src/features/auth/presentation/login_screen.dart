import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app.dart';
import '../../../app_providers.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});
  static const routeName = '/login';

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _phoneCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  bool _obscure = true;

  @override
  void dispose() {
    _phoneCtrl.dispose();
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _onSubmit() async {
    if (!_formKey.currentState!.validate()) return;
    try {
      await ref.read(authControllerProvider.notifier).login(
            phone: _phoneCtrl.text.trim(),
            password: _passwordCtrl.text.trim(),
          );
      if (mounted) navigateToHome(context, ref.read(authControllerProvider).status);
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Authentication failed. Check credentials.')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(authControllerProvider);
    return Scaffold(
      backgroundColor: AppColors.background,
      body: Column(
        children: [
          // ── Navy header band ──────────────────────────────────────────────
          Container(
            color: AppColors.ink,
            child: SafeArea(
              bottom: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(28, 32, 28, 36),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: 44,
                      height: 44,
                      decoration: BoxDecoration(
                        color: AppColors.accent,
                        borderRadius: BorderRadius.circular(13),
                      ),
                      child: const Icon(PhosphorIconsFill.van, color: Colors.white, size: 22),
                    ),
                    const SizedBox(height: 20),
                    Text(
                      'Welcome back',
                      style: GoogleFonts.sora(fontSize: 26, fontWeight: FontWeight.w800, color: Colors.white, letterSpacing: -0.6),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Sign in to your driver account',
                      style: GoogleFonts.manrope(fontSize: 14, color: Colors.white.withValues(alpha: 0.62), fontWeight: FontWeight.w500),
                    ),
                  ],
                ),
              ),
            ),
          ),

          // ── Form area ─────────────────────────────────────────────────────
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(24, 28, 24, 40),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _Label('Phone number'),
                    const SizedBox(height: 8),
                    TextFormField(
                      controller: _phoneCtrl,
                      keyboardType: TextInputType.phone,
                      decoration: InputDecoration(
                        hintText: '+213 6xx xxx xxx',
                        prefixIcon: Icon(PhosphorIconsRegular.phone, size: 18),
                      ),
                      validator: (v) => (v == null || v.isEmpty) ? 'Required' : null,
                    ),
                    const SizedBox(height: 20),
                    _Label('Password'),
                    const SizedBox(height: 8),
                    TextFormField(
                      controller: _passwordCtrl,
                      obscureText: _obscure,
                      decoration: InputDecoration(
                        hintText: '••••••••',
                        prefixIcon: Icon(PhosphorIconsRegular.lockKey, size: 18),
                        suffixIcon: IconButton(
                          icon: Icon(
                            _obscure ? PhosphorIconsRegular.eye : PhosphorIconsRegular.eyeSlash,
                            size: 18,
                          ),
                          onPressed: () => setState(() => _obscure = !_obscure),
                        ),
                      ),
                      validator: (v) => (v == null || v.length < 6) ? 'Min 6 characters' : null,
                    ),
                    const SizedBox(height: 28),
                    DriveButton(
                      label: 'Sign In',
                      icon: PhosphorIconsBold.arrowRight,
                      isLoading: auth.isLoading,
                      onPressed: auth.isLoading ? null : _onSubmit,
                      fullWidth: true,
                      size: DriveButtonSize.lg,
                    ),
                    if (auth.error != null) ...[
                      const SizedBox(height: 14),
                      _ErrorBanner(auth.error!),
                    ],
                    const SizedBox(height: 24),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text("Don't have an account?",
                            style: GoogleFonts.manrope(fontSize: 13, color: AppColors.textSecondary, fontWeight: FontWeight.w500)),
                        TextButton(
                          onPressed: () => Navigator.of(context).pushNamed('/register'),
                          style: TextButton.styleFrom(foregroundColor: AppColors.accent, padding: const EdgeInsets.only(left: 4)),
                          child: Text('Register', style: GoogleFonts.manrope(fontSize: 13, fontWeight: FontWeight.w700)),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Label extends StatelessWidget {
  const _Label(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Text(
        text,
      style: GoogleFonts.manrope(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.textSecondary),
      );
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner(this.message);
  final String message;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: AppColors.dangerSubtle,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.dangerBorder),
        ),
        child: Row(
          children: [
            const Icon(PhosphorIconsFill.warningCircle, size: 16, color: AppColors.danger),
            const SizedBox(width: 8),
            Expanded(child: Text(message, style: const TextStyle(fontSize: 13, color: AppColors.danger))),
          ],
        ),
      );
}
