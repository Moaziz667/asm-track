import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';

import '../../../app.dart';
import '../../../app_providers.dart';
import '../models/auth_models.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';

class RegisterScreen extends ConsumerStatefulWidget {
  const RegisterScreen({super.key});
  static const routeName = '/register';

  @override
  ConsumerState<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends ConsumerState<RegisterScreen> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _phoneCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  bool _obscure = true;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _phoneCtrl.dispose();
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _onSubmit() async {
    if (!_formKey.currentState!.validate()) return;
    await ref.read(authControllerProvider.notifier)
        .register(_nameCtrl.text.trim(), _phoneCtrl.text.trim(), _passwordCtrl.text);
    final status = ref.read(authControllerProvider).status;
    if (mounted && status == AuthStatus.authenticated) navigateToHome(context, status);
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(authControllerProvider);
    return Scaffold(
      backgroundColor: AppColors.background,
      body: Column(
        children: [
          Container(
            color: AppColors.navy,
            child: SafeArea(
              bottom: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(28, 24, 28, 32),
                child: Row(
                  children: [
                    InkWell(
                      onTap: () => Navigator.of(context).pop(),
                      borderRadius: BorderRadius.circular(10),
                      child: Container(
                        width: 36,
                        height: 36,
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: 0.08),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: const Icon(Icons.arrow_back_ios_new_rounded, size: 15, color: Colors.white),
                      ),
                    ),
                    const SizedBox(width: 16),
                    Text(
                      'Create account',
                      style: GoogleFonts.inter(fontSize: 18, fontWeight: FontWeight.w700, color: Colors.white),
                    ),
                  ],
                ),
              ),
            ),
          ),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(24, 28, 24, 40),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Driver Registration',
                        style: GoogleFonts.inter(fontSize: 22, fontWeight: FontWeight.w700, color: AppColors.textPrimary, letterSpacing: -0.4)),
                    const SizedBox(height: 4),
                    const Text('Fill in your details to get started.',
                        style: TextStyle(fontSize: 14, color: AppColors.muted)),
                    const SizedBox(height: 28),
                    _Field(label: 'Full name', child: TextFormField(
                      controller: _nameCtrl,
                      decoration: const InputDecoration(hintText: 'John Doe', prefixIcon: Icon(Icons.person_outline_rounded, size: 18)),
                      validator: (v) => (v == null || v.isEmpty) ? 'Required' : null,
                    )),
                    const SizedBox(height: 18),
                    _Field(label: 'Phone number', child: TextFormField(
                      controller: _phoneCtrl,
                      keyboardType: TextInputType.phone,
                      decoration: const InputDecoration(hintText: '+213 6xx xxx xxx', prefixIcon: Icon(Icons.phone_outlined, size: 18)),
                      validator: (v) => (v == null || v.isEmpty) ? 'Required' : null,
                    )),
                    const SizedBox(height: 18),
                    _Field(label: 'Password', child: TextFormField(
                      controller: _passwordCtrl,
                      obscureText: _obscure,
                      decoration: InputDecoration(
                        hintText: '••••••••',
                        prefixIcon: const Icon(Icons.lock_outline_rounded, size: 18),
                        suffixIcon: IconButton(
                          icon: Icon(_obscure ? Icons.visibility_outlined : Icons.visibility_off_outlined, size: 18),
                          onPressed: () => setState(() => _obscure = !_obscure),
                        ),
                      ),
                      validator: (v) => (v == null || v.length < 6) ? 'Min 6 characters' : null,
                    )),
                    const SizedBox(height: 28),
                    DriveButton(
                      label: 'Create Account',
                      icon: Icons.arrow_forward_rounded,
                      isLoading: auth.isLoading,
                      onPressed: auth.isLoading ? null : _onSubmit,
                      fullWidth: true,
                      size: DriveButtonSize.lg,
                    ),
                    if (auth.error != null) ...[
                      const SizedBox(height: 12),
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: AppColors.dangerSubtle,
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(color: AppColors.dangerBorder),
                        ),
                        child: Row(children: [
                          const Icon(Icons.error_outline_rounded, size: 16, color: AppColors.danger),
                          const SizedBox(width: 8),
                          Expanded(child: Text(auth.error!, style: const TextStyle(color: AppColors.danger, fontSize: 13))),
                        ]),
                      ),
                    ],
                    const SizedBox(height: 20),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Text('Already have an account?', style: TextStyle(fontSize: 13, color: AppColors.muted)),
                        TextButton(
                          onPressed: () => Navigator.of(context).pop(),
                          style: TextButton.styleFrom(foregroundColor: AppColors.accent),
                          child: Text('Sign in', style: GoogleFonts.inter(fontSize: 13, fontWeight: FontWeight.w600)),
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

class _Field extends StatelessWidget {
  const _Field({required this.label, required this.child});
  final String label;
  final Widget child;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: GoogleFonts.inter(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.textSecondary)),
          const SizedBox(height: 7),
          child,
        ],
      );
}
