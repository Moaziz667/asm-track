import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hive_flutter/hive_flutter.dart';
import '../firebase_options.dart';

import 'app.dart';

Future<void> bootstrap() async {
  await Hive.initFlutter();
  await Hive.openBox<Map<dynamic, dynamic>>('offline_queue');
  await Firebase.initializeApp(
    options: DefaultFirebaseOptions.currentPlatform,
  );

  runApp(const ProviderScope(child: DriverApp()));
}
