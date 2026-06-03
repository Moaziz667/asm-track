import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons/lucide_icons.dart';

import '../../../app_providers.dart';
import '../../../config/app_config.dart';
import 'setup_account_screen.dart';
import 'workspace_screen.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});
  static const routeName = '/login';

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _formKey = GlobalKey<FormState>(debugLabel: 'login_form');
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
      // Navigation on success is handled centrally by DriverApp's auth listener.
    } catch (_) {
      if (mounted) {
        final message = ref.read(authControllerProvider).error ??
            'Echec de l\'authentification. Vérifiez vos identifiants.';
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..showSnackBar(SnackBar(content: Text(message)));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final auth = ref.watch(authControllerProvider);
    return Scaffold(
      body: Column(
        children: [
          Container(
            width: double.infinity,
            child: SafeArea(
              bottom: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(28, 40, 28, 30),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: 52,
                      height: 52,
                      decoration: BoxDecoration(
                        color: colorScheme.primary,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: const Icon(Icons.local_shipping_rounded, color: Colors.white, size: 28),
                    ),
                    const SizedBox(height: 24),
                    Text(
                      'Espace chauffeur',
                      style: theme.textTheme.headlineMedium,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Acces securise AsmOne',
                      style: theme.textTheme.labelMedium?.copyWith(color: colorScheme.primary),
                    ),
                  ],
                ),
              ),
            ),
          ),
          Divider(height: 1, color: colorScheme.outlineVariant),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(28, 32, 28, 40),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Identification', style: theme.textTheme.labelMedium?.copyWith(color: colorScheme.onSurfaceVariant)),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _phoneCtrl,
                      keyboardType: TextInputType.phone,
                      decoration: const InputDecoration(
                        hintText: 'Numero de telephone',
                        prefixIcon: Icon(LucideIcons.phone, size: 20),
                      ),
                      validator: (v) => (v == null || v.isEmpty) ? 'Requis' : null,
                    ),
                    const SizedBox(height: 24),
                    Text('Securite', style: theme.textTheme.labelMedium?.copyWith(color: colorScheme.onSurfaceVariant)),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _passwordCtrl,
                      obscureText: _obscure,
                      decoration: InputDecoration(
                        hintText: 'Mot de passe',
                        prefixIcon: const Icon(LucideIcons.lock, size: 20),
                        suffixIcon: IconButton(
                          icon: Icon(
                            _obscure ? LucideIcons.eye : LucideIcons.eyeOff,
                            size: 18,
                          ),
                          onPressed: () => setState(() => _obscure = !_obscure),
                        ),
                      ),
                      validator: (v) => (v == null || v.length < 6) ? 'Min 6 caracteres' : null,
                    ),
                    const SizedBox(height: 40),
                    SizedBox(
                      width: double.infinity,
                      height: 52,
                      child: FilledButton.icon(
                        onPressed: auth.isLoading ? null : _onSubmit,
                        icon: auth.isLoading
                            ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                            : const Icon(LucideIcons.logIn),
                        label: Text(auth.isLoading ? 'Connexion...' : 'Se connecter'),
                      ),
                    ),
                    if (auth.error != null) ...[
                      const SizedBox(height: 20),
                      _ErrorBanner(auth.error!, colorScheme),
                    ],
                    const SizedBox(height: 48),
                    Center(
                      child: Column(
                        children: [
                          TextButton(
                            onPressed: () => Navigator.of(context).pushNamed(SetupAccountScreen.routeName),
                            child: Text(
                              'Configurer mon compte',
                              style: theme.textTheme.labelMedium?.copyWith(color: colorScheme.primary),
                            ),
                          ),
                          TextButton(
                            onPressed: () async {
                              final storage = ref.read(tokenStorageProvider);
                              await storage.saveApiBaseUrl('');
                              final current = ref.read(appConfigProvider);
                              ref.read(appConfigProvider.notifier).state = AppConfig(apiBaseUrl: '', discoveryUrl: current.discoveryUrl);
                              if (context.mounted) {
                                Navigator.of(context).pushReplacementNamed(WorkspaceScreen.routeName);
                              }
                            },
                            child: Text(
                              "Changer d'espace de travail",
                              style: theme.textTheme.labelSmall?.copyWith(color: colorScheme.secondary),
                            ),
                          ),
                        ],
                      ),
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

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner(this.message, this.colorScheme);
  final String message;
  final ColorScheme colorScheme;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: colorScheme.errorContainer,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: colorScheme.error),
        ),
        child: Row(
          children: [
            Icon(Icons.error_outline_rounded, size: 16, color: colorScheme.error),
            const SizedBox(width: 8),
            Expanded(child: Text(message, style: TextStyle(fontSize: 13, color: colorScheme.error))),
          ],
        ),
      );
}
