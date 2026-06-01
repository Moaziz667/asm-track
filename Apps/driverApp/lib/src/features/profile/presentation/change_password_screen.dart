import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';

class ChangePasswordScreen extends ConsumerStatefulWidget {
  const ChangePasswordScreen({super.key});

  @override
  ConsumerState<ChangePasswordScreen> createState() => _ChangePasswordScreenState();
}

class _ChangePasswordScreenState extends ConsumerState<ChangePasswordScreen> {
  final _formKey = GlobalKey<FormState>(debugLabel: 'change_password_form');
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
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Parametres de securite'),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(LucideIcons.shieldAlert, size: 40, color: colorScheme.primary),
              const SizedBox(height: 16),
              Text(
                'Modifier le mot de passe',
                style: theme.textTheme.headlineSmall,
              ),
              const SizedBox(height: 8),
              Text(
                'Assurez la securite de votre compte en utilisant un mot de passe fort et unique.',
                style: theme.textTheme.bodyMedium?.copyWith(color: colorScheme.onSurfaceVariant),
              ),
              const SizedBox(height: 32),
              
              Text('Mot de passe actuel', style: theme.textTheme.labelMedium?.copyWith(color: colorScheme.onSurfaceVariant)),
              const SizedBox(height: 12),
              TextFormField(
                controller: _oldPasswordController,
                obscureText: _obscureOld,
                decoration: InputDecoration(
                  hintText: '*******',
                  prefixIcon: const Icon(LucideIcons.key),
                  suffixIcon: IconButton(
                    icon: Icon(_obscureOld ? LucideIcons.eye : LucideIcons.eyeOff, size: 18),
                    onPressed: () => setState(() => _obscureOld = !_obscureOld),
                  ),
                ),
                validator: (v) => v == null || v.isEmpty ? 'Requis' : null,
              ),
              
              const SizedBox(height: 24),
              Text('Nouveau mot de passe', style: theme.textTheme.labelMedium?.copyWith(color: colorScheme.onSurfaceVariant)),
              const SizedBox(height: 12),
              TextFormField(
                controller: _newPasswordController,
                obscureText: _obscureNew,
                decoration: InputDecoration(
                  hintText: 'Nouveau mot de passe',
                  prefixIcon: const Icon(LucideIcons.lock),
                  suffixIcon: IconButton(
                    icon: Icon(_obscureNew ? LucideIcons.eye : LucideIcons.eyeOff, size: 18),
                    onPressed: () => setState(() => _obscureNew = !_obscureNew),
                  ),
                ),
                validator: (v) {
                  if (v == null || v.length < 8) return 'Minimum 8 caracteres';
                  if (!RegExp(r'^(?=.*[A-Za-z])(?=.*\d).{8,}$').hasMatch(v)) {
                    return 'Doit contenir une lettre et un chiffre';
                  }
                  return null;
                },
              ),
              
              const SizedBox(height: 16),
              TextFormField(
                controller: _confirmPasswordController,
                obscureText: _obscureNew,
                decoration: const InputDecoration(
                  hintText: 'Confirmer le nouveau mot de passe',
                  prefixIcon: Icon(LucideIcons.checkCircle),
                ),
                validator: (v) {
                  if (v != _newPasswordController.text) return 'Les mots de passe ne correspondent pas';
                  return null;
                },
              ),
              
              const SizedBox(height: 40),
              SizedBox(
                width: double.infinity,
                height: 52,
                child: FilledButton.icon(
                  onPressed: _isWorking ? null : _submit,
                  icon: _isWorking
                      ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(LucideIcons.lock),
                  label: Text(_isWorking ? 'Modification...' : 'Modifier le mot de passe'),
                ),
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
          const SnackBar(content: Text('Mot de passe mis a jour avec succes.')),
        );
        Navigator.pop(context);
      }
    } catch (e) {
      String message = 'Echec de la mise a jour';
      if (e is DioException) {
        final data = e.response?.data;
        if (data is Map && data.containsKey('message')) {
          message = data['message'];
        } else if (data is Map && data.containsKey('error')) {
          message = data['error'];
        }
      }
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(message), backgroundColor: Theme.of(context).colorScheme.error),
        );
      }
    } finally {
      if (mounted) setState(() => _isWorking = false);
    }
  }
}
