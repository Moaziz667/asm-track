import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hive_flutter/hive_flutter.dart';

import 'app.dart';

Future<void> bootstrap() async {
  // Hive.initFlutter() handles the platform-specific directory internally.
  // On mobile/desktop it uses path_provider, On web it uses IndexedDB.
  await Hive.initFlutter();
  await Hive.openBox<Map<dynamic, dynamic>>('offline_queue');

  runApp(const ProviderScope(child: DriverApp()));
}
