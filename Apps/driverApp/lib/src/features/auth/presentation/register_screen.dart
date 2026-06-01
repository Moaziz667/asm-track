import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app_providers.dart';

class RegisterScreen extends ConsumerStatefulWidget {
  const RegisterScreen({super.key});
  static const routeName = '/register';

  @override
  ConsumerState<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends ConsumerState<RegisterScreen> {
  final _formKey = GlobalKey<FormState>(debugLabel: 'register_form');
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
    // Navigation on success is handled centrally by DriverApp's auth listener.
    await ref.read(authControllerProvider.notifier)
        .register(_nameCtrl.text.trim(), _phoneCtrl.text.trim(), _passwordCtrl.text);
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
            color: colorScheme.surfaceContainerHigh,
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
                          color: colorScheme.onSurface.withValues(alpha: 0.08),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Icon(Icons.arrow_back_ios_new_rounded, size: 15, color: colorScheme.onSurface),
                      ),
                    ),
                    const SizedBox(width: 16),
                    Text(
                      'Creer un compte',
                      style: theme.textTheme.titleMedium,
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
                    Text('Inscription chauffeur', style: theme.textTheme.headlineSmall),
                    const SizedBox(height: 4),
                    Text('Remplissez vos informations pour commencer.',
                        style: theme.textTheme.bodyMedium?.copyWith(color: colorScheme.onSurfaceVariant)),
                    const SizedBox(height: 28),
                    _Field(label: 'Nom complet', child: TextFormField(
                      controller: _nameCtrl,
                      decoration: const InputDecoration(hintText: 'John Doe', prefixIcon: Icon(Icons.person_outline_rounded, size: 18)),
                      validator: (v) => (v == null || v.isEmpty) ? 'Requis' : null,
                    )),
                    const SizedBox(height: 18),
                    _Field(label: 'Numero de telephone', child: TextFormField(
                      controller: _phoneCtrl,
                      keyboardType: TextInputType.phone,
                      decoration: const InputDecoration(hintText: '+213 6xx xxx xxx', prefixIcon: Icon(Icons.phone_outlined, size: 18)),
                      validator: (v) => (v == null || v.isEmpty) ? 'Requis' : null,
                    )),
                    const SizedBox(height: 18),
                    _Field(label: 'Mot de passe', child: TextFormField(
                      controller: _passwordCtrl,
                      obscureText: _obscure,
                      decoration: InputDecoration(
                        hintText: '*******',
                        prefixIcon: const Icon(Icons.lock_outline_rounded, size: 18),
                        suffixIcon: IconButton(
                          icon: Icon(_obscure ? Icons.visibility_outlined : Icons.visibility_off_outlined, size: 18),
                          onPressed: () => setState(() => _obscure = !_obscure),
                        ),
                      ),
                      validator: (v) => (v == null || v.length < 6) ? 'Min 6 caracteres' : null,
                    )),
                    const SizedBox(height: 28),
                    SizedBox(
                      width: double.infinity,
                      height: 52,
                      child: FilledButton.icon(
                        onPressed: auth.isLoading ? null : _onSubmit,
                        icon: auth.isLoading
                            ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                            : const Icon(Icons.arrow_forward_rounded),
                        label: Text(auth.isLoading ? 'Inscription...' : 'Creer un compte'),
                      ),
                    ),
                    if (auth.error != null) ...[
                      const SizedBox(height: 12),
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: colorScheme.errorContainer,
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(color: colorScheme.error),
                        ),
                        child: Row(children: [
                          Icon(Icons.error_outline_rounded, size: 16, color: colorScheme.error),
                          const SizedBox(width: 8),
                          Expanded(child: Text(auth.error!, style: TextStyle(color: colorScheme.error, fontSize: 13))),
                        ]),
                      ),
                    ],
                    const SizedBox(height: 20),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text('Vous avez deja un compte ?', style: TextStyle(fontSize: 13, color: colorScheme.onSurfaceVariant)),
                        TextButton(
                          onPressed: () => Navigator.of(context).pop(),
                          child: Text('Se connecter', style: TextStyle(fontWeight: FontWeight.w600, color: colorScheme.primary)),
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
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600)),
        const SizedBox(height: 7),
        child,
      ],
    );
  }
}
