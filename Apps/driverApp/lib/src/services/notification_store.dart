import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hive/hive.dart';

class AppNotification {
  AppNotification({
    required this.id,
    required this.title,
    required this.body,
    required this.type,
    required this.receivedAt,
    this.read = false,
  });

  factory AppNotification.fromMap(Map<dynamic, dynamic> m) => AppNotification(
        id: m['id'] as String,
        title: m['title'] as String,
        body: m['body'] as String,
        type: m['type'] as String? ?? 'GENERAL',
        receivedAt: DateTime.parse(m['receivedAt'] as String),
        read: m['read'] as bool? ?? false,
      );

  final String id;
  final String title;
  final String body;
  final String type;
  final DateTime receivedAt;
  final bool read;

  Map<String, dynamic> toMap() => {
        'id': id,
        'title': title,
        'body': body,
        'type': type,
        'receivedAt': receivedAt.toIso8601String(),
        'read': read,
      };

  AppNotification copyWith({bool? read}) => AppNotification(
        id: id,
        title: title,
        body: body,
        type: type,
        receivedAt: receivedAt,
        read: read ?? this.read,
      );
}

class NotificationStore extends StateNotifier<List<AppNotification>> {
  NotificationStore() : super([]) {
    _load();
  }

  static const _boxName = 'notifications';
  static const _maxEntries = 50;

  Box get _box => Hive.box(_boxName);

  void _load() {
    final entries = _box.values
        .map((e) => AppNotification.fromMap(e as Map))
        .toList()
      ..sort((a, b) => b.receivedAt.compareTo(a.receivedAt));
    state = entries.take(_maxEntries).toList();
  }

  Future<void> add({
    required String title,
    required String body,
    required String type,
  }) async {
    final n = AppNotification(
      id: DateTime.now().millisecondsSinceEpoch.toString(),
      title: title,
      body: body,
      type: type,
      receivedAt: DateTime.now(),
    );
    await _box.put(n.id, n.toMap());
    // Evict oldest if over limit
    if (_box.length > _maxEntries) {
      final sorted = _box.keys.toList();
      await _box.delete(sorted.first);
    }
    _load();
  }

  Future<void> markAllRead() async {
    for (final n in state) {
      await _box.put(n.id, n.copyWith(read: true).toMap());
    }
    _load();
  }

  int get unreadCount => state.where((n) => !n.read).length;
}

final notificationStoreProvider =
    StateNotifierProvider<NotificationStore, List<AppNotification>>(
  (_) => NotificationStore(),
);

final unreadNotifCountProvider = Provider<int>((ref) {
  final list = ref.watch(notificationStoreProvider);
  return list.where((n) => !n.read).length;
});
