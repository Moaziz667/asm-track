import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hive_flutter/hive_flutter.dart';
import '../firebase_options.dart';

import 'app.dart';
import 'services/token_storage.dart';

Future<void> bootstrap() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Hive.initFlutter();

  final tokenStorage = TokenStorage();
  final dbKey = await tokenStorage.getOrCreateDbKey();
  final cipher = HiveAesCipher(dbKey);

  await Hive.openBox<Map<dynamic, dynamic>>('offline_queue', encryptionCipher: cipher);
  await Hive.openBox('notifications', encryptionCipher: cipher);
  await Hive.openBox('domain_cache', encryptionCipher: cipher);

  await Firebase.initializeApp(
    options: DefaultFirebaseOptions.currentPlatform,
  );

  runApp(const ProviderScope(child: DriverApp()));
}

