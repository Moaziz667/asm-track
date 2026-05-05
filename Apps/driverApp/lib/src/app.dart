import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app_providers.dart';
import 'features/auth/models/auth_models.dart';
import 'features/auth/presentation/login_screen.dart';
import 'features/auth/presentation/register_screen.dart';
import 'features/auth/presentation/splash_screen.dart';
import 'features/deliveries/presentation/delivery_detail_screen.dart';
import 'features/home/presentation/home_shell.dart';
import 'features/pod/presentation/pod_form_screen.dart';
import 'theme/app_theme.dart';

class DriverApp extends ConsumerStatefulWidget {
  const DriverApp({super.key});

  @override
  ConsumerState<DriverApp> createState() => _DriverAppState();
}

class _DriverAppState extends ConsumerState<DriverApp> {
  @override
  void initState() {
    super.initState();
    Future.microtask(() => ref.read(authControllerProvider.notifier).bootstrap());
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'asmDrive',
      debugShowCheckedModeBanner: false,
      theme: buildAppTheme(),
      routes: {
        SplashScreen.routeName: (_) => const SplashScreen(),
        LoginScreen.routeName: (_) => const LoginScreen(),
        RegisterScreen.routeName: (_) => const RegisterScreen(),
        HomeShell.routeName: (_) => const HomeShell(),
      },
      initialRoute: SplashScreen.routeName,
      onGenerateRoute: (settings) {
        if (settings.name == DeliveryDetailScreen.routeName) {
          final args = settings.arguments as DeliveryDetailArgs;
          return MaterialPageRoute(
            builder: (_) => DeliveryDetailScreen(args: args),
            settings: settings,
          );
        }
        if (settings.name == PodFormScreen.routeName) {
          final args = settings.arguments as PodFormArgs;
          return MaterialPageRoute(
            builder: (_) => PodFormScreen(args: args),
            settings: settings,
          );
        }
        return null;
      },
    );
  }
}

void navigateToHome(BuildContext context, AuthStatus status) {
  if (status == AuthStatus.authenticated) {
    Navigator.of(context).pushNamedAndRemoveUntil(HomeShell.routeName, (route) => false);
  } else if (status == AuthStatus.unauthenticated) {
    Navigator.of(context).pushNamedAndRemoveUntil(LoginScreen.routeName, (route) => false);
  }
}
