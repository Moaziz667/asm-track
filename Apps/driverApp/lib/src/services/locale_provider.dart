import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

class LocaleNotifier extends StateNotifier<String> {
  LocaleNotifier() : super('fr') {
    _load();
  }

  static const _key = 'driver_locale';

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    state = prefs.getString(_key) ?? 'fr';
  }

  Future<void> setLocale(String locale) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, locale);
    state = locale;
  }
}

final localeProvider = StateNotifierProvider<LocaleNotifier, String>((ref) {
  return LocaleNotifier();
});

class DriverCopy {
  static const Map<String, Map<String, String>> dict = {
    'fr': {
      // Navigation & Shell
      'tab_route': 'Tournée',
      'tab_calendar': 'Calendrier',
      'tab_profile': 'Profil',
      'offline_banner': 'Hors ligne — les actions seront synchronisées à la reconnexion',
      'notifications': 'Notifications',
      'no_notifications': 'Aucune notification',
      
      // Driver shift status
      'status_online': 'En service',
      'status_on_break': 'En pause',
      'status_offline': 'Hors service',
      'status_online_upper': 'EN SERVICE',
      'status_on_break_upper': 'EN PAUSE',
      'status_offline_upper': 'HORS SERVICE',
      
      // Shift actions
      'action_start_shift': 'Prendre mon service',
      'action_take_break': 'Prendre une pause',
      'action_end_day': 'Terminer ma journée',
      'action_resume_service': 'Reprendre le service',
      
      // Profile screen labels & actions
      'profile_title': 'PROFIL',
      'driver_id': 'ID Chauffeur',
      'phone': 'Téléphone',
      'last_ping': 'Dernier ping',
      'gps': 'GPS',
      'metric_delivered': 'Livré',
      'metric_failed': 'Échoué',
      'metric_total': 'Total',
      'section_actions': 'ACTIONS',
      'send_location': 'Envoyer ma position',
      'section_security': 'SÉCURITÉ',
      'change_password': 'Changer le mot de passe',
      'logout': 'Se déconnecter',
      'logout_confirm_title': 'Se déconnecter ?',
      'logout_confirm_body': 'Vous devrez vous reconnecter pour accéder à vos livraisons.',
      'cancel': 'Annuler',
      'position_sent': 'Position envoyée au dispatch.',
      
      // WebSocket / Toast notifications
      'ws_route_assigned': 'Tournée {route} assignée — consultez-la avant de partir.',
      'ws_route_cancelled': 'La tournée {route} a été annulée.',
      'ws_route_reassigned_away': 'La tournée {route} a été réaffectée à un autre chauffeur.',
      'ws_route_reassigned_to_you': 'La tournée {route} vous a été réaffectée !',
      'ws_stop_added': '{client} ajouté à {route}.',
      'ws_pickup_overdue': 'Chargement en retard — Dépôt {client} ({count}).',
      'ws_stop_removed': '{client}{ref} retiré de {route}{why}.',
      'ws_route_updated': 'La tournée {route} a été modifiée.',
      'ws_stops_transferred_out': 'Des arrêts ont été retirés de {route}.',
      'ws_stops_transferred_in': 'De nouveaux arrêts ont été ajoutés à {route}.',
      'ws_generic_route': 'votre tournée',
      'ws_handoff_incoming': 'Réception requise — colis {ref} de {name}',
      'ws_handoff_outgoing': 'À remettre — colis {ref} à {name}',
      'ws_handoff_confirmed': 'Transfert confirmé — colis {ref}',
      'ws_handoff_cancelled': 'Transfert annulé — colis {ref}',
      'ws_handoff_other': 'un chauffeur',
      'handoff_action_scan': 'Scanner',
      'handoff_action_show': 'Afficher le code',
      
      // Settings language selection
      'language_setting': 'Langue',
    },
    'en': {
      // Navigation & Shell
      'tab_route': 'Route',
      'tab_calendar': 'Calendar',
      'tab_profile': 'Profile',
      'offline_banner': 'Offline — actions will sync when connection is restored',
      'notifications': 'Notifications',
      'no_notifications': 'No notifications',
      
      // Driver shift status
      'status_online': 'Active Duty',
      'status_on_break': 'On Break',
      'status_offline': 'Offline',
      'status_online_upper': 'ACTIVE DUTY',
      'status_on_break_upper': 'ON BREAK',
      'status_offline_upper': 'OFFLINE',
      
      // Shift actions
      'action_start_shift': 'Start Shift',
      'action_take_break': 'Take a Break',
      'action_end_day': 'End Workday',
      'action_resume_service': 'Resume Service',
      
      // Profile screen labels & actions
      'profile_title': 'PROFILE',
      'driver_id': 'Driver ID',
      'phone': 'Phone Number',
      'last_ping': 'Last Ping',
      'gps': 'GPS',
      'metric_delivered': 'Delivered',
      'metric_failed': 'Failed',
      'metric_total': 'Total',
      'section_actions': 'ACTIONS',
      'send_location': 'Send my position',
      'section_security': 'SECURITY',
      'change_password': 'Change Password',
      'logout': 'Sign Out',
      'logout_confirm_title': 'Sign Out?',
      'logout_confirm_body': 'You will need to sign in again to access your deliveries.',
      'cancel': 'Cancel',
      'position_sent': 'Location sent to dispatcher.',
      
      // WebSocket / Toast notifications
      'ws_route_assigned': 'Route {route} assigned — please review before departure.',
      'ws_route_cancelled': 'Route {route} has been cancelled.',
      'ws_route_reassigned_away': 'Route {route} has been reassigned to another driver.',
      'ws_route_reassigned_to_you': 'Route {route} has been reassigned to you!',
      'ws_stop_added': '{client} added to route {route}.',
      'ws_pickup_overdue': 'Pickup overdue — Depot {client} ({count}).',
      'ws_stop_removed': '{client}{ref} removed from route {route}{why}.',
      'ws_route_updated': 'Route {route} has been updated.',
      'ws_stops_transferred_out': 'Stops were removed from route {route}.',
      'ws_stops_transferred_in': 'New stops were added to route {route}.',
      'ws_generic_route': 'your route',
      'ws_handoff_incoming': 'Pickup required — parcel {ref} from {name}',
      'ws_handoff_outgoing': 'To hand over — parcel {ref} to {name}',
      'ws_handoff_confirmed': 'Handover confirmed — parcel {ref}',
      'ws_handoff_cancelled': 'Handover cancelled — parcel {ref}',
      'ws_handoff_other': 'a driver',
      'handoff_action_scan': 'Scan',
      'handoff_action_show': 'Show code',
      
      // Settings language selection
      'language_setting': 'Language',
    },
    'ar': {
      // Navigation & Shell
      'tab_route': 'الرحلة',
      'tab_calendar': 'التقويم',
      'tab_profile': 'الملف الشخصي',
      'offline_banner': 'خارج الشبكة — سيتم مزامنة الإجراءات عند إعادة الاتصال',
      'notifications': 'الإشعارات',
      'no_notifications': 'لا توجد إشعارات',
      
      // Driver shift status
      'status_online': 'في الخدمة',
      'status_on_break': 'في استراحة',
      'status_offline': 'خارج الخدمة',
      'status_online_upper': 'في الخدمة',
      'status_on_break_upper': 'في استراحة',
      'status_offline_upper': 'خارج الخدمة',
      
      // Shift actions
      'action_start_shift': 'بدء الخدمة',
      'action_take_break': 'أخذ استراحة',
      'action_end_day': 'إنهاء العمل اليومي',
      'action_resume_service': 'استئناف الخدمة',
      
      // Profile screen labels & actions
      'profile_title': 'الملف الشخصي',
      'driver_id': 'معرف السائق',
      'phone': 'رقم الهاتف',
      'last_ping': 'آخر اتصال',
      'gps': 'موقع GPS',
      'metric_delivered': 'تم التوصيل',
      'metric_failed': 'فشل',
      'metric_total': 'الإجمالي',
      'section_actions': 'الإجراءات',
      'send_location': 'إرسال موقعي',
      'section_security': 'الأمان',
      'change_password': 'تغيير كلمة المرور',
      'logout': 'تسجيل الخروج',
      'logout_confirm_title': 'تسجيل الخروج؟',
      'logout_confirm_body': 'ستحتاج إلى تسجيل الدخول مرة أخرى للوصول إلى شحناتك.',
      'cancel': 'إلغاء',
      'position_sent': 'تم إرسال الموقع إلى المسؤول.',
      
      // WebSocket / Toast notifications
      'ws_route_assigned': 'تم تعيين الرحلة {route} — يرجى مراجعتها قبل الانطلاق.',
      'ws_route_cancelled': 'تم إلغاء الرحلة {route}.',
      'ws_route_reassigned_away': 'تم نقل الرحلة {route} لسائق آخر.',
      'ws_route_reassigned_to_you': 'تم إعادة تعيين الرحلة {route} إليك!',
      'ws_stop_added': 'تم إضافة {client} إلى الرحلة {route}.',
      'ws_pickup_overdue': 'تأخر التحميل — مستودع {client} ({count}).',
      'ws_stop_removed': 'تم إزالة {client}{ref} من الرحلة {route}{why}.',
      'ws_route_updated': 'تم تحديث الرحلة {route}.',
      'ws_stops_transferred_out': 'تم إزالة محطات من رحلتك {route}.',
      'ws_stops_transferred_in': 'تم إضافة محطات جديدة إلى رحلتك {route}.',
      'ws_generic_route': 'رحلتك',
      'ws_handoff_incoming': 'استلام مطلوب — الطرد {ref} من {name}',
      'ws_handoff_outgoing': 'للتسليم — الطرد {ref} إلى {name}',
      'ws_handoff_confirmed': 'تم تأكيد التسليم — الطرد {ref}',
      'ws_handoff_cancelled': 'أُلغي التسليم — الطرد {ref}',
      'ws_handoff_other': 'سائق',
      'handoff_action_scan': 'مسح',
      'handoff_action_show': 'إظهار الرمز',
      
      // Settings language selection
      'language_setting': 'اللغة',
    },
  };

  static String get(String key, String locale) {
    return dict[locale]?[key] ?? dict['fr']?[key] ?? key;
  }
}
