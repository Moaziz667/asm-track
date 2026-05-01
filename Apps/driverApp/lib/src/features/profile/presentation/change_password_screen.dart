import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';

class ChangePasswordScreen extends ConsumerStatefulWidget {
  const ChangePasswordScreen({super.key});

  @override
  ConsumerState<ChangePasswordScreen> createState() => _ChangePasswordScreenState();
}

class _ChangePasswordScreenState extends ConsumerState<ChangePasswordScreen> {
  final _formKey = GlobalKey<FormState>();
  final _oldPasswordController = TextEditingController();
  final _newPasswordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  
  bool _isWorking = false;
  bool _obscureOld = true;
  bool _obscureNew = true;

  @override
  void dispose() {
    _oldPasswordController.dispose();
    _newPasswordController.dispose();
    _confirmPasswordController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('SECURITY SETTINGS'),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(LucideIcons.shieldAlert, size: 40, color: AppColors.neonYellow),
              const SizedBox(height: 16),
              Text(
                'UPDATE PASSWORD',
                style: GoogleFonts.spaceGrotesk(fontSize: 24, fontWeight: FontWeight.w900, color: AppColors.textPrimary, letterSpacing: -0.5),
              ),
              const SizedBox(height: 8),
              const Text(
                'Ensure your account remains secure by using a strong, unique password.',
                style: TextStyle(color: AppColors.muted, fontSize: 13, height: 1.4),
              ),
              const SizedBox(height: 32),
              
              Text('CURRENT PASSWORD', style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.muted, letterSpacing: 1.5)),
              const SizedBox(height: 12),
              TextFormField(
                controller: _oldPasswordController,
                obscureText: _obscureOld,
                decoration: InputDecoration(
                  hintText: '••••••••',
                  prefixIcon: const Icon(LucideIcons.key),
                  suffixIcon: IconButton(
                    icon: Icon(_obscureOld ? LucideIcons.eye : LucideIcons.eyeOff, size: 18),
                    onPressed: () => setState(() => _obscureOld = !_obscureOld),
                  ),
                ),
                validator: (v) => v == null || v.isEmpty ? 'Required' : null,
              ),
              
              const SizedBox(height: 24),
              Text('NEW PASSWORD', style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w900, color: AppColors.muted, letterSpacing: 1.5)),
              const SizedBox(height: 12),
              TextFormField(
                controller: _newPasswordController,
                obscureText: _obscureNew,
                decoration: InputDecoration(
                  hintText: 'New password',
                  prefixIcon: const Icon(LucideIcons.lock),
                  suffixIcon: IconButton(
                    icon: Icon(_obscureNew ? LucideIcons.eye : LucideIcons.eyeOff, size: 18),
                    onPressed: () => setState(() => _obscureNew = !_obscureNew),
                  ),
                ),
                validator: (v) {
                  if (v == null || v.length < 8) return 'Minimum 8 characters';
                  return null;
                },
              ),
              
              const SizedBox(height: 16),
              TextFormField(
                controller: _confirmPasswordController,
                obscureText: _obscureNew,
                decoration: const InputDecoration(
                  hintText: 'Confirm new password',
                  prefixIcon: Icon(LucideIcons.checkCircle),
                ),
                validator: (v) {
                  if (v != _newPasswordController.text) return 'Passwords do not match';
                  return null;
                },
              ),
              
              const SizedBox(height: 40),
              DriveButton(
                label: 'UPDATE PASSWORD',
                fullWidth: true,
                size: DriveButtonSize.lg,
                isLoading: _isWorking,
                onPressed: _submit,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    
    setState(() => _isWorking = true);
    try {
      final repo = ref.read(profileRepositoryProvider);
      await repo.changePassword(
        oldPassword: _oldPasswordController.text,
        newPassword: _newPasswordController.text,
      );
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Password updated successfully.')),
        );
        Navigator.pop(context);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Update failed: $e'), backgroundColor: AppColors.danger),
        );
      }
    } finally {
      if (mounted) setState(() => _isWorking = false);
    }
  }
}
