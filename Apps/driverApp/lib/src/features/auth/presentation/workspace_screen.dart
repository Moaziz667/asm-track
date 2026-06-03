import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app_providers.dart';
import '../../../config/app_config.dart';
import 'login_screen.dart';

class WorkspaceScreen extends ConsumerStatefulWidget {
  const WorkspaceScreen({super.key});

  static const routeName = '/workspace';

  @override
  ConsumerState<WorkspaceScreen> createState() => _WorkspaceScreenState();
}

class _WorkspaceScreenState extends ConsumerState<WorkspaceScreen> {
  final _codeController = TextEditingController();
  bool _isLoading = false;
  String? _error;
  bool _isManualMode = false;

  @override
  void dispose() {
    _codeController.dispose();
    super.dispose();
  }

  Future<void> _submitCode() async {
    final code = _codeController.text.trim();
    if (code.isEmpty) return;

    setState(() {
      _isLoading = true;
      _error = null;
    });

    if (_isManualMode) {
      if (!code.startsWith('http://') && !code.startsWith('https://')) {
        setState(() {
          _error = 'URL must start with http:// or https://';
          _isLoading = false;
        });
        return;
      }
      try {
        final storage = ref.read(tokenStorageProvider);
        await storage.saveApiBaseUrl(code);
        final current = ref.read(appConfigProvider);
        ref.read(appConfigProvider.notifier).state = AppConfig(apiBaseUrl: code, discoveryUrl: current.discoveryUrl);
        if (mounted) {
          Navigator.of(context).pushReplacementNamed(LoginScreen.routeName);
        }
      } catch (e) {
        setState(() {
          _error = 'Failed to save configuration';
          _isLoading = false;
        });
      }
      return;
    }

    final lookupCode = code.toUpperCase();
    try {
      final dio = Dio();
      final config = ref.read(appConfigProvider);
      final response = await dio.get(config.discoveryUrl);
      final Map<String, dynamic> clients = response.data is String 
          ? jsonDecode(response.data) 
          : response.data;

      final url = clients[lookupCode];
      if (url == null) {
        setState(() {
          _error = 'Company Code not found';
          _isLoading = false;
        });
        return;
      }

      final storage = ref.read(tokenStorageProvider);
      await storage.saveApiBaseUrl(url);
      final current = ref.read(appConfigProvider);
      ref.read(appConfigProvider.notifier).state = AppConfig(apiBaseUrl: url, discoveryUrl: current.discoveryUrl);

      if (mounted) {
        Navigator.of(context).pushReplacementNamed(LoginScreen.routeName);
      }
    } catch (e) {
      if (lookupCode.startsWith('HTTP')) {
        final url = lookupCode.toLowerCase();
        final storage = ref.read(tokenStorageProvider);
        await storage.saveApiBaseUrl(url);
        final current = ref.read(appConfigProvider);
        ref.read(appConfigProvider.notifier).state = AppConfig(apiBaseUrl: url, discoveryUrl: current.discoveryUrl);
        if (mounted) {
          Navigator.of(context).pushReplacementNamed(LoginScreen.routeName);
        }
        return;
      }
      setState(() {
        _error = 'Failed to connect to Discovery Service';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    return Scaffold(
      appBar: AppBar(title: Text(_isManualMode ? 'Manual Configuration' : 'Workspace Setup')),
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              _isManualMode ? 'Enter Backend API URL' : 'Enter your Company Code',
              style: theme.textTheme.headlineSmall,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              _isManualMode 
                  ? 'Specify the full URL of your deployment (e.g., https://api.asm.tn)' 
                  : 'Provided by your dispatcher (e.g., ASM01)',
              style: theme.textTheme.bodyMedium?.copyWith(color: colorScheme.onSurfaceVariant),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            TextField(
              controller: _codeController,
              decoration: InputDecoration(
                labelText: _isManualMode ? 'API Base URL' : 'Company Code',
                border: const OutlineInputBorder(),
                errorText: _error,
                hintText: _isManualMode ? 'https://' : null,
              ),
              textCapitalization: _isManualMode ? TextCapitalization.none : TextCapitalization.characters,
              keyboardType: _isManualMode ? TextInputType.url : TextInputType.text,
            ),
            const SizedBox(height: 24),
            SizedBox(
              height: 52,
              child: FilledButton(
                onPressed: _isLoading ? null : _submitCode,
                child: _isLoading 
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) 
                    : const Text('Continue'),
              ),
            ),
            const SizedBox(height: 16),
            TextButton(
              onPressed: _isLoading ? null : () {
                setState(() {
                  _isManualMode = !_isManualMode;
                  _error = null;
                  _codeController.clear();
                });
              },
              child: Text(_isManualMode ? 'Use Company Code instead' : 'Configure API URL manually'),
            ),
          ],
        ),
      ),
    );
  }
}
