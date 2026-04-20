import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hive_flutter/hive_flutter.dart';
import 'package:path_provider/path_provider.dart';

import 'app.dart';

Future<void> bootstrap() async {
  final dir = await getApplicationDocumentsDirectory();
  await Hive.initFlutter(dir.path);
  await Hive.openBox<Map<dynamic, dynamic>>('offline_queue');

  runApp(const ProviderScope(child: DriverApp()));
}
