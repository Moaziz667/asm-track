import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:lucide_icons/lucide_icons.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

import '../../../app_providers.dart';
import '../../../theme/app_theme.dart';
import '../../../theme/widgets.dart';
import 'login_screen.dart';

class SetupAccountScreen extends ConsumerStatefulWidget {
  const SetupAccountScreen({super.key});
  static const routeName = '/setup-account';

  @override
  ConsumerState<SetupAccountScreen> createState() => _SetupAccountScreenState();
}

class _SetupAccountScreenState extends ConsumerState<SetupAccountScreen> {
  final _formKey = GlobalKey<FormState>(debugLabel: 'setup_form');
  final _tokenCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  final _confirmCtrl = TextEditingController();

  bool _obscurePassword = true;
  bool _obscureConfirm = true;
  bool _isLoading = false;
  bool _isValidating = false;
  String? _validatedName;
  String? _error;
  String? _tokenError;

  @override
  void dispose() {
    _tokenCtrl.dispose();
    _passwordCtrl.dispose();
    _confirmCtrl.dispose();
    super.dispose();
  }

  Future<void> _validateToken() async {
    final token = _tokenCtrl.text.trim();
    if (token.isEmpty) {
      setState(() => _tokenError = 'Entrez votre code d\'activation');
      return;
    }

    setState(() {
      _isValidating = true;
      _tokenError = null;
      _validatedName = null;
    });

    try {
      final client = ref.read(apiClientProvider);
      final response = await client.dio.get<Map<String, dynamic>>(
        '/api/auth/driver/setup/validate',
        queryParameters: {'token': token},
      );
      final name = response.data?['name'] as String? ?? '';
      setState(() {
        _validatedName = name;
        _isValidating = false;
      });
    } catch (e) {
      setState(() {
        _tokenError = 'Code invalide ou expiré';
        _isValidating = false;
      });
    }
  }

  Future<void> _onSubmit() async {
    if (_validatedName == null) {
      await _validateToken();
      return;
    }
    if (!_formKey.currentState!.validate()) return;

    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      final client = ref.read(apiClientProvider);
      await client.dio.post<void>(
        '/api/auth/driver/setup',
        data: {
          'token': _tokenCtrl.text.trim(),
          'password': _passwordCtrl.text.trim(),
        },
      );

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Compte activé ! Connectez-vous avec votre numéro de téléphone.',
              style: GoogleFonts.spaceGrotesk(color: Colors.black, fontWeight: FontWeight.w700),
            ),
            backgroundColor: AppColors.neonYellow,
            duration: const Duration(seconds: 4),
          ),
        );
        Navigator.of(context).pushReplacementNamed(LoginScreen.routeName);
      }
    } catch (e) {
      setState(() {
        _error = 'Activation échouée. Vérifiez votre code et réessayez.';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.background,
      body: Column(
        children: [
          // ── Header ──────────────────────────────────────────────────────
          Container(
            color: AppColors.background,
            width: double.infinity,
            child: SafeArea(
              bottom: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(28, 40, 28, 30),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Container(
                          width: 52,
                          height: 52,
                          decoration: BoxDecoration(
                            color: AppColors.neonYellow,
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: const Icon(LucideIcons.truck, color: Colors.black, size: 28),
                        ),
                        const Spacer(),
                        TextButton(
                          onPressed: () => Navigator.of(context).pushReplacementNamed(LoginScreen.routeName),
                          child: Text(
                            'SE CONNECTER',
                            style: GoogleFonts.spaceGrotesk(
                              fontSize: 11,
                              fontWeight: FontWeight.w800,
                              color: AppColors.muted,
                              letterSpacing: 1,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 24),
                    Text(
                      'CONFIGURER MON COMPTE',
                      style: GoogleFonts.spaceGrotesk(
                        fontSize: 28,
                        fontWeight: FontWeight.w900,
                        color: AppColors.textPrimary,
                        letterSpacing: -1.2,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'ACTIVATION CHAUFFEUR',
                      style: GoogleFonts.spaceGrotesk(
                        fontSize: 12,
                        color: AppColors.neonYellow,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 1.5,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),

          const Divider(height: 1, color: AppColors.border),

          // ── Form ────────────────────────────────────────────────────────
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(28, 32, 28, 40),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Instructions banner
                    Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: AppColors.neonYellowSubtle,
                        border: Border.all(color: AppColors.neonYellowBorder),
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Icon(LucideIcons.info, size: 16, color: AppColors.neonYellow),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              'Entrez le code d\'activation reçu par email, puis choisissez votre mot de passe.',
                              style: GoogleFonts.spaceGrotesk(
                                fontSize: 13,
                                color: AppColors.neonYellow,
                                fontWeight: FontWeight.w600,
                                height: 1.5,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),

                    const SizedBox(height: 32),

                    // Token field
                    Text(
                      'CODE D\'ACTIVATION',
                      style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w800, color: AppColors.muted, letterSpacing: 1),
                    ),
                    const SizedBox(height: 12),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: TextFormField(
                            controller: _tokenCtrl,
                            style: GoogleFonts.spaceGrotesk(
                              color: AppColors.neonYellow,
                              fontWeight: FontWeight.w700,
                              fontSize: 13,
                              letterSpacing: 0.5,
                            ),
                            decoration: InputDecoration(
                              hintText: 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx',
                              prefixIcon: const Icon(LucideIcons.key, size: 18),
                              errorText: _tokenError,
                              suffixIcon: _validatedName != null
                                  ? const Icon(LucideIcons.checkCircle, size: 18, color: AppColors.neonYellow)
                                  : null,
                            ),
                            enabled: _validatedName == null,
                            onChanged: (_) {
                              if (_tokenError != null || _validatedName != null) {
                                setState(() {
                                  _tokenError = null;
                                  _validatedName = null;
                                });
                              }
                            },
                          ),
                        ),
                        if (_validatedName == null) ...[
                          const SizedBox(width: 8),
                          SizedBox(
                            height: 56,
                            child: ElevatedButton(
                              onPressed: _isValidating ? null : _validateToken,
                              style: ElevatedButton.styleFrom(
                                backgroundColor: AppColors.surfaceElevated,
                                foregroundColor: AppColors.textPrimary,
                                padding: const EdgeInsets.symmetric(horizontal: 16),
                              ),
                              child: _isValidating
                                  ? const SizedBox(
                                      width: 16,
                                      height: 16,
                                      child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.neonYellow),
                                    )
                                  : Text(
                                      'VALIDER',
                                      style: GoogleFonts.spaceGrotesk(fontSize: 12, fontWeight: FontWeight.w800, letterSpacing: 1),
                                    ),
                            ),
                          ),
                        ],
                      ],
                    ),

                    // Validated name confirmation
                    if (_validatedName != null) ...[
                      const SizedBox(height: 12),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                        decoration: BoxDecoration(
                          color: AppColors.neonYellowSubtle,
                          border: Border.all(color: AppColors.neonYellowBorder),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Row(
                          children: [
                            const Icon(LucideIcons.userCheck, size: 16, color: AppColors.neonYellow),
                            const SizedBox(width: 8),
                            Text(
                              'Bienvenue, $_validatedName',
                              style: GoogleFonts.spaceGrotesk(
                                fontSize: 13,
                                color: AppColors.neonYellow,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],

                    // Password fields — only shown after token is validated
                    if (_validatedName != null) ...[
                      const SizedBox(height: 32),
                      Text(
                        'MOT DE PASSE',
                        style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w800, color: AppColors.muted, letterSpacing: 1),
                      ),
                      const SizedBox(height: 12),
                      TextFormField(
                        controller: _passwordCtrl,
                        obscureText: _obscurePassword,
                        style: GoogleFonts.spaceGrotesk(color: AppColors.textPrimary, fontWeight: FontWeight.w600),
                        decoration: InputDecoration(
                          hintText: 'Minimum 6 caractères',
                          prefixIcon: const Icon(LucideIcons.lock, size: 20),
                          suffixIcon: IconButton(
                            icon: Icon(
                              _obscurePassword ? LucideIcons.eye : LucideIcons.eyeOff,
                              size: 18,
                              color: AppColors.muted,
                            ),
                            onPressed: () => setState(() => _obscurePassword = !_obscurePassword),
                          ),
                        ),
                        validator: (v) {
                          if (v == null || v.length < 6) return 'Minimum 6 caractères';
                          return null;
                        },
                      ),
                      const SizedBox(height: 20),
                      Text(
                        'CONFIRMER LE MOT DE PASSE',
                        style: GoogleFonts.spaceGrotesk(fontSize: 11, fontWeight: FontWeight.w800, color: AppColors.muted, letterSpacing: 1),
                      ),
                      const SizedBox(height: 12),
                      TextFormField(
                        controller: _confirmCtrl,
                        obscureText: _obscureConfirm,
                        style: GoogleFonts.spaceGrotesk(color: AppColors.textPrimary, fontWeight: FontWeight.w600),
                        decoration: InputDecoration(
                          hintText: 'Répétez votre mot de passe',
                          prefixIcon: const Icon(LucideIcons.lock, size: 20),
                          suffixIcon: IconButton(
                            icon: Icon(
                              _obscureConfirm ? LucideIcons.eye : LucideIcons.eyeOff,
                              size: 18,
                              color: AppColors.muted,
                            ),
                            onPressed: () => setState(() => _obscureConfirm = !_obscureConfirm),
                          ),
                        ),
                        validator: (v) {
                          if (v != _passwordCtrl.text) return 'Les mots de passe ne correspondent pas';
                          return null;
                        },
                      ),
                      const SizedBox(height: 40),
                      DriveButton(
                        label: 'ACTIVER MON COMPTE',
                        icon: LucideIcons.shieldCheck,
                        isLoading: _isLoading,
                        onPressed: _isLoading ? null : _onSubmit,
                        fullWidth: true,
                        size: DriveButtonSize.lg,
                      ),
                      if (_error != null) ...[
                        const SizedBox(height: 20),
                        _ErrorBanner(_error!),
                      ],
                    ],
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
  const _ErrorBanner(this.message);
  final String message;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: AppColors.dangerSubtle,
          borderRadius: BorderRadius.circular(4),
          border: Border.all(color: AppColors.dangerBorder),
        ),
        child: Row(
          children: [
            const Icon(PhosphorIconsFill.warningCircle, size: 16, color: AppColors.danger),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                message,
                style: const TextStyle(fontSize: 13, color: AppColors.danger),
              ),
            ),
          ],
        ),
      );
}
