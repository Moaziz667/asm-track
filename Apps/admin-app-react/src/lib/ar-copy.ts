// ── Notification template helpers (local to avoid a cycle with ux-copy) ─────
const _ar_fmtEta = (iso: string): string => {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  // 24h clock keeps the digits scannable on mobile RTL.
  return d.toLocaleTimeString('ar', { hour: '2-digit', minute: '2-digit', hour12: false });
};
const _ar_ref = (p: any): string => p?.orderId ? `${p.orderId} · ` : '';
// Arabic stop counting: 1=محطة، 2=محطتان، 3-10=N محطات، 11+=N محطة
const _ar_stops = (n: number): string => {
  if (n <= 0) return '';
  if (n === 1) return 'محطة واحدة';
  if (n === 2) return 'محطتان';
  if (n >= 3 && n <= 10) return `${n} محطات`;
  return `${n} محطة`;
};
const _ar_money = (amount: any, currency?: string): string => {
  if (amount == null || amount === '') return '';
  const n = Number(amount);
  if (!isFinite(n)) return '';
  const cur = (currency && String(currency).trim()) || 'TND';
  return `${n.toLocaleString('ar', { maximumFractionDigits: 2 })} ${cur}`;
};
const _ar_clock = (v: any): string => {
  if (typeof v !== 'string') return '';
  const m = /^(\d{2}):(\d{2})/.exec(v);
  return m ? `${m[1]}:${m[2]}` : '';
};
const _ar_window = (start: any, end: any): string => {
  const s = _ar_clock(start);
  const e = _ar_clock(end);
  if (s && e) return `${s}–${e}`;
  return s || e || '';
};

export const AR_COPY = {
  // ── Actions (button labels) ────────────────────────────────────────
  actions: {
    cancelRoute: 'إلغاء الرحلة',
    validateRoute: 'تأكيد الرحلة',
    reassignRoute: 'إعادة تعيين الرحلة',
    closeRoute: 'إغلاق الرحلة',
    deleteDraft: 'حذف المسودة',
    addStop: 'إضافة محطة',
    removeStop: 'إزالة المحطة',
    pinLocation: 'تحديد موقع GPS',
    confirm: 'تأكيد',
    cancel: 'إلغاء',
    close: 'إغلاق',
    back: '← عودة',
    refresh: 'تحديث',
    save: 'حفظ',
    create: 'إنشاء',
    edit: 'تعديل',
    delete: 'حذف',
    view: 'عرض التفاصيل',
    export: 'تصدير',
    import: 'استيراد',
    search: 'بحث',
    filter: 'تصفية',
    clearFilters: 'مسح الفلاتر',
    newRoute: 'رحلة جديدة',
    newVehicle: 'مركبة جديدة',
    newDriver: 'سائق جديد',
    // Delivery exception actions
    reassignDelivery: 'إعادة تعيين للسائق',
    transferToRoute: 'نقل إلى رحلة',
    unassignDelivery: 'إرجاع إلى قائمة الانتظار',
    bulkReassign: 'إعادة تعيين جماعية',
    swapDeliveries: 'تبديل الشحنات',
    postpone: 'تأجيل لليوم التالي',
    returnToDepot: 'إرجاع للمستودع وإعادة التوزيع',
    handoverInField: 'تسليم ميداني',
  },

  // ── Tooltips (title of icon buttons) ────────────────────────────
  tooltips: {
    cancelRoute: 'إلغاء هذه الرحلة نهائياً',
    validateRoute: 'تغيير حالة الرحلة إلى مؤكدة',
    reassignRoute: 'تعيين هذه الرحلة لسائق آخر',
    closeRoute: 'إغلاق هذه الرحلة يدوياً',
    deleteDraft: 'حذف مسودة الرحلة هذه',
    viewDetail: 'عرض تفاصيل هذا العنصر',
    addStop: 'إضافة شحنة لهذه الرحلة',
    removeStop: 'إزالة هذه المحطة من الرحلة',
    pinLocation: 'تحديد إحداثيات GPS يدوياً',
    refresh: 'تحديث البيانات',
    export: 'تصدير البيانات',
    // Delivery exception tooltips
    reassignDelivery: 'نقل هذه الشحنة لسائق آخر (مع الاحتفاظ بالملكية)',
    transferToRoute: 'وضع هذه الشحنة في رحلة موجودة في الترتيب المختار',
    unassignDelivery: 'إزالة من الرحلة وإرجاعها لقائمة الشحنات غير المعينة',
    capacityOver: 'تجاوزت سعة المركبة — اختر مركبة أخرى أو قسّم الشحنة',
    capacityOk: 'سعة المركبة متاحة',
  },

  // ── Empty States ──────────────────────────────────────────────────────
  empty: {
    routes: 'لا توجد رحلات تطابق الفلاتر المحددة',
    deliveries: 'لا توجد شحنات تطابق المعايير المحددة',
    drivers: 'لا يوجد سائقون مسجلون — ابدأ بإضافة سائق',
    vehicles: 'لا توجد مركبات في الأسطول — أضف مركبة أولى',
    depots: 'لم يتم تكوين أي مستودعات',
    zones: 'لم يتم تحديد أي مناطق جغرافية',
    stops: 'لا توجد محطات في هذه الرحلة',
    history: 'لا يوجد سجل متاح لهذا العنصر',
    pod: 'لا يوجد إثبات تسليم متاح لهذه المحطة',
    exceptions: 'لا توجد استثناءات لمعالجتها — كل شيء على ما يرام',
    notifications: 'لا توجد إشعارات معلقة',
    auditLogs: 'لا توجد سجلات تدقيق متاحة',
    search: 'لم يتم العثور على نتائج لهذا البحث',
    generic: 'لا توجد نتائج',
  },

  // ── Toast Errors ────────────────────────────────────────────────────
  errors: {
    missingGps: 'لا يمكن التأكيد — بعض المحطات تفتقر إلى إحداثيات GPS',
    cancelFailed: 'حدث خطأ أثناء إلغاء الرحلة',
    reassignFailed: 'حدث خطأ أثناء إعادة التعيين',
    validateFailed: 'حدث خطأ أثناء تأكيد الرحلة',
    closeFailed: 'حدث خطأ أثناء إغلاق الرحلة',
    deleteFailed: 'حدث خطأ أثناء الحذف',
    loadFailed: 'تعذر تحميل البيانات — يرجى المحاولة مرة أخرى',
    saveFailed: 'حدث خطأ أثناء الحفظ',
    importFailed: 'حدث خطأ أثناء الاستيراد',
    exportFailed: 'حدث خطأ أثناء التصدير',
    networkError: 'خطأ في الشبكة — تحقق من اتصالك',
    // Reassign/transfer errors
    capacityExceeded: 'تم تجاوز سعة المركبة لهذه الرحلة',
    stopOrderInvalid: 'ترتيب غير صالح — يجب أن يكون بين 1 وعدد المحطات + 1',
    timeWindowInvalid: 'النافذة الزمنية خارج حدود الرحلة',
    unassignFailed: 'حدث خطأ أثناء الإرجاع لقائمة الانتظار',
    transferFailed: 'حدث خطأ أثناء النقل',
  },

  // ── Toast Success ─────────────────────────────────────────────────────
  success: {
    routeCancelled: 'تم إلغاء الرحلة — أُعيدت الشحنات المجدولة إلى قائمة الانتظار',
    routeValidated: 'تم تأكيد الرحلة بنجاح',
    routeClosed: 'تم إغلاق الرحلة',
    routeDeleted: 'تم حذف المسودة',
    routeReassigned: 'تم إعادة تعيين الرحلة بنجاح',
    saved: 'تم الحفظ بنجاح',
    created: 'تم الإنشاء بنجاح',
    deleted: 'تم الحذف بنجاح',
    exported: 'تم التصدير بنجاح',
    imported: 'تم الاستيراد بنجاح',
    deliveryReassigned: 'تم إعادة تعيين الشحنة بنجاح',
    deliveryUnassigned: 'تم إرجاع الشحنة إلى قائمة الانتظار',
    deliveryTransferred: 'تم نقل الشحنة إلى الرحلة المستهدفة',
  },

  // ── Confirmation Dialogs ────────────────────────────────────────
  confirm: {
    cancelRoute: {
      title: 'إلغاء الرحلة؟',
      body: 'سيتم إرجاع الشحنات المجدولة إلى قائمة الانتظار. الطرود التي تم استلامها بالفعل ستبقى مع السائق.',
      confirm: 'نعم، إلغاء',
    },
    deleteRoute: {
      title: 'حذف المسودة؟',
      body: 'هذا الإجراء غير قابل للتراجع. سيتم حذف المسودة نهائياً.',
      confirm: 'حذف',
    },
    closeRoute: {
      title: 'إغلاق الرحلة؟',
      body: 'سيتم وضع علامة مغلقة على الرحلة. هذا الإجراء لا يمكن التراجع عنه.',
      confirm: 'إغلاق',
    },
    deleteVehicle: {
      title: 'حذف المركبة؟',
      body: 'سيتم إزالة المركبة من الأسطول. هذا الإجراء غير قابل للتراجع.',
      confirm: 'حذف',
    },
    deleteDriver: {
      title: 'حذف السائق؟',
      body: 'سيتم حذف ملف السائق من النظام.',
      confirm: 'حذف',
    },
  },

  // ── Field Placeholders ──────────────────────────────────────────────
  placeholders: {
    searchRoutes: 'البحث عن رحلة...',
    searchDeliveries: 'العميل، المرجع، المعرف...',
    searchDrivers: 'اسم السائق أو رقم الهاتف...',
    searchVehicles: 'النوع، الموديل، رقم اللوحة...',
    searchGeneric: 'بحث...',
    reason: 'السبب',
    city: 'المدينة...',
    date: 'التاريخ',
    notes: 'ملاحظات أو تعليمات...',
  },

  // ── Section and Page Labels ─────────────────────────────────────────
  pages: {
    routes: {
      title: 'الرحلات',
      subtitle: 'تخطيط وتتبع رحلات التسليم',
    },
    routeDetail: {
      backLabel: '← العودة للرحلات',
      kpiProgress: 'التقدم',
      kpiDelay: 'التأخير المتراكم',
      kpiSla: 'الالتزام باتفاقية الخدمة SLA',
      kpiRevenue: 'إجمالي الإيرادات',
      kpiDistance: 'إجمالي المسافة',
      kpiDriver: 'السائق',
      kpiVehicle: 'المركبة',
      stopsTitle: 'ترتيب التسليم',
      orderInfo: 'معلومات الطلب',
      statusLog: 'سجل الحالة',
      pod: 'إثبات التسليم',
      legacyTitle: 'المحطات التي تمت إزالتها من الرحلة',
      sidebarDriver: 'السائق المعين',
      sidebarVehicle: 'المركبة',
      sidebarDepot: 'مستودع البداية',
      sidebarMetrics: 'مقاييس الرحلة',
      sidebarLifecycle: 'دورة الحياة',
    },
    deliveries: {
      title: 'التأهل الجغرافي',
      subtitle: 'خط تأهيل الشحنات وتتبع العمليات الجغرافية',
      // Quick views
      quickViewAll: 'جميع الحالات',
      quickViewNeedsPinning: 'تحديد موقع مطلوب',
      quickViewUnassigned: 'بانتظار التعيين',
      quickViewInTransit: 'التسليم جارٍ',
      quickViewCompleted: 'التسليمات المؤكدة',
      quickViewFailed: 'الحوادث والعائدات',
      // Filter labels
      filterLabel: 'المرشحات',
      showFilters: 'إظهار المرشحات',
      hideFilters: 'إخفاء المرشحات',
      filterByStatus: 'جميع الحالات',
      filterByDriver: 'بدون تصفية السائق',
      filterByZone: 'جميع المناطق',
      filterClear: 'مسح المرشحات',
      searchPlaceholder: 'البحث حسب المرجع أو العميل أو العنوان...',
      // Tabs
      tabList: 'القائمة',
      tabMap: 'الخريطة',
      // Table headers
      refHeader: 'المرجع',
      clientHeader: 'العميل',
      addressHeader: 'العنوان',
      driverHeader: 'السائق',
      zoneHeader: 'المنطقة',
      statusHeader: 'الحالة',
      // Buttons & Actions
      pinButton: 'تحديد الموقع',
      cancelButton: 'إلغاء التسليم',
      downloadBl: 'تحميل سند التسليم',
      trackingLink: 'رابط التتبع',
      // Modals & Messages
      pinModalTitle: 'تحقق من الموقع الجغرافي',
      pinModalSearch: 'البحث عن عنوان...',
      pinModalConfirm: 'تم تأكيد الموقع',
      pinReverseGeocoding: 'جاري تحديد الموقع...',
      cancelModalTitle: 'إلغاء التسليم',
      cancelModalLabel: 'السبب (اختياري)',
      cancelModalPlaceholder: 'اشرح سبب إلغاء هذا التسليم...',
      // Messages
      backorderCreated: 'تم إنشاء طلب إضافي',
      deliveryCreated: 'تم إنشاء التسليم',
      deliveryCancelled: 'تم إلغاء التسليم',
      trackingCopied: 'تم نسخ رابط التتبع!',
      loadError: 'خطأ في التحميل',
      backorderError: 'خطأ الطلب الإضافي',
      pinError: 'خطأ تحديد الموقع',
      downloadError: 'خطأ تحميل سند التسليم',
      unknownDriver: 'تعيين غير معروف',
      outOfZone: 'خارج المنطقة',
      lockedGeocoding: 'التأهل مغلق',
      // Pagination & Layout
      totalFlow: 'إجمالي التدفق',
    },
    drivers: {
      title: 'السائقون',
      subtitle: 'إدارة الموظفين والمتابعة الميدانية والتشغيلية',
    },
    vehicles: {
      title: 'أسطول المركبات',
      subtitle: 'إدارة وتتبع المركبات والآليات التشغيلية',
    },
    dashboard: {
      title: 'لوحة التحكم',
      subtitle: 'مركز القيادة والتحكم التشغيلي',
    },
    depots: {
      title: 'المستودعات',
      subtitle: 'إعداد مستودعات ومراكز البداية والنهاية',
    },
    zones: {
      title: 'المناطق الجغرافية',
      subtitle: 'تقسيم المناطق الجغرافية لتحسين وتخطيط الرحلات',
    },
    exceptions: {
      title: 'الاستثناءات',
      subtitle: 'إدارة الحوادث، فشل التسليم، وإرجاع الشحنات لقائمة الانتظار',
    },
    dispatch: {
      title: 'التوزيع (Dispatch)',
      subtitle: 'فحص الاستثناءات — إعادة تعيين أو تعيين تسليم',
    },
    import: {
      title: 'مركز الاستيراد',
      subtitle: 'دمج وتأهيل البيانات القادمة من نظام ERP',
    },
    settings: {
      title: 'الإعدادات',
      subtitle: 'تكوين نظام الإدارة والتحكم بالنظام',
    },
    auditLogs: {
      title: 'سجلات التدقيق',
      subtitle: 'تتبع ومراقبة كاملة للعمليات الإدارية والنظام',
    },
    performance: {
      title: 'الأداء',
      subtitle: 'تحليلات ومؤشرات الأداء التشغيلي والفعالية',
    },
    notifications: {
      title: 'الإشعارات',
      subtitle: 'مركز إدارة التنبيهات والإشعارات والتحذيرات',
    },
    reports: {
      title: 'التقارير',
      subtitle: 'إنشاء وتصدير التقارير التشغيلية والإحصاءات',
    },
    operations: {
      title: 'نظرة عامة',
      subtitle: 'مراقبة العمليات والأسطول في الوقت الفعلي',
    },
    routeBuilder: {
      title: 'إنشاء رحلة',
      subtitle: 'تخطيط المسارات وتحسينها على الخريطة',
    },
    companies: {
      title: 'الشركات',
      subtitle: 'إدارة المستأجرين وتكوينات نظام ERP',
    },
  },

  // ── Status Labels ──────────────────────────────────
  statusLabels: {
    DRAFT: 'مسودة',
    VALIDATED: 'مؤكدة',
    IN_PROGRESS: 'نشطة',
    CLOSED: 'مكتملة',
    COMPLETED: 'مكتملة',
    CANCELLED: 'ملغاة',
    FAILED: 'فاشلة',
    SLA_BREACH: 'خارج SLA',
    UNSCHEDULED: 'غير مخطط',
    SCHEDULED: 'مخطط',
    PENDING: 'قيد الانتظار',
    ARRIVED: 'وصل',
    PICKED_UP: 'تم الاستلام',
    IN_TRANSIT: 'في الطريق',
    DELIVERED: 'تم التسليم',
    PARTIALLY_DELIVERED: 'تسليم جزئي',
    PARTIAL: 'جزئي',
    REMOVED: 'تمت إزالته',
    REMOVED_REPLANNED: 'تمت إعادة جدولته',
    REMOVED_CANCELLED: 'ملغى (مزال)',
    FAILED_ATTEMPT: 'محاولة فاشلة',
  } as Record<string, string>,

  // ── Delivery Failure Codes ────────────────────────────────────────
  failureCodes: {
    CLIENT_ABSENT:   'العميل غائب',
    REFUSED:         'رفض من العميل',
    WRONG_ADDRESS:   'العنوان غير صحيح',
    DAMAGED:         'المنتج تالف',
    OTHER:           'سبب آخر',
  } as Record<string, string>,

  // ── Order Source ─────────────────────────────────────────────
  sources: {
    APP:    'تطبيق الهاتف',
    ODOO:   'نظام Odoo',
    MANUAL: 'إدخال يدوي',
    ERP:    'نظام ERP',
  } as Record<string, string>,

  // ── ERP Synchronization Status ────────────────────────────────────
  syncStatus: {
    SYNCED:          'تمت المزامنة',
    PENDING_RETRY:   'مزامنة معلقة',
    PENDING_CANCEL:  'إلغاء معلق',
    SYNC_FAILED:     'فشل المزامنة',
  } as Record<string, string>,

  // ── Delivery Priority ─────────────────────────────────────────────
  priorities: {
    HIGH:   'عاجل',
    NORMAL: 'عادي',
    LOW:    'منخفض',
    URGENT: 'عاجل',
  } as Record<string, string>,

  // ── Event Actor ─────────────────────────────────
  actors: {
    SYSTEM:     'النظام',
    AUTO:       'تلقائي',
    DRIVER:     'السائق',
    ADMIN:      'المدير',
    DISPATCHER: 'الموزع',
    MANAGER:    'المدير',
    ERP:        'نظام ERP',
    ODOO:       'نظام Odoo',
  } as Record<string, string>,

  // ── Refusal Reason / Item Issue ───────────────────────────────
  itemReasons: {
    CLIENT_ABSENT:    'العميل غائب',
    CLIENT_REJECTED:  'رفض من العميل',
    DAMAGED:          'المنتج تالف',
    WRONG_ITEM:       'منتج خاطئ',
    WRONG_ADDRESS:    'العنوان غير صحيح',
    POSTPONED:        'مؤجل',
    OTHER:            'آخر',
    REFUSED:          'مرفوض من العميل',
  } as Record<string, string>,

  // ── Item Outcome (partial delivery) ───────────────────────
  outcomes: {
    DELIVERED:           'تم التسليم',
    PARTIAL:             'جزئي',
    PARTIALLY_DELIVERED: 'جزئي',
    REFUSED:             'مرفوض',
    DAMAGED:             'تالف',
    MISSING:             'مفقود',
    RETURNED:            'مرتجع',
    WRONG_ITEM:          'منتج خاطئ',
    NOT_HOME:            'العميل غائب',
    EXPIRED:             'منتهي الصلاحية',
    POSTPONED:           'مؤجل',
  } as Record<string, string>,

  // ── SLA Status / Delay ────────────────────────────────────────────────
  delayStatus: {
    EARLY:   'مبكر',
    ON_TIME: 'في الوقت المحدد',
    LATE:    'متأخر',
  } as Record<string, string>,

  // ── Driver / Vehicle Availability ───────────────────────────────
  availability: {
    driverAvailable: 'متاح للتعيين',
    driverBusy: 'في رحلة نشطة',
    driverOnStandby: 'في وضع الاستعداد',
    vehicleAvailable: 'متاحة',
    vehicleBusy: 'معينة في رحلة',
    vehicleOffline: 'خارج الخدمة',
  },

  // ── Loading States ───────────────────────────────────────────────────────
  loading: {
    map: 'جاري تحميل الخريطة...',
    data: 'جاري التحميل...',
    saving: 'جاري الحفظ...',
    generic: 'جاري التحميل...',
  },

  // ── notificationsDropdown ─────────────────────────────────────────────
  notificationsDropdown: {
    title: 'الإشعارات',
    markAllRead: 'تحديد الكل كمقروء',
    viewAll: 'عرض الكل',
    noNotifications: 'لا توجد إشعارات',
    today: 'اليوم',
    thisWeek: 'هذا الأسبوع',
    older: 'أقدم',
    viewMore: 'عرض {count} إشعارات إضافية ←',
    justNow: 'الآن',
    minutesAgo: 'منذ {minutes} د',
    hoursAgo: 'منذ {hours} س',
    daysAgo: 'منذ {days} ي',
  },

  notificationsPage: {
    title: 'سجل العمليات',
    statUnread: 'غير مقروءة',
    statCritical: 'حرجة',
    statTotal: 'أحداث',
    markAllRead: 'تحديد الكل كمقروء',
    clearAll: 'مسح الكل',
    eventSingular: 'حدث',
    eventPlural: 'أحداث',
    filters: {
      all: 'الكل',
      unread: 'غير المقروءة',
      critical: 'حرجة',
      warning: 'تحذيرات',
      info: 'معلومات',
    },
    groups: {
      today: 'اليوم',
      week: 'هذا الأسبوع',
      older: 'أقدم',
    },
    empty: {
      title: 'كل شيء هادئ',
      subtitleAll: 'الأسطول يعمل بسلاسة. ستُبَث أحداث التوصيلات والمسارات وERP هنا في الوقت الفعلي.',
      subtitleFiltered: 'لا توجد أحداث تطابق هذا الفلتر.',
    },
    time: {
      justNow: 'الآن',
      minutesAgo: 'منذ {minutes} د',
      hoursAgo: 'منذ {hours} س',
      daysAgo: 'منذ {days} ي',
      dateLocale: 'ar',
    },
  },

  // ── globalSearch ──────────────────────────────────────────────────────
  globalSearch: {
    triggerPlaceholder: 'البحث أو الانتقال إلى...',
    inputPlaceholder: 'اكتب لبدء البحث...',
    searching: 'جاري البحث...',
    minChars: 'اكتب حرفين على الأقل',
    noResults: 'لم يتم العثور على نتائج',
    groups: {
      recents: 'عمليات البحث الأخيرة',
      systemActions: 'إجراءات النظام',
      quickNav: 'الملاحة السريعة',
      deliveries: 'الشحنات',
      routes: 'الرحلات',
      drivers: 'السائقين',
      vehicles: 'المركبات',
      depots: 'المستودعات',
      zones: 'المناطق الجغرافية',
    },
    system: {
      themeTitle: 'تغيير مظهر الشاشة',
      themeDesc: 'التبديل بين المظهر الفاتح والمظلم',
      refreshTitle: 'تحديث بيانات النظام',
      refreshDesc: 'مزامنة لوحة القيادة مع الخادم',
      driverTitle: 'إضافة سائق جديد',
      driverDesc: 'دعوة سائق للانضمام إلى منصة ASM اللوجستية',
    },
    shortcuts: {
      navigate: 'تصفح',
      open: 'افتح',
      close: 'أغلق',
    },
  },

  // ── Dashboard page ──────────────────────────────────────────────────────
  dashboardPage: {
    syncError: 'فشل المزامنة',
    kpiTotal: 'الإجمالي',
    kpiInTransit: 'في الطريق',
    kpiDelivered: 'تم التسليم',
    kpiExceptions: 'الاستثناءات',
    periodDay: 'اليوم',
    periodWeek: 'هذا الأسبوع',
    periodMonth: 'هذا الشهر',
    periodAll: 'الكل',
    dispatchFlowTitle: 'تدفق التوزيع',
    plannerButton: 'المخطط',
    emptyState: 'فارغ',
    driverPerformanceTitle: 'أداء السائق',
    chartLegendTotal: 'الإجمالي',
    chartLegendDelivered: 'تم التسليم',
    serviceQualityTitle: 'جودة الخدمة',
    slaRateLabel: 'معدل اتفاقية الخدمة',
    statsCompleted: 'مكتملة',
    statsInProgress: 'قيد التقدم',
    statsExceptions: 'الاستثناءات',
    unknownClient: 'عميل غير معروف',
    moreDeliveries: '+{count} المزيد',
  },

  // ── Operations Page ─────────────────────────────────────────────────────
  operationsPage: {
    // Header
    subtitle: 'تتبع الرحلات',
    title: 'مركز التحكم العملياتي',
    // Tabs
    tabToday: 'اليوم',
    tabWeek: 'هذا الأسبوع',
    // KPIs
    kpiActiveRoutes: 'الرحلات النشطة',
    kpiFieldDrivers: 'السائقون الميدانيون',
    kpiCompletedStops: 'المحطات المكتملة',
    kpiFailures: 'الأعطال / التنبيهات',
    // Section: To Start
    sectionStart: 'رحلات معلقة',
    noRoutesWaiting: 'لا توجد رحلات معلقة',
    // Section: Watchpoints
    sectionWatchpoints: 'نقاط الانتباه',
    allUnderControl: 'كل شيء تحت السيطرة',
    // Performance analysis
    sectionPerformance: 'تحليل الأداء',
    labelSuccess: 'ناجحة',
    labelFailures: 'فاشلة',
    labelOngoing: 'قيد التنفيذ',
    // Routes table
    tableTodayRoutes: 'رحلات اليوم',
    tableRoute: 'الرحلة',
    tableDriver: 'السائق',
    tableStatus: 'الحالة',
    tableProgress: 'التقدم',
    noRoutes: 'لا توجد رحلات',
    // Week tab
    thisWeek: 'هذا الأسبوع',
    routesCount: 'رحلات',
    completion: 'إكمال',
    weekInProgress: 'قيد التنفيذ',
    weekClosed: 'مكتملة',
    weekTotal: 'الإجمالي',
    // Day and month names
    dayNames: ['الاثنين', 'الثلاثاء', 'الأربعاء', 'الخميس', 'الجمعة', 'السبت', 'الأحد'],
    monthNames: ['يناير', 'فبراير', 'مارس', 'أبريل', 'مايو', 'يونيو', 'يوليو', 'أغسطس', 'سبتمبر', 'أكتوبر', 'نوفمبر', 'ديسمبر'],
  },

  // ── Dispatch Desk Page ─────────────────────────────────────────────────────
  dispatchDeskPage: {
    // Mobile tabs
    tabFilters: 'المرشحات',
    tabDispatch: 'التوزيع',
    // Filter panel
    filterToggleReduce: 'تقليل المرشحات',
    filterToggleShow: 'إظهار المرشحات',
    filterQuickSearch: 'بحث سريع',
    filterQuickSearchPlaceholder: 'المرجع، العميل، السائق...',
    // Period filter
    filterPeriod: 'الفترة',
    periodDay: 'اليوم',
    periodWeek: 'الأسبوع',
    periodMonth: 'الشهر',
    periodAll: 'الكل',
    periodCustom: 'نطاق',
    dateFrom: 'من',
    dateTo: 'إلى',
    // Other filters
    filterDriver: 'السائق',
    filterDriverPlaceholder: 'جميع السائقين',
    filterDriverNoFilter: 'بدون مرشح السائق',
    filterZone: 'المنطقة',
    filterZonePlaceholder: 'جميع المناطق',
    filterZoneGlobal: 'جميع المناطق',
    filterRoute: 'الرحلة',
    filterRoutePlaceholder: 'الكل',
    filterStatus: 'الحالة',
    filterStatusPlaceholder: 'جميع الحالات',
    filterStatusAll: 'جميع الحالات',
    filterStatusUnscheduled: 'غير مجدول',
    filterStatusScheduled: 'مجدول',
    filterStatusPickedUp: 'تم الاستلام',
    filterStatusInTransit: 'في الطريق',
    filterStatusDelivered: 'تم التسليم',
    filterStatusPartial: 'تسليم جزئي',
    filterStatusCancelled: 'ملغى',
    filterStatusFailed: 'فاشلة',
    // Buttons
    buttonLoading: 'جاري التحميل…',
    buttonRefresh: 'تحديث',
    // Active drivers section
    activeDriversLabel: 'في الخدمة',
    // KPI strip
    kpiCritical: 'حرجة',
    kpiUnassigned: 'غير معينة',
    kpiInTransit: 'قيد التسليم',
    kpiFailed: 'فشلت',
    // Tabs
    tabAssign: 'التكليفات',
    tabAction: 'يتطلب إجراء',
    tabFailed: 'فشلت',
    tabMissingGps: 'GPS مفقود',
    tabHandoff: 'التسليمات',
    kpiUpdated: 'آخر تحديث',
    cardCreated: 'أُنشئت',
    handoffEmpty: 'لا توجد تسليمات جارية',
    handoffStateRequested: 'في انتظار الرمز',
    handoffStateInProgress: 'تم إصدار الرمز · بانتظار المسح',
    handoffStateOverdue: 'متأخر',
    handoffCancelButton: 'إلغاء',
    handoffCancelTitle: 'إلغاء التسليم',
    handoffCancelDescription: 'سيتم إلغاء التسليم. يبقى الطرد مع السائق المُرسِل ويجب إعادة جدولته.',
    handoffCancelReasonLabel: 'السبب (اختياري)',
    handoffCancelConfirm: 'إلغاء التسليم',
    // New alerts banner
    newAlertSingular: 'تنبيه جديد',
    newAlertPlural: 'تنبيهات جديدة',
    newAlertBannerRefresh: 'تحديث',
    // Toast messages
    errorLoadingAlerts: 'فشل تحميل التنبيهات',
    errorCannotReassign: 'لا يمكن إعادة تعيين هذه الشحنة في حالتها الحالية',
    errorCannotReplan: 'لا يمكن إعادة جدولة هذه الشحنة في حالتها الحالية',
    successReplanned: 'تمت إعادة جدولة الشحنة بنجاح',
    errorReplan: 'فشل في إعادة جدولة الشحنة',
    errorNoteRequired: 'يرجى إضافة ملاحظة قبل التأكيد',
    successCancelled: 'تم إلغاء الشحنة',
    errorCancel: 'فشل الإلغاء',
    successReturnConfirmed: 'تم تأكيد العودة',
    errorReturnConfirm: 'فشل تأكيد العودة',
    // Table headers
    tableHeaderOrder: 'الطلب',
    tableHeaderStatus: 'الحالة',
    tableHeaderClient: 'العميل',
    tableHeaderZone: 'المنطقة',
    tableHeaderDriver: 'السائق',
    tableHeaderMotif: 'السبب',
    tableHeaderAlert: 'التنبيه',
    tableHeaderCoordinates: 'الإحداثيات',
    // Table content
    noActionRequired: 'لا يتطلب أي إجراء',
    noOrdersFound: 'لم يتم العثور على طلبات',
    unassigned: 'غير معين',
    missingGps: 'GPS مفقود',
    fixGps: 'إصلاح →',
    // Modal titles
    returnTitle: 'تأكيد العودة إلى المستودع',
    returnDescription: 'تم إرجاع الرزمة إلى المستودع بنجاح. سيتم إعادة تعيينها للجدولة مرة أخرى.',
    returnNoteLabel: 'ملاحظة العودة (اختياري)',
    returnConfirmLabel: 'تأكيد العودة',
    cancelTitle: 'إلغاء الشحنة نهائياً',
    cancelDescription: 'هذا الإجراء لا يمكن التراجع عنه. سيتم تحديد الشحنة كملغاة ولن تتمكن من إعادة جدولتها.',
    cancelReasonLabel: 'سبب الإلغاء (إلزامي)',
    cancelConfirmLabel: 'إلغاء نهائي',
    keepLabel: 'احتفظ',
    // Detail modal
    detailTitle: 'التفاصيل',
    orderLabel: 'الطلب',
    openLink: 'فتح →',
    driverLabel: 'السائق',
    replanButton: 'إعادة الجدولة',
    reassignButton: 'إعادة التعيين',
    // Tooltips
    reassignTooltip: 'إعادة التعيين',
    assignDriverTooltip: 'تعيين سائق',
    replanTooltip: 'إعادة الجدولة',
    callClientTooltip: 'اتصل بالعميل',
    callDriverTooltip: 'اتصل بالسائق',
    // Additional descriptions
    reasonLabel: 'السبب',
    noComment: 'لا توجد ملاحظات',
    reprogrammationLabel: 'إعادة الجدولة',
    byLabel: 'من قبل',
    // Motif labels (formatMotif)
    motifSlaUnscheduled: 'طلب غير مجدول',
    motifSlaScheduled: 'عالق في المستودع',
    motifSlaPickup: 'استلام متأخر',
    motifSlaInTransit: 'تسليم متأخر',
    motifScheduledMonitoring: 'التوقف السابق قيد التنفيذ',
    motifClientAbsent: 'العميل غائب',
    motifRefused: 'رفض التسليم',
    motifWrongAddress: 'عنوان خاطئ',
    motifDamaged: 'الطرد تالف',
    motifOther: 'سبب آخر',
    motifPartialDelivery: 'تسليم جزئي',
    motifFailed: 'فشل التسليم',
    motifCancelled: 'تم الإلغاء',
    motifUnscheduled: 'غير مجدول',
    motifScheduled: 'مجدول',
    motifPickedUp: 'تم الاستلام',
    motifInTransit: 'قيد التسليم',
    motifDelivered: 'تم التسليم',
    motifPartiallyDelivered: 'تم التسليم جزئياً',
    motifUnknown: 'حادثة غير معروفة',
    // Time elapsed (formatElapsed)
    timeJustNow: 'للتو',
    timeMinutes: '{diff} دقيقة',
    timeHours: '{h}س{mm}',
    timeDays: '{d}ي',
    // Comments (formatComment)
    commentSlaUnscheduled: 'في انتظار رحلة منذ {time}',
    commentSlaScheduled: 'الطرد في المستودع لكن لم يتم استلامه منذ {time}',
    commentSlaPickup: 'السائق لم يصل بعد إلى العميل · في انتظار منذ {time}',
    commentSlaInTransit: 'قيد التسليم لكن متأخر · تم الإبلاغ عنه منذ {time}',
    commentScheduledMonitoring: 'السائق ينهي التوقف السابق · سيتم معالجته بعد ذلك',
    commentClientAbsent: 'العميل كان غائباً عند محاولة التسليم · منذ {time}',
    commentRefused: 'رفض العميل التسليم · منذ {time}',
    commentWrongAddress: 'عنوان التسليم غير موجود أو خاطئ · منذ {time}',
    commentDamaged: 'تم الإبلاغ عن الطرد التالف · منذ {time}',
    commentOther: 'فشل التسليم بدون سبب محدد · منذ {time}',
    commentPartial: 'تم الإبلاغ عن تسليم جزئي منذ {time}',
    commentFailed: 'فشل التسليم · منذ {time}',
    commentCancelled: 'تم الإلغاء منذ {time}',
    commentDefault: 'تم الإبلاغ عنه منذ {time}',
    // Suggestions (formatSuggestion)
    suggestionSlaUnscheduled: '→ جدولة في رحلة',
    suggestionScheduledMonitoring: '→ مراقبة — لا توجد إجراءات فورية',
    suggestionWrongAddress: '→ تصحيح العنوان في تفاصيل الطلب',
    suggestionOther: '→ التحقق من تعليق السائق',
    // ReplanModal
    replanModalTitleReplan: 'إعادة جدولة للتخطيط',
    replanModalTitleReassign: 'إعادة تعيين التسليم',
    replanModalLabelOrder: 'الطلب',
    replanModalLabelProblem: 'المشكلة',
    replanModalLabelClient: 'العميل',
    replanModalWhatWillHappen: 'ماذا سيحدث؟',
    replanModalDescription: 'سيتم إزالة هذا التسليم من الرحلة الحالية{routeName} وإعادته إلى قائمة الانتظار. سيقوم المخطط بإعادة تعيينه إلى رحلة جديدة.',
    replanModalNoteLabel: 'ملاحظة (مطلوبة)',
    replanModalNoteHint: 'اشرح بإيجاز سبب قيامك بهذا الإجراء',
    replanModalNotePlaceholder: 'مثال: العميل غائب، عنوان خاطئ، السائق غير متاح…',
    // ActionRow button tooltips
    buttonReassign: 'إعادة تعيين',
    buttonAssign: 'تعيين سائق',
    buttonReplan: 'إعادة الجدولة',
    buttonCallClient: 'اتصل بالعميل',
    buttonContactClient: 'التواصل مع العميل',
    buttonCallDriver: 'اتصل بالسائق',
    buttonReturnToDepot: 'العودة إلى المستودع',
    // ActionRow labels
    unassignedLabel: 'غير معين',
    incidentLabel: 'حادثة',
    criticalLabel: 'حرج',
    reportedLabel: 'تم الإبلاغ عنه',
    updatedLabel: 'تم التحديث',
    clientLabel: 'العميل',
    routeLabel: 'الرحلة',
    routeInProgress: 'جارٍ',
    routeValidated: 'موثق',
    routeDraft: 'مسودة',
    noRouteAssigned: 'لا توجد رحلة مخصصة',
  },

  // ── ReassignDrawer ───────────────────────────────────────────────────────
  reassignDrawer: {
    // Step labels
    stepDriver: 'السائق',
    stepRoute: 'الرحلة',
    stepConfigure: 'تكوين',
    // Route status
    routeDraft: 'مسودة',
    routeValidated: 'موثق',
    routeInProgress: 'جارٍ',
    routeClosed: 'مغلق',
    routeCancelled: 'ملغى',
    // Drawer title
    assignTitle: 'تعيين التسليم',
    assignBatchTitle: '{count} تعيينات',
    reassignTitle: 'إعادة تعيين',
    batchTitle: '{count} تسليمات',
    // Buttons
    backButton: 'رجوع',
    changeDriver: 'تغيير',
    confirmReassign: 'تأكيد إعادة التعيين',
    confirmBatch: 'تأكيد ({count} تسليمات)',
    // Step 1: Driver selection
    searchPlaceholder: 'البحث عن سائق…',
    onlineWithRoute: 'نشط — مع رحلة',
    onlineNoRoute: 'نشط — بدون رحلة',
    autoRouteCreated: 'تم إنشاء الرحلة تلقائياً كمسودة',
    onBreak: 'في فترة راحة',
    showOffline: 'عرض',
    hideOffline: 'إخفاء',
    offlineLabel: 'غير متصل',
    offlineWarning: 'سيتلقى السائقون غير المتصلين المهمة ولكنهم غير نشطين.',
    noDriver: 'لم يتم العثور على سائقين',
    // Stop info
    stopFree: 'متاح',
    loadingRoutes: 'جاري التحميل…',
    noRoutes: 'بدون رحلة',
    // Step 2: Route selection
    loadingRoutesStep2: 'جاري تحميل الرحلات…',
    noActiveRoutes: 'لا توجد رحلات نشطة (30 يوم القادمة)',
    noActiveRoutesDesc: 'هذا السائق لا يحتوي على رحلات مجدولة. قم بإنشاء واحدة من صفحة الرحلات.',
    routeCount: '{count} رحلة{plural}',
    // Step 3: Configure
    timeWindowLabel: 'نافذة التسليم',
    timeWindowStart: 'البدء',
    timeWindowEnd: 'النهاية',
    timeWindowError: 'النهاية قبل البدء',
    timeWindowErrorDesc: 'يجب أن يكون وقت النهاية بعد وقت البدء',
    noteForDriver: 'ملاحظة للسائق',
    noteOptional: '(اختياري)',
    noteRequired: '*',
    notePlaceholder: 'اشرح إعادة التعيين…',
    noteInternalPlaceholder: 'ملاحظة داخلية (اختياري)…',
  },

  reassignCommandOverlay: {
    deliveryLabel: 'الشحنة',
    title: 'إعادة تعيين العنصر',
    driverTab: 'السائق',
    routeTab: 'الرحلة',
    searchDriver: 'البحث عن سائق…',
    searchRoute: 'البحث عن رحلة…',
    unnamed: 'سائق بدون اسم',
    activeRoute: 'الرحلة النشطة',
    stops: 'المحطات',
    noResults: 'لم يتم العثور على أي عناصر',
    noDriver: 'لا يوجد سائق معين',
    operationParams: 'المعطيات العملياتية',
    orderLabel: 'ترتيب المرور بالمحطة',
    timeStart: 'وقت البدء',
    timeEnd: 'وقت النهاية',
    noteLabel: 'ملاحظة',
    notePlaceholder: 'اشرح سبب إعادة التعيين…',
    capacityTarget: 'استهلاك السعة',
    forceWarning: 'فرض التعيين (تجاهل التحذير)',
    cancelBtn: 'إلغاء',
    confirmBtn: 'تأكيد',
  },

  // ── Delivery Detail Page ──────────────────────────────────────────────────
  deliveryPage: {
    notFound: 'لم يتم العثور على التسليم',
    cancelled: 'ملغى',
    loadingFile: 'جاري تحميل الملف...',
    returnButton: 'رجوع',
    // Section: Client
    sectionClient: 'العميل',
    labelName: 'الاسم',
    labelPhone: 'رقم الهاتف',
    labelAddress: 'العنوان',
    labelCity: 'المدينة',
    labelPostalCode: 'الرمز البريدي',
    labelZone: 'المنطقة',
    // Section: Order
    sectionOrder: 'الطلب',
    labelReference: 'المرجع',
    labelInternalId: 'معرف داخلي',
    labelTotalWeight: 'الوزن الإجمالي',
    labelAmount: 'المبلغ',
    labelSource: 'المصدر',
    labelSyncErp: 'مزامنة ERP',
    labelCreatedAt: 'تم الإنشاء',
    labelUpdatedAt: 'تم التحديث',
    // Section: Driver & Route
    sectionDriverRoute: 'السائق والرحلة',
    labelDriver: 'السائق',
    labelRoute: 'الرحلة',
    seeRoute: 'عرض الرحلة',
    // Section: Items
    sectionItems: 'العناصر',
    itemsCount: 'عناصر · {count} صف{plural}',
    // Table headers
    tableDesignation: 'الوصف',
    tableSku: 'SKU',
    tableQty: 'الكمية',
    tableQtyDone: 'الكمية المسلمة',
    tableUnitPrice: 'سعر الوحدة',
    tableTotal: 'الإجمالي',
    // Section: Timeline
    sectionTimeline: 'تتبع الحالة',
    noHistory: 'لا يوجد سجل متاح.',
    // Section: Proof of Delivery
    sectionProof: 'إثبات التسليم',
    noPhoto: 'لا توجد صورة',
    noBL: 'لا توجد وثيقة توثيق موقعة',
  },

  // ── Breadcrumbs ──────────────────────────────────────────────────────────
  breadcrumbs: {
    home: 'الرئيسية',
    separator: '/',
  },

  // ── TopNav & Theme ──────────────────────────────────────────────────────
  topNav: {
    toggleSidebar: 'تبديل الشريط الجانبي',
    myAccount: 'حسابي',
    logout: 'تسجيل الخروج',
    lightMode: 'الوضع الفاتح',
    darkMode: 'الوضع المظلم',
    enableLightMode: 'تفعيل الوضع الفاتح',
    enableDarkMode: 'تفعيل الوضع المظلم',
    user: 'المستخدم',
  },

  // ── Routes Detail Page ──────────────────────────────────────────────────
  routeDetailPage: {
    labelOrder: 'الطلب',
    labelErpRef: 'مرجع ERP',
    labelWeight: 'الوزن',
    labelQty: 'الكمية',
    labelSource: 'المصدر',
    labelTimeWindow: 'نافذة التسليم',
    labelInstructions: 'التعليمات',
    labelNotes: 'ملاحظة',
    labelDepot: 'المستودع',
    labelDeparture: 'المغادرة',
    noDepot: 'لا يوجد مستودع',
    labelLifecycle: 'دورة الحياة',
    statusCreated: 'تم الإنشاء',
    statusValidated: 'تم التحقق',
    statusStarted: 'تم البدء',
    statusClosed: 'مغلقة',
    labelStopsSequence: 'تسلسل المحطات',
    tabDetails: 'التفاصيل',
    tabHistory: 'السجل',
    tabProof: 'الإثبات',
    labelArticles: 'المقالات',
    tableArticle: 'المقال',
    tableOrdered: 'مطلوب',
    tableDelivered: 'تم التسليم',
    tableStatus: 'الحالة',
    tableUnitPrice: 'السعر الوحدوي',
    labelTotal: 'الإجمالي',
    createBackorder: 'إنشاء طلب إضافي',
    loading: 'جاري التحميل...',
    noPodAvailable: 'لا يوجد إثبات تسليم متاح',
    signedBL: 'وثيقة التسليم الموقعة',
    photoPod: 'صورة الإثبات',
    photo: 'صورة',
    deliveryNote: 'وثيقة التسليم',
    buttonTimeWindow: 'نافذة',
    buttonRemove: 'إزالة',
    buttonCancel: 'إلغاء',
    modalCancelStopTitle: 'إلغاء هذه المحطة',
    modalCancelStopDesc: 'إلغاء',
    modalRemoveStopTitle: 'إزالة هذه المحطة',
    modalRemoveStopDesc: 'إزالة',
    modalCancel: 'إلغاء',
    modalRemove: 'إزالة',
    modalClose: 'إغلاق',
    modalSave: 'حفظ',
    labelStart: 'البدء',
    labelEnd: 'النهاية',
    dataNotLoaded: 'البيانات لم يتم تحميلها',
    generatingBL: 'جاري توليد وثيقة التسليم...',
    blDownloaded: 'تم تحميل وثيقة التسليم',
    blGenerationError: 'خطأ في توليد وثيقة التسليم',
    backorderCreated: 'تم إنشاء الطلب الإضافي',
    backorderError: 'خطأ في الطلب الإضافي',
    stopCancelled: 'تم إلغاء المحطة',
    stopCancelError: 'فشل إلغاء المحطة',
    stopRemoved: 'تم إزالة المحطة',
    stopRemoveError: 'فشل إزالة المحطة',
    windowUpdated: 'تم تحديث نافذة التسليم',
    updateError: 'فشل التحديث',
    overlapWarning: '⚠️ هذه النافذة تتداخل مع محطات أخرى في الرحلة.',
    routeNotFound: 'الرحلة غير موجودة.',
    backToRoutes: 'العودة إلى الرحلات',
    by: 'بواسطة',
    tabMap: 'الخريطة',
    tabStops: 'المحطات',
    dispatchActionRecorded: 'تم تسجيل إجراء التوزيع.',
    deliveryReplanned: 'تمت إعادة جدولة الشحنة لمحاولة إعادة توصيل.',
    deliveryReassigned: 'تم إعادة تعيين الشحنة إلى سائق آخر.',
    reason: 'السبب',
    reasonPlaceholder: 'اشرح السبب في إزالتك لهذا الإيقاف...',
    refERP: 'ERP',
    refDelivery: 'شحنة',
    unitKg: 'كجم',
    unitMin: 'دقيقة',
    back: 'عودة',
    breadcrumbDetail: 'التفاصيل',
    optimized: 'محسنة',
    refresh: 'تحديث',
    labelDriver: 'السائق',
    notAssigned: 'غير معين',
    labelProgress: 'التقدم',
    failed: 'فشل',
    stops: 'محطات',
    labelCumulativeDelay: 'التأخير التراكمي',
    departure: 'المغادرة',
    labelPunctuality: 'الالتزام بالمواعيد',
    withinWindow: 'ضمن النافذة',
    labelDistance: 'المسافة',
    labelVehicle: 'المركبة',
    labelTotalLoad: 'الحمل الإجمالي',
    capacity: 'السعة',
    buttonRemoveRoute: 'إزالة من الرحلة',
    tooltipRemoveStop: 'إزالة هذه المحطة من الرحلة — ستعود الشحنة إلى مجموعة غير مجدولة لإعادة التوزيع. سيتم إعلام السائق فوراً.',
    tooltipEditWindow: 'تعديل نافذة التسليم لهذا الإيقاف. متاح فقط للرحلات المؤكدة مع إيقافات معلقة أو مجدولة.',
  },

  // ── صفحة الاستيراد ────────────────────────────────────────────────────
  importPage: {
    // Page structure
    pageSubtitle: 'تدفق البيانات',
    pageTitle: 'استيراد',
    pageTitleBrand: 'ERP',

    // Mobile tabs
    tabFilters: 'المرشحات',
    tabOrders: 'الطلبات',

    // Search & sync
    searchPlaceholder: 'المرجع، العميل...',
    syncButton: 'مزامنة ERP',

    // Filter pills / Tabs
    pillAll: 'جميع الطلبات',
    pillReady: 'جاهز للاستيراد',
    pillDone: 'تم استيراده بالفعل',

    // Table headers
    headerReference: 'مرجع ERP',
    headerCustomer: 'العميل',
    headerDestination: 'الوجهة',
    headerAmount: 'المبلغ',
    headerStatus: 'الحالة',
    headerActions: 'الإجراءات',

    // Table content
    emptyState: 'لا توجد طلبات معلقة',
    statusSynced: 'تمت المزامنة',
    statusReady: 'جاهز للاستيراد',
    tooltipDetails: 'التفاصيل',
    buttonView: 'عرض',
    buttonConfirm: 'تأكيد',
    buttonImport: 'استيراد',

    // Bulk actions
    selectedMessage: 'طلب{plural} محدد{plural}',
    deselect: 'إلغاء التحديد',
    bulkImport: 'استيراد ({count})',

    // Pagination
    showing: 'عرض {showing} من {total} طلبات',

    // Preview drawer
    drawerTitle: 'التحقق من الاستيراد',
    loadingError: 'فشل التحميل',

    // Preview section labels
    previewRefERP: 'مرجع ERP',
    previewCustomer: 'العميل النهائي',
    previewLocation: 'موقع التسليم',

    // Metrics
    metricsArticles: 'العناصر',
    metricsWeight: 'الوزن',
    metricsTotal: 'الإجمالي',

    // Items list
    itemsListTitle: 'محتويات الطلب',
    itemsColArticle: 'العنصر',
    itemsColQty: 'الكمية',
    itemsColPrice: 'السعر',

    // Warnings
    backorderWarning: 'تم اكتشاف طلب متأخر (معرف: {backorderId})',

    // Action buttons
    buttonViewDelivery: 'عرض التسليم',
    buttonImportFlow: 'استيراد إلى التدفق',

    // Last sync
    lastSync: 'آخر مزامنة:',
  },

  // ── صفحة منشئ المسارات ────────────────────────────────────────────────
  routeBuilderPage: {
    // Loading
    loadingMap: 'جاري تحميل الخريطة...',

    // KPI labels
    kpiScheduled: 'مجدولة',
    kpiUnscheduled: 'غير مجدولة',
    kpiTotal: 'الإجمالي',
    kpiRoutes: 'المسارات',

    // Date filter
    buttonToday: 'اليوم',
    tooltipTodayRoutes: 'عرض مسارات اليوم',

    // Toggle buttons
    buttonExpand: 'توسيع',
    buttonCollapse: 'طي',

    // Sidebar (RouteSidebar)
    noRoutesForDate: 'لا توجد مسارات في هذا التاريخ',
    lockRoute: 'قفل المسار',
    unlockRoute: 'فتح قفل المسار',
    selectForBatchOptimize: 'تحديد لتحسين العمليات المجمعة',
    selectAll: 'تحديد الكل',
    deselectAll: 'إلغاء تحديد الكل',
    newRoute: 'مسار جديد',

    // Modals
    createRouteTitle: 'إنشاء مسار',
    settingsTitle: 'إعدادات المسار',
    settingsSubtitlePrefix: 'إعدادات',
    settingsWarning: 'سيتم إعادة حساب الجداول الزمنية بعد الحفظ.',
    routeNameReadOnly: 'الاسم (للقراءة فقط)',
    deleteConfirmTitle: 'إجراء لا يمكن عكسه',
    deleteDraftModalTitle: 'حذف المسودة؟',
    deleteDraftModalBody: 'هذا الإجراء لا يمكن التراجع عنه. جميع الطلبات في هذا الموقف ستصبح غير مخططة.',
    validateTitle: 'التحقق من صحة المسار',
    routeNameLabel: 'اسم المسار',
    operationDateLabel: 'تاريخ العملية',
    assignedDriverLabel: 'السائق المعين',
    vehicleLabel: 'المركبة',
    departureDepotLabel: 'مستودع المغادرة',
    logisticsDepotLabel: 'مستودع الخدمات اللوجستية',
    selectDepot: 'اختر المستودع…',
    selectPlaceholder: 'اختر…',
    selectRoutePlaceholder: 'اختر مسارًا…',
    deleteRouteButton: 'حذف المسار',
    vehicleBusy: ' — مشغول',

    // Search & filters
    searchClientRefId: 'ابحث عن العميل والمعرف والمرجع…',
    searchPlaceholder: 'بحث…',
    noResults: 'لا توجد نتائج',
    clearSelection: 'مسح التحديد',
    selectedStops: '{count} موقف{plural} مختار{plural}',

    // Orders table
    emptyOrdersState: 'لا توجد طلبات للاستيراد',

    // ValidationModal & Modals general
    finalReviewSubtitle: 'مراجعة نهائية',
    chronoErrors: 'أخطاء في الجدول الزمني',
    readyForValidation: 'جاهز للتحقق',
    noStopsDetected: 'لم يتم العثور على أي مواقف.',
    headerIndex: '#',
    headerClientDestination: 'العميل والوجهة',
    headerStart: 'البداية',
    headerEnd: 'النهاية',
    headerStatus: 'الحالة',

    // StopsPanel / General Panel
    selectRoutePromptTitle: 'اختر مسارًا',
    selectRoutePromptDesc: 'اختر مسارًا من القائمة الجانبية لإدارة مواقفه.',
    validatedBadge: 'تم التحقق',
    draftBadge: 'مسودة',
    stopsCountLabelSingular: '{count} موقف',
    stopsCountLabelPlural: '{count} مواقف',
    payloadLabel: 'الحمولة',
    overloadLabel: 'حمولة زائدة: +{amount} كجم',
    chronoConflictWarning: 'تضارب في الجدول الزمني للفترات الزمنية.',
    finalizeValidateButton: 'إنهاء وتحقق',

    // OrdersTable
    headerIdErp: 'معرف ERP',
    headerIdAsm: 'معرف ASM',
    headerClient: 'العميل',
    headerArticles: 'السلع',
    headerWeight: 'الحمولة',
    headerDate: 'التاريخ',
    articlesCountSingular: '{count} سلعة',
    articlesCountPlural: '{count} سلع',
    assignButton: 'تعيين ({count})',

    // RoutesTable
    headerRoute: 'المسار',
    headerDriver: 'السائق',
    headerVehicle: 'المركبة',
    headerStops: 'المواقف',
    headerDistance: 'المسافة',
    headerDuration: 'المدة',

    // TimelineGantt
    emptyTimelineGantt: 'لا توجد مسارات لعرضها في الجدول الزمني',
    noDriver: 'بدون سائق',

    // ActionBar
    actionBarSettingsTooltip: 'إعدادات المسار',
    actionBarSettingsLabel: 'الإعدادات',
    optimizingProgress: 'جاري التحسين...',
    optimize: 'تحسين',
    validate: 'تحقق',
    savingProgress: 'جاري الحفظ...',
    save: 'حفظ',

    // StopsList
    dragDropPrompt: 'اسحب وأسقط الطلبات لبدء التخطيط.',
    deselectButton: 'إلغاء التحديد',
    removeButton: 'إزالة ({count})',

    // OptimizePreview
    optimizePreviewTitle: 'معاينة التحسين',
    gainLabel: 'الفائدة',
    suggestedRouteLabel: 'المسار المقترح من OSRM',
    applyButton: 'تطبيق',
    departureTimeLabel: 'وقت المغادرة',
    durationLabel: 'المدة',
    noSuggestionLabel: 'لا توجد اقتراحات لعرضها',
    sincePreviousStopLabel: 'من الموقف السابق',

    // Map & Overlays
    mapLayerStreet: 'الخريطة',
    mapLayerHot: 'حراري',
    mapLayerSatellite: 'قمر صناعي',
    layerDepots: 'المستودعات',
    layerTraces: 'المسارات',
    activeZoneLabel: 'المنطقة النشطة',
    activeZoneNone: 'لا توجد منطقة نشطة',
    tabOrders: 'الطلبات',
    tabRoutes: 'التوريدات',
    tabTimeline: 'الجدول الزمني',
    dragCardOrder: 'طلب',
    dragCardOrders: '{count} طلبات',
    dragCardArticles: 'المحتويات',
    dragCardOtherArticles: '+{count} عناصر أخرى',
    dragCardArticleCount: '{count} عنصر{plural}',
    dragCardDropPrompt: 'أفلته في مسار',
    dragCardScrollPrompt: 'استخدم عجلة الفأرة للتصفح',
    mapSearchPlaceholder: 'البحث عن مكان...',
    pageTitle: 'تخطيط الرحلات',
    breadcrumbDashboard: 'لوحة التحكم',
    selectedRoutes: '{count} محددة',
    eligibleRoutes: '({count} مؤهلة)',
    lockOrLessStopsIgnored: 'يتم تجاهل الرحلات المغلقة أو التي تحتوي على أقل من موقفين.',
    batchOptimizeButton: 'تحسين',
    clearSelectionTooltip: 'مسح التحديد',

    // رسائل الإشعارات
    toastLoadFailed: 'فشل تحميل البيانات',
    toastRouteNameRequired: 'اسم الرحلة مطلوب',
    toastDateRequired: 'تاريخ الرحلة مطلوب',
    toastDriverRequired: 'يرجى اختيار سائق',
    toastDepotRequired: 'يرجى اختيار مستودع',
    toastRouteCreated: 'تم إنشاء الرحلة',
    toastOrderNotPinned: 'الطلب {order} غير مثبَّت — ثبِّته على الخريطة أولاً قبل التعيين.',
    toastSelectRouteFirst: 'اختر رحلة أولاً',
    toastNoOrderSelected: 'لم يتم تحديد أي طلب',
    toastOrderAssigned: 'تم تعيين الطلب {order}',
    toastOrdersAssigned: 'تم تعيين {count} طلبات',
    toastOsrmSuggested: 'تم توليد اقتراح OSRM: الترتيب والمسار ووقت الوصول جاهزة للمعاينة.',
    toastOsrmFailed: 'فشل تحسين OSRM',
    toastRouteLocked: 'تم قفل الرحلة',
    toastRouteUnlocked: 'تم فتح قفل الرحلة',
    toastLockFailed: 'فشل القفل',
    toastUnlockFailed: 'فشل فتح القفل',
    toastNoEligibleRoutes: 'لا توجد رحلات مؤهلة (مقفلة أو أقل من موقفين).',
    toastBatchOptimizeOk: 'تم تحسين {count} رحلة.',
    toastBatchOptimizePartial: '{ok} تم تحسينها، {ko} فشلت.',
    toastBatchOptimizeFailed: 'فشل التحسين الجماعي.',
    toastOsrmApplied: 'تم تطبيق تحسين OSRM ونوافذ الوقت',
    toastStopRemoved: 'تم إزالة الموقف من الرحلة',
    toastStopsRemoved: 'تم إزالة {count} مواقف',
    toastTimeWindowRequired: 'يجب أن تحتوي جميع المواقف على نافذة زمنية (بداية ونهاية)',
    toastTimeWindowEndBeforeStart: 'يجب أن يكون وقت الانتهاء بعد وقت البداية',
    toastTimeWindowChronoError: 'خطأ في الترتيب الزمني: يجب أن يبدأ كل موقف بعد انتهاء السابق',
    toastTimeWindowsSaved: 'تم حفظ النوافذ الزمنية',
    toastSettingsDateRequired: 'التاريخ مطلوب',
    toastSettingsSaved: 'تم حفظ إعدادات الرحلة',
    toastItineraryClear: 'تم حذف المسار',
    toastRouteValidated: 'تم التحقق من الرحلة',
    toastRouteNotFound: 'الرحلة غير موجودة',
    toastReorgFailed: 'فشل إعادة الترتيب، تم التراجع',
    toastStopTransferred: 'تم نقل الموقف',
    toastTransferFailed: 'فشل النقل، تم التراجع',
  },

  // ── صفحة جدول الرحلات ────────────────────────────────────────────────
  routesTablePage: {
    // Page structure
    pageSubtitle: 'تحسين الرحلات',
    pageTitle: 'تتبع',
    pageTitleBrand: 'الرحلات',

    // Mobile tabs
    tabFilters: 'الفلاتر',
    tabList: 'الرحلات',

    // New route button
    newRouteButton: 'رحلة جديدة',
    searchPlaceholder: 'بحث عن العميل...',

    // Status filters
    filterStatusLabel: 'فلاتر الحالة',
    filterAllRoutes: 'جميع الرحلات',
    filterInProgress: 'قيد التنفيذ',
    filterReady: 'جاهزة للانطلاق',
    filterClosed: 'السجل / المغلقة',

    // Advanced filters
    advancedFiltersLabel: 'التكوين المتقدم',
    filterPeriodLabel: 'الفترة',
    filterAllDates: 'جميع التواريخ',
    filterToday: 'اليوم',
    filterYesterday: 'أمس',
    filterWeek: 'آخر 7 أيام',
    filterDriverLabel: 'السائق',
    filterAllDrivers: 'جميع السائقين',
    filterVehicleLabel: 'المركبة',
    filterAllVehicles: 'جميع المركبات',
    filterDepotLabel: 'الموقع / المستودع',
    filterAllDepots: 'جميع المواقع',
    filterZoneLabel: 'المنطقة الجغرافية',
    filterAllZones: 'جميع المناطق',
    clearFiltersButton: 'مسح الفلاتر',
    refreshButton: 'تحديث السجل',

    // Table headers
    headerRoute: 'الرحلة',
    headerScheduled: 'مجدولة',
    headerZoneDepot: 'المنطقة · المستودع',
    headerDriver: 'السائق',
    headerStops: 'المحطات',
    headerStatus: 'الحالة',
    headerActions: 'الإجراءات',

    // Detail table headers
    headerIndex: '#',
    headerClient: 'العميل',
    headerAddress: 'العنوان',
    headerERP: 'مرجع ERP',
    headerWeight: 'الوزن',
    headerDeliveryStatus: 'الحالة',

    // Route details
    noActiveStops: 'لا توجد محطات نشطة',
    movementLabel: 'حركة',
    notAssigned: 'غير معين',
    addressNotProvided: 'العنوان غير متوفر',

    // Close route tooltip
    closeRouteTooltip: 'إغلاق',

    // Close route modal
    closeRouteTitle: 'إغلاق الرحلة',
    closeRouteDescription: 'الإغلاق النهائي لـ "{routeName}". سيتم أرشفة جميع التسليمات غير المكتملة.',
    closeRouteConfirm: 'تأكيد الإغلاق',
    closeRouteCancel: 'إلغاء',

    // Error messages
    loadError: 'فشل تحميل الرحلات',
  },

  // ── صفحة السائقين ────────────────────────────────────────────────────
  driversPage: {
    pageSubtitle: 'الموارد البشرية',
    pageTitle: 'إدارة',
    pageTitleBrand: 'السائقين',
    tabFilters: 'الفلاتر',
    tabList: 'السائقين',
    newDriverButton: 'سائق جديد',
    importCsvButton: 'استيراد CSV',
    searchPlaceholder: 'الاسم أو الهاتف...',
    refreshButton: 'تحديث',
    fleetStatusLabel: 'حالة الأسطول',
    totalDriversLabel: 'العدد الكلي',
    onMissionLabel: 'في مهمة',
    operationalStatusLabel: 'الحالات التشغيلية',
    allFleet: 'الأسطول بأكمله',
    busy: 'في مهمة',
    available: 'متاح',
    tableHeaderDriver: 'السائق',
    tableHeaderContact: 'جهة الاتصال',
    tableHeaderActivity: 'النشاط',
    tableHeaderActions: 'الإجراءات',
    displayedCount: '{count} سائق(قين) معروضين',
    onMissionStatus: 'في مهمة',
    availableStatus: 'متاح',
    activeDelivery: 'قيد التنفيذ',
    onRoute: 'في الرحلة',
    free: 'حر',
    deactivateTooltip: 'تعطيل',
    activateTooltip: 'تفعيل',
    editModalTitle: 'تحرير السائق',
    newDriverModalTitle: 'سائق جديد',
    loadingFleet: 'جاري تحميل الأسطول...',
    // Modal form fields
    formInstructionLabel: 'أدخل معلومات السائق',
    nameLabel: 'الاسم الكامل',
    namePlaceholder: 'الاسم الأول واللقب',
    phoneLabel: 'رقم الهاتف',
    phonePlaceholder: '+216 XX XXX XXX',
    emailLabel: 'عنوان البريد الإلكتروني',
    emailPlaceholder: 'driver@company.com',
    invitationEmailHelp: 'سيتم إرسال بريد دعوة إلى هذا العنوان',
    saveButton: 'حفظ',
    createButton: 'إنشاء',
    cancelButton: 'إلغاء',
    // Additional modal & tooltip text
    modifyTooltip: 'تعديل',
    deleteTooltip: 'إلغاء التفعيل / حذف',
    profileModalTitle: 'ملف السائق الشخصي',
    modifyButton: 'تعديل',
    closeButton: 'إغلاق',
    activityLogTitle: 'سجل الأنشطة الأخيرة',
    noActivityDetected: 'لم يتم اكتشاف أي نشاط',
    deleteDriverTitle: 'حذف هذا السائق؟',
    deleteDriverDescription: 'سيتم حذف ملف السائق {driverName} من النظام. هذا الإجراء لا يمكن التراجع عنه.',
    deleteButton: 'حذف',
    statusActive: 'نشط',
    statusInactive: 'غير نشط',
    statusPending: 'قيد الانتظار',
    statusSuspended: 'موقوف',
    pendingStatusLockedTooltip: 'يجب على السائق إكمال التسجيل أولاً قبل أي إجراء.',
    pendingEditLockedTooltip: 'التعديل غير متاح حتى يفعّل السائق حسابه.',
    cancelInviteTitle: 'إلغاء الدعوة؟',
    cancelInviteDescription: 'سيتم إلغاء دعوة {driverName} وحذف السائق. هذا الإجراء لا يمكن التراجع عنه.',
    cancelInviteButton: 'إلغاء الدعوة',
    cancelInviteReasonLabel: 'السبب (اختياري)',
    cancelInviteReasonPlaceholder: 'مثال: تكرار، خطأ في الإدخال…',
    cancelInviteSuccess: 'تم إلغاء الدعوة',
    suspendDriverTitle: 'إيقاف هذا السائق؟',
    suspendDriverDescription: 'سيتم تعطيل حساب {driverName}. لن يعود قادراً على تسجيل الدخول، لكن سيتم الاحتفاظ بسجله. إجراء قابل للعكس.',
    suspendDriverButton: 'إيقاف',
    suspendReasonLabel: 'السبب (اختياري)',
    suspendReasonPlaceholder: 'مثال: غياب طويل، تحقيق داخلي…',
    suspendSuccess: 'تم إيقاف السائق',
    resendInviteButton: 'إعادة إرسال الدعوة',
    resendInviteTooltip: 'إرسال رمز تفعيل جديد لهذا السائق',
    resendInviteSuccess: 'تمت إعادة إرسال الدعوة',
    resendInProgress: 'جارٍ الإرسال…',
    resendCooldown: 'إعادة المحاولة بعد {seconds} ثانية',
    resendRateLimited: 'محاولات كثيرة. يرجى الانتظار {seconds} ثانية.',
    invitationExpired: 'انتهت صلاحية الدعوة. أعد إرسال رمز جديد للمتابعة.',
    invitationExpiresIn: 'تنتهي خلال {time}',
    invitationExpiredChip: 'منتهية',
  },

  // ── صفحة المركبات ─────────────────────────────────────────────────────
  vehiclesPage: {
    // Page header & navigation
    pageSubtitle: 'إدارة الأسطول',
    pageTitle: 'إدارة',
    pageTitleBrand: 'المركبات',
    tabFilters: 'الفلاتر',
    tabList: 'المركبات',
    // Vehicle types
    vehicleTypeHeavy: 'شاحنة ثقيلة',
    vehicleTypeVan: 'فان',
    vehicleTypeCar: 'تجاري',
    vehicleTypeMoto: 'دراجة نارية / سكوتر',
    // Status labels
    statusEngaged: 'مشغول',
    statusAvailable: 'متاح',
    statusOutOfService: 'خارج الخدمة',
    // Sidebar
    newVehicleButton: 'إضافة مورد',
    searchPlaceholder: 'بحث تقني...',
    operationalStatusLabel: 'حالة التشغيل',
    fleetTotal: 'إجمالي الأسطول',
    operational: 'جاهز للعمل',
    engaged: 'مشغول',
    maintenance: 'صيانة',
    logisticsCapacityLabel: 'سعة اللوجستيات',
    totalTonnageLabel: 'إجمالي الطاقة',
    fleetOccupancyLabel: 'استخدام الأسطول',
    // Grid & toolbar
    displayedCount: 'عرض {count} وحدة تقنية',
    noVehiclesFound: 'لم يتم العثور على مركبات',
    // Card labels
    assignedDriver: 'السائق المعين',
    unassigned: '— غير معين',
    capacityLabel: 'السعة',
    volumeLabel: 'الحجم',
    // Modal - Create/Edit
    modalTitle: 'التكوين التقني',
    editSubtitle: 'تعديل الأصل',
    createSubtitle: 'مورد استراتيجي جديد',
    modalImageLabel: 'صورة الأصل',
    imageUploadButton: 'تحميل',
    imageChangeButton: 'تغيير',
    cancelButton: 'إلغاء',
    saveButton: 'حفظ الشهادة',
    createButton: 'إنشاء الأصل',
    // Form fields
    makeLabel: 'الماركة',
    modelLabel: 'الموديل',
    plateLabel: 'اللوحة',
    typeLabel: 'النوع',
    capacityKgLabel: 'السعة (كجم)',
    volumeM3Label: 'الحجم (متر مكعب)',
    yearLabel: 'السنة',
    statusLabel: 'الحالة',
    operationalStatus: 'جاهز للعمل',
    // Delete modal
    deleteTitle: 'حذف الأصل',
    deleteDescription: 'هل أنت متأكد من أنك تريد إزالة المركبة {vehicleName} من الأسطول بشكل دائم؟',
    deleteButton: 'تأكيد الإزالة',
    deleteCancel: 'إلغاء',
  },

  // ── صفحة المستودعات ──────────────────────────────────────────────────
  depotsPage: {
    pageSubtitle: 'شبكة اللوجستيات',
    pageTitle: 'إدارة',
    pageTitleBrand: 'المستودعات',
    depotsCount: '{count} مراكز عمليات مرجعية',
    newHubButton: 'مركز جديد',
    mapInitializing: 'جاري تهيئة الخريطة...',
    mapLoading: 'جاري تحميل الشبكة...',
    mapTitle: 'الشبكة الإقليمية',
    totalHubs: 'إجمالي المراكز',
    operationalHubs: 'جاهزة للعمل',
    registryTitle: 'سجل المواقع',
    noDepots: 'لم يتم العثور على مراكز',
    headerDesignation: 'تسمية المركز',
    headerLocation: 'الموقع الفعلي',
    headerCoordinates: 'الإحداثيات',
    headerStatus: 'الحالة',
    statusOperational: 'جاهز للعمل',
    statusInactive: 'غير نشط',
    modalTitle: 'تكوين المركز',
    modalSubtitle: 'شهادة الخطة التقنية',
    cancelButton: 'إلغاء',
    saveButton: 'حفظ المركز',
    updateButton: 'تحديث المركز',
    nameLabel: 'اسم المركز',
    nameExample: 'HUB_ALPHA_01',
    addressLabel: 'العنوان',
    addressPlaceholder: 'الموقع الفعلي...',
    latitudeLabel: 'خط العرض',
    longitudeLabel: 'خط الطول',
    geometricAdjustment: 'التعديل الهندسي',
    operationalAvailability: 'التوفر التشغيلي',
    deleteTitle: 'أرشفة المركز',
    deleteDescription: 'هل تؤكد تعطيل {hubName}؟ ستتم إعادة حساب الشبكة الإقليمية.',
    deleteButton: 'أرشفة المركز',
    deleteCancel: 'إلغاء',
  },

  // ── صفحة الأداء ────────────────────────────────────────────────────────
  performancePage: {
    pageSubtitle: 'تدفق الأداء',
    pageTitle: 'تحليل',
    pageTitleBrand: 'اللوجستيات',
    operationalDashboard: 'لوحة تحكم العمليات',
    periodLabel: 'الفترة:',
    periodDay: 'اليوم',
    periodWeek: 'أسبوع',
    periodMonth: 'شهر',
    periodAll: 'الكل',
    lastUpdated: 'آخر تحديث:',
    operationalVolume: 'الحجم التشغيلي',
    completionRate: 'معدل الإنجاز',
    deliveryPerformance: 'أداء التسليم',
    avgDelay: 'التأخير المتوسط',
    basedOnTarget: 'بناءً على الوقت المستهدف',
    lifeCycle: 'دورة الحياة',
    assignmentToDestination: 'التعيين → الوجهة',
    volumeCurve: 'منحنى الحجم',
    lastSevenDays: 'النشاط في آخر 7 أيام',
    temporalFragmentation: 'التجزئة الزمنية',
    efficiencyByPhase: 'الكفاءة حسب المرحلة (دقيقة)',
    driverResponse: 'استجابة السائق',
    assignmentToPickup: 'التعيين → الاستلام',
    depotLoading: 'تحميل المستودع',
    pickupToTransit: 'الاستلام → الإرسال',
    effectiveTransit: 'النقل الفعال',
    transitToCompletion: 'النقل → الوجهة',
    totalCycleIndex: 'إجمالي مؤشر الدورة',
    densityByZone: 'الكثافة حسب المنطقة',
    driverPerformanceRanking: 'ترتيب أداء السائقين',
    actor: 'الممثل',
    volume: 'الحجم',
    success: 'نجاح',
    delay: 'تأخير',
    downloadPdfTooltip: 'تنزيل تقرير PDF',
    notAssigned: 'غير معين',
    successSlash: 'نجح',
    failureSlash: 'فشل',
    errorMissingDriverId: 'معرّف السائق مفقود',
    successReportDownloaded: 'تم تنزيل تقرير {name}',
    errorReportGeneration: 'خطأ في إنشاء التقرير',
  },

  // ── صفحة المناطق ─────────────────────────────────────────────────────
  zonesPage: {
    pageSubtitle: 'شبكة اللوجستيات',
    pageTitle: 'إدارة',
    pageTitleBrand: 'المناطق',
    tabZones: 'مناطق',
    tabMap: 'خريطة',
    newZoneButton: 'قطاع جديد',
    syncButton: 'مزامنة',
    syncTooltip: 'مزامنة جميع التسليمات مع المناطق الحالية',
    meshIndicators: 'مؤشرات الشبكة',
    activeSectors: 'القطاعات النشطة',
    postalPoints: 'نقاط البريد',
    operationalHelp: 'المساعدة التشغيلية',
    helpText: 'حدد محيطاتك بتجميع الرموز البريدية. سيستخدم الإرسال التلقائي هذه المناطق للتحسين.',
    registryTitle: 'سجل القطاعات التشغيلية',
    zonesConfigured: '{count} كيانات مكونة',
    noZones: 'لم يتم تسجيل أي مناطق',
    headerDesignation: 'القطاع / التسمية',
    headerCoverage: 'التغطية البريدية',
    headerDensity: 'الكثافة',
    headerStatus: 'الحالة',
    statusOperational: 'جاهز للعمل',
    statusInactive: 'غير نشط',
    extraCodes: '+{count}',
    mapInitializing: 'جاري تهيئة شبكة ASM...',
    modalTitle: 'تكوين الإقليم',
    modalSubtitle: 'شهادة التقسيم التقني',
    cancelButton: 'إلغاء',
    saveButton: 'حفظ',
    sectorNameLabel: 'اسم القطاع',
    sectorNamePlaceholder: 'مثال: تونس الشمالية',
    zoneColorLabel: 'لون المنطقة',
    operationalNotesLabel: 'الملاحظات التشغيلية',
    operationalNotesPlaceholder: 'تفاصيل حول المنطقة...',
    dispatchAvailability: 'توفر الإرسال',
    postalCoverageLabel: 'التغطية البريدية',
    postalCodePlaceholder: 'الرمز البريدي مثال 1000',
    conflictsDetected: 'تم اكتشاف تضاربات',
    conflictWarning: 'هذه الرموز مخصصة بالفعل لمناطق أخرى.',
    removeConflicts: 'إزالة التضاربات',
    activePostalPoints: 'نقاط بريدية نشطة',
    deleteTitle: 'أرشفة المنطقة',
    deleteDescription: 'هل تؤكد تعطيل {zoneName}؟ ستتم إعادة حساب الشبكة الإقليمية.',
    deleteButton: 'أرشفة المنطقة',
    deleteCancel: 'إلغاء',
  },

  // ── صفحة سجل التدقيق ──────────────────────────────────────────────────
  auditLogsPage: {
    pageSubtitle: 'أمان النظام',
    pageTitle: 'سجل',
    pageTitleBrand: 'التدقيق',
    eventsRecorded: 'الأحداث المسجلة',
    actionLabel: 'الإجراء',
    actionPlaceholder: 'مثال DELETE_ROUTE',
    actorLabel: 'الممثل',
    actorPlaceholder: 'الاسم أو البريد الإلكتروني',
    roleLabel: 'كيان الدور',
    allRoles: 'جميع الأدوار',
    fromLabel: 'من',
    toLabel: 'إلى',
    resetButton: 'إعادة تعيين',
    timestampHeader: 'الطابع الزمني',
    actorHeader: 'الممثل',
    roleHeader: 'الدور',
    actionHeader: 'طبيعة الإجراء',
    resourceHeader: 'المورد المستهدف',
    ipHeader: 'عنوان IP',
    loadingLogs: 'جاري تحميل السجلات...',
    noLogs: 'لم يتم العثور على سجلات',
    eventId: 'معرّف الحدث',
    engineCategory: 'فئة المحرك',
    payloadDetails: 'تفاصيل الحمولة',
    noTechnicalDetails: 'لا توجد تفاصيل تقنية مسجلة.',
    // Timeline date grouping
    dateToday: 'اليوم',
    dateYesterday: 'أمس',
    dateEarlierWeek: 'في وقت سابق من هذا الأسبوع',
    dateOlder: 'أقدم',
    // Timeline metadata
    byActor: 'بواسطة',
    fullId: 'كامل:',
    // Action categories
    actionFluxRoute: 'تدفق المسار',
    actionExecution: 'التنفيذ',
    actionFleetManagement: 'إدارة الأسطول',
    actionAssignment: 'التعيين',
    actionMeshing: 'الشبكة',
    actionMobility: 'الحركة',
    actionIntervention: 'التدخل',
    actionSystem: 'النظام',
    actionErpSync: 'مزامنة ERP',
    actionSystemAudit: 'تدقيق النظام',
    actionDriverInvited: 'تمت دعوة السائق',
    actionDriverActivated: 'تم تفعيل السائق',
    actionDriverSuspended: 'تم إيقاف السائق',
    actionDriverInviteResent: 'أُعيد إرسال الدعوة',
    actionDriverInviteCancelled: 'تم إلغاء الدعوة',
    actionDriverUpdated: 'تم تحديث السائق',
    actionDriverPasswordReset: 'إعادة تعيين كلمة المرور',
    actionDriverBulkImported: 'استيراد جماعي',
  },

  // ── صفحة الإعدادات ────────────────────────────────────────────────────
  settingsPage: {
    platformNexus: 'منصة ASM Track',
    pageTitle: 'الإعدادات',
    pageTitleBrand: 'العامة',
    generalConfig: 'الإعدادات العامة',
    slaParameters: 'حدود SLA',
    identitiesAccess: 'الهويات والوصول',
    tabSections: 'الأقسام',
    tabParameters: 'المعاملات',
    systemAdmin: 'إدارة النظام',
    readOnlyMode: 'وضع القراءة فقط',
    coreService: 'الخدمة الأساسية',
    coreServiceDesc: 'إعدادات المثيل الأساسية.',
    systemNotifications: 'إخطارات النظام',
    systemNotificationsDesc: 'تفعيل التنبيهات البصرية للتأخيرات الحرجة.',
    autoArchiving: 'الأرشفة التلقائية',
    autoArchivingDesc: 'نقل المسارات المنتهية إلى السجل بعد 24 ساعة.',
    companyBranding: 'معلومات الشركة',
    instanceName: 'اسم المثيل',
    supportContact: 'جهة الاتصال للدعم',
    companyAddress: 'عنوان الشركة',
    primaryColor: 'اللون الأساسي',
    slaagreement: 'اتفاقية مستوى الخدمة (SLA)',
    slaDesc: 'حدود زمنية لحساب الامتثال التشغيلي.',
    waitingTime: 'وقت الانتظار (Waiting)',
    waitingTimeDesc: 'من لحظة إنشاء الطلب حتى تعيينه لسائق',
    assignmentDelay: 'تأخير بدء المسار (Assign)',
    assignmentDelayDesc: 'من بدء المسار المخطط أو الفعلي حتى سحب الحزمة من المستودع',
    transitDelay: 'تأخير المغادرة (Pickup)',
    transitDelayDesc: 'من سحب الحزمة حتى مغادرة المستودع الفعلية (بدء الجولة)',
    accessControl: 'التحكم في الوصول (IAM)',
    newUser: 'مستخدم جديد',
    actor: 'الممثل',
    authorization: 'التفويض',
    creationDate: 'تاريخ الإنشاء',
    iamGovernance: 'حوكمة IAM',
    cancelButton: 'إلغاء',
    initializeAccess: 'تهيئة الوصول',
    fullName: 'الاسم الكامل',
    fullNameExample: 'مثال: Admin Alpha',
    loginEmail: 'بريد الدخول',
    loginEmailExample: 'admin@asmtrack.com',
    temporaryPassword: 'كلمة المرور المؤقتة',
    profilePrivileges: 'امتيازات الملف الشخصي',
    slaThresholdCert: 'شهادة حد SLA',
    setpointValue: 'قيمة نقطة التعيين (دقائق)',
    applyButton: 'تطبيق',
    // SLA Modal
    whatMeasure: 'ما الذي يقيسه هذا؟',
    currentThreshold: 'الحد الأدنى الحالي',
    setNewThreshold: 'تعيين حد أدنى جديد',
    slaBreachWarning: 'سيتم وضع علامة على أي عملية تسليم تتجاوز هذا الوقت كانتهاك SLA',
    recommendation: '💡 التوصية',
    waitingRec: 'النطاق الشائع: 10-30 دقيقة. الوقت من إنشاء الطلب إلى تعيين السائق.',
    assignmentRec: 'النطاق الشائع: 10-30 دقيقة. من بدء المسار إلى الاستلام من المستودع. مع الأخذ في الاعتبار تحضير السيارة والاختيار.',
    pickupRec: 'النطاق الشائع: 5-15 دقيقة. من الاستلام حتى المغادرة الفعلية. يسمح بالتحميل والفحوصات.',
    // Integration
    integrationConfig: 'تكامل نظام تخطيط الموارد (ERP)',
    erpProvider: 'مزود نظام تخطيط الموارد',
    erpUrl: 'رابط اتصال JSON-RPC',
    erpUrlDesc: 'مثل: http://odoo:8069/jsonrpc',
    erpDb: 'اسم قاعدة البيانات',
    erpUid: 'معرف المستخدم (UID)',
    erpPassword: 'كلمة المرور / مفتاح API',
    saveConfig: 'حفظ التكوين',
    testConnection: 'اختبار الاتصال',
    testSuccess: 'تم إنشاء اتصال ERP بنجاح.',
    testFailed: 'فشل اتصال ERP. تحقق من بيانات الاعتماد الخاصة بك.',
    noErpDesc: 'لم يتم تحديد مزود ERP. يجب إدخال البيانات يدوياً.',
    erpOdooDesc: 'اتصال مباشر بنظام Odoo عبر واجهة JSON-RPC للمزامنة التلقائية.',
    erpDuxDesc: 'اتصال بنظام Dux (قيد الدمج). سيتم دعم النظام بالكامل قريباً.',
  },

  // ── صفحة التسليمات ────────────────────────────────────────────────────
  deliveriesPage: {
    // Page structure
    pageSubtitle: 'شبكة اللوجستيات',
    pageTitle: 'تتبع',
    pageTitleBrand: 'التسليمات',

    // Filter section
    filterLabel: 'المرشحات',
    showFilters: 'عرض المرشحات',
    hideFilters: 'إخفاء المرشحات',
    searchPlaceholder: 'بحث سريع',
    filterByStatus: 'جميع الحالات',
    filterByDriver: 'جميع السائقين',
    filterByZone: 'جميع المناطق',
    filterClear: 'المرشحات',
    dateLabel: 'تاريخ التسليم',
    qualificationLabel: 'التصنيف',
    refreshButton: 'تحديث',
    tabList: 'قائمة',

    // Quick views
    totalFlow: 'جميع التسليمات',
    quickViewNeedsPinning: 'تحتاج إلى تحديد موقع',
    quickViewUnassigned: 'غير معينة',
    quickViewInTransit: 'قيد النقل',
    quickViewCompleted: 'مسلمة',
    quickViewFailed: 'فشلت',

    // Table display
    displayLabel: 'العرض:',
    entityDetected: 'كيانات تم اكتشافها',
    pageSize: '/ صفحة',

    // Table headers
    refHeader: 'المرجع',
    clientHeader: 'العميل',
    addressHeader: 'العنوان',
    driverHeader: 'السائق',
    zoneHeader: 'المنطقة',
    statusHeader: 'الحالة',
    actionsHeader: 'الإجراءات',

    // Table content
    unknownDriver: 'عنوان غير معروف',
    notAssigned: 'غير معين',
    outOfZone: 'المنطقة غير محددة',

    // Pagination
    pageLabel: 'صفحة',
    resultsLabel: 'نتائج',
    prevButton: '← السابق',
    nextButton: 'التالي →',

    // Pin/Location modal
    pinModalTitle: 'تحديد موقع التسليم',
    pinModalSearch: 'البحث عن عنوان...',
    pinModalConfirm: 'تم تأكيد الموقع',
    lockedGeocoding: 'الموقع مقفل — التسليم قيد التنفيذ',
    pinReverseGeocoding: 'جاري تحديد الموقع...',
    addressLocated: 'تم العثور على الموقع — انقر على الخريطة للتأكيد',
    analyzeInProgress: 'جاري التحليل...',
    addressTarget: 'الموقع المستهدف',
    addressPlaceholder: 'إدخال يدوي...',
    postalCodeLabel: 'رمز البريد',
    postalCodePlaceholder: '20XX',
    pinButtonConfirm: 'تأكيد الموقع',
    lockedDeliveryMessage: 'تسليم نشط. الموقع مقفل لضمان سلامة الرحلة المحسنة.',

    // Tooltips
    tooltipPinLocation: 'تأكيد موقع GPS على الخريطة',
    tooltipRepin: 'تعديل الموقع',
    tooltipCancel: 'إلغاء التسليم',
    tooltipDownloadBL: 'تحميل إيصال التسليم (PDF)',
    trackingLink: 'نسخ رابط التتبع العام',

    // Cancel modal
    cancelModalTitle: 'إلغاء التسليم',
    cancelModalDescription: 'هذا الإجراء لا رجعة فيه. سيتم إلغاء الطلب ومزامنته مع نظام ERP.',
    cancelModalLabel: 'سبب الإلغاء',
    cancelModalPlaceholder: 'اشرح سبب هذا الإلغاء...',
    cancelButtonConfirm: 'إلغاء نهائي',
    cancelButtonKeep: 'الاحتفاظ',

    // Success/Error messages
    deliveryCancelled: 'تم إلغاء التسليم',
    deliveryCreated: 'تم إنشاء التسليم',
    backorderCreated: 'تم إنشاء طلب إضافي',
    trackingCopied: 'تم نسخ رابط التتبع',

    // Error messages
    loadError: 'فشل تحميل التسليمات',
    backorderError: 'خطأ في إنشاء الطلب الإضافي',
    pinError: 'خطأ في حفظ الموقع',
    downloadError: 'خطأ في تحميل إيصال التسليم',

    // Page loading
    pageLoading: 'جاري تحميل التسليمات...',
  },

  // ── رسائل API والأخطاء ──────────────────────────────────────────────
  apiMessages: {
    successStopCancelled: 'تم إزالة الإيقاف من الرحلة',
    successStopRemoved: 'تم حذف الإيقاف',
    successWindowUpdated: 'تم تحديث نافذة التسليم',
    successWindowsSaved: 'تم حفظ نوافذ التسليم',
    successRouteValidated: 'تم تأكيد الرحلة',
    successRouteReassigned: 'تم إعادة تعيين الرحلة',
    successRouteClosed: 'تم إغلاق الرحلة',
    successRouteCancelled: 'تم إلغاء الرحلة',
    successBackorderCreated: 'تم إنشاء طلب إضافي',
    successDeliveryRescheduled: 'تمت إعادة جدولة الشحنة',
    successDeliveryReassigned: 'تمت إعادة تعيين الشحنة',
    successPositionConfirmed: 'تم تأكيد الموقع',
    successTrackingLinkCopied: 'تم نسخ رابط التتبع',
    successLogin: 'تم تسجيل الدخول بنجاح',
    errorDataNotLoaded: 'البيانات لم يتم تحميلها',
    infoBlGenerating: 'جاري توليد وثيقة التسليم...',
    successBlDownloaded: 'تم تحميل وثيقة التسليم بنجاح',
    errorBlGenerationFailed: 'خطأ في توليد وثيقة التسليم',
    successSlaUpdated: 'تم تحديث إعدادات SLA',
    successErpUpdated: 'تم تحديث إعدادات تكامل ERP',
    successUserCreated: 'تم إنشاء المستخدم بنجاح',
    successDeliveryCreated: 'تم إنشاء الشحنة بنجاح',
    errorDeliveryCreateFailed: 'فشل إنشاء الشحنة',
    errorDriverRoutesLoadFailed: 'تعذر تحميل مسارات السائق',
    errorNoteRequired: 'يرجى إضافة ملاحظة توضيحية',
    errorTimeWindowRequired: 'النافذة الزمنية مطلوبة لهذا التسليم',
    successReassignToDraft: 'تمت إضافة الشحنة (الشحنات) إلى مسودة المسار',
    successReassignToActive: 'تمت إعادة تعيين الشحنة (الشحنات) بنجاح',
    errorReassignPartialSuccess: 'تعذر نقل بعض الشحنات',
    errorReassignFailed: 'تعذر إعادة تعيين الشحنة',

    errorStopNotFound: 'الإيقاف غير موجود',
    errorStopInvalidStatus: 'الإيقاف في حالة غير صالحة',
    errorStopInTransit: 'لا يمكن إلغاء إيقاف أثناء التوصيل',
    errorStopCancellationNotAllowed: 'إلغاء الإيقاف مسموح فقط على الرحلات المؤكدة أو الجارية',
    errorStopDoesNotBelong: 'هذا الإيقاف لا ينتمي إلى هذه الرحلة',

    errorRouteNotFound: 'الرحلة غير موجودة',
    errorRouteInvalidStatus: 'العملية غير مسموحة لهذه حالة الرحلة',
    errorRouteValidationFailed: 'فشل تأكيد الرحلة',
    errorRouteReassignmentFailed: 'فشل إعادة تعيين الرحلة',
    errorRouteClosureFailed: 'فشل إغلاق الرحلة',
    errorRouteCancellationFailed: 'فشل إلغاء الرحلة',
    errorOnlyValidatedRoutes: 'فقط الرحلات المؤكدة أو الجارية يمكن تعديلها',

    errorDeliveryNotFound: 'الشحنة غير موجودة',
    errorDeliveryInvalidStatus: 'العملية غير مسموحة لهذه حالة الشحنة',
    errorBackorderCreationFailed: 'فشل إنشاء الطلب الإضافي',
    errorDeliveryReassignmentFailed: 'فشل إعادة تعيين الشحنة',

    errorWindowInvalid: 'نافذة تسليم غير صالحة',
    errorWindowOutOfBounds: 'نافذة التسليم خارج حدود الرحلة',
    errorWindowOverlap: 'نافذة التسليم متضاربة مع إيقاف آخر',
    errorWindowUpdateFailed: 'فشل تحديث نافذة التسليم',

    errorMissingGPS: 'بعض الإيقافات تفتقد إحداثيات GPS',
    errorMissingLocation: 'الموقع غير متوفر',
    errorCapacityExceeded: 'تم تجاوز سعة المركبة',
    errorVehicleAlreadyAssigned: 'المركبة المحددة مُعينة بالفعل',
    errorInvalidOrderSequence: 'تسلسل إيقافات غير صحيح',

    errorBadRequest: 'طلب غير صحيح',
    errorUnauthorized: 'أنت غير مصرح بإجراء هذه العملية',
    errorForbidden: 'تم منع الوصول',
    errorConflict: 'تضارب في البيانات - قد يكون الكائن قد تم تعديله',
    errorServerError: 'خطأ في الخادم - الرجاء المحاولة مرة أخرى',
    errorNetworkError: 'خطأ الشبكة - تحقق من اتصالك',
    errorTimeoutError: 'انتهت مهلة الانتظار',
    errorUnknownError: 'حدث خطأ',

    errorDataLoadFailed: 'فشل تحميل البيانات',
    errorSaveFailed: 'فشل حفظ التغييرات',
    errorDeleteFailed: 'فشل حذف العنصر',
    errorExportFailed: 'فشل التصدير',
    errorImportFailed: 'فشل الاستيراد',

    // Company CRUD
    successCompanyCreated: 'تم إنشاء الشركة بنجاح',
    successCompanyUpdated: 'تم تحديث الشركة بنجاح',
    successCompanyDeactivated: 'تم إلغاء تنشيط الشركة بنجاح',
    errorCompanyCreateFailed: 'فشل إنشاء الشركة',
    errorCompanyUpdateFailed: 'فشل تحديث الشركة',
    errorCompanyDeactivateFailed: 'فشل إلغاء تنشيط الشركة',
    errorCompaniesLoadFailed: 'فشل تحميل الشركات',
    errorCompanyNameRequired: 'اسم الشركة مطلوب',

    // Depot CRUD
    successDepotCreated: 'تم إنشاء المستودع بنجاح',
    successDepotUpdated: 'تم تحديث المستودع بنجاح',
    successDepotDeleted: 'تم حذف المستودع بنجاح',
    errorDepotCreateFailed: 'فشل إنشاء المستودع',
    errorDepotUpdateFailed: 'فشل تحديث المستودع',
    errorDepotDeleteFailed: 'فشل حذف المستودع',
    errorDepotsLoadFailed: 'فشل تحميل المستودعات',
    successDepotGeolocate: 'تم تحديد موقع المستودع بنجاح',
    errorDepotGeolocateFailed: 'موقع المستودع غير موجود',
    successDepotSync: 'تمت مزامنة عنوان المستودع بنجاح',
    errorDepotSyncFailed: 'فشل مزامنة عنوان المستودع',
    errorDepotAddressRequired: 'عنوان المستودع مطلوب',
    errorDepotNameRequired: 'اسم المستودع مطلوب',

    // Driver CRUD
    successDriverCreated: 'تمت دعوة السائق بنجاح',
    successDriverUpdated: 'تم تحديث السائق بنجاح',
    successDriverActivated: 'تم تفعيل السائق بنجاح',
    successDriverDeactivated: 'تم إلغاء تنشيط السائق بنجاح',
    successDriverSuspended: 'تم إيقاف السائق بنجاح',
    successDriverInviteCancelled: 'تم إلغاء الدعوة',
    successDriverInviteResent: 'تمت إعادة إرسال الدعوة',
    errorDriverCreateFailed: 'فشل دعوة السائق',
    errorDriverUpdateFailed: 'فشل تحديث السائق',
    errorDriverDeactivateFailed: 'فشل إلغاء تنشيط السائق',
    errorDriverSuspendFailed: 'فشل إيقاف السائق',
    errorDriverCancelInviteFailed: 'فشل إلغاء الدعوة',
    errorDriverInviteResendFailed: 'فشل إعادة إرسال الدعوة',
    errorDriversLoadFailed: 'فشل تحميل السائقين',
    errorDriverNameRequired: 'اسم وهاتف السائق مطلوبان',
    errorDriverEmailRequired: 'البريد الإلكتروني للسائق مطلوب',
    errorDriverInviteExpired: 'انتهت صلاحية الدعوة. أعد إرسال رمز جديد.',
    errorPendingStatusLocked: 'لا يمكن تغيير حالة حساب قيد التفعيل.',
    errorDriverRateLimited: 'محاولات كثيرة. يرجى الانتظار.',
    successDriversImported: 'تم استيراد السائقين بنجاح',

    // Import
    successImportCompleted: 'تم اكتمال استيراد الطلبات',
    errorImportBatchFailed: 'فشل استيراد بعض الطلبات',
    successImportSingle: 'تم استيراد الطلب بنجاح',
    errorImportSingleFailed: 'فشل استيراد الطلب',
    errorImportAlreadyExists: 'هذا الطلب موجود بالفعل في النظام',

    // Vehicle CRUD
    successVehicleCreated: 'تم إنشاء المركبة بنجاح',
    successVehicleUpdated: 'تم تحديث المركبة بنجاح',
    successVehicleDeleted: 'تم حذف المركبة بنجاح',
    errorVehicleCreateFailed: 'فشل إنشاء المركبة',
    errorVehicleUpdateFailed: 'فشل تحديث المركبة',
    errorVehicleDeleteFailed: 'فشل حذف المركبة',
    errorVehiclesLoadFailed: 'فشل تحميل المركبات',
    errorVehiclePlateRequired: 'رقم لوحة المركبة مطلوب',

    // Zone CRUD
    successZoneCreated: 'تم إنشاء المنطقة بنجاح',
    successZoneUpdated: 'تم تحديث المنطقة بنجاح',
    successZoneDeleted: 'تم حذف المنطقة بنجاح',
    errorZoneCreateFailed: 'فشل إنشاء المنطقة',
    errorZoneUpdateFailed: 'فشل تحديث المنطقة',
    errorZoneDeleteFailed: 'فشل حذف المنطقة',
    errorZonesLoadFailed: 'فشل تحميل المناطق',
    successZonesSynced: 'تمت إعادة تعيين الشحنات للمناطق',
    errorZonesSyncFailed: 'فشل مزامنة المناطق',
    errorZoneNameRequired: 'اسم المنطقة مطلوب',
    errorZoneMinPostalCodesRequired: 'مطلوب رمز بريدي واحد على الأقل',
    errorZoneConflictingCodes: 'بعض الرموز البريدية معينة بالفعل',
    errorZoneCodeAlreadyAdded: 'تم إضافة الرمز البريدي بالفعل',
    errorZoneCodeLookupFailed: 'الرمز البريدي غير موجود',

    // Undo / Optimistic
    successUndoAction: 'تم التراجع عن الإجراء بنجاح',
    errorUndoActionFailed: 'فشل التراجع عن الإجراء',
    errorActionFailed: 'فشل الإجراء',
  },

  // ── sidebar ─────────────────────────────────────────────────────────────
  sidebar: {
    collapse: 'تصغير',
    groups: {
      operations: 'العمليات',
      deliveries: 'الشحنات',
      planning: 'التخطيط',
      fleet: 'الأسطول',
      analytics: 'التحليلات',
      platform: 'المنصة',
    },
    items: {
      dashboard: 'لوحة القيادة',
      overview: 'نظرة عامة',
      dispatch: 'التوزيع',
      tracking: 'المتابعة',
      import: 'الاستيراد',
      createRoute: 'إنشاء رحلة',
      routes: 'الرحلات',
      drivers: 'السائقين',
      vehicles: 'المركبات',
      depots: 'المستودعات',
      zones: 'المناطق الجغرافية',
      performance: 'الأداء',
      audit: 'التدقيق',
      settings: 'الإعدادات',
      erpIntegration: 'ربط ERP',
      companies: 'الشركات',
    }
  },

  // ── notifications ────────────────────────────────────────────────────────
  notifications: {
    FAILED: {
      title: 'فشل التوصيل',
      message: (p: any) => `${_ar_ref(p)}${p.clientName || 'العميل'} — فشل التوصيل${p.motif ? ` · ${p.motif}` : ''}`,
    },
    DELIVERED: {
      title: 'توصيل ناجح',
      message: (p: any) => `${_ar_ref(p)}${p.clientName || 'العميل'} — تم التوصيل`,
    },
    'delivery.created': {
      title: 'شحنة جديدة',
      message: (p: any) => {
        const base = p.orderId
          ? `تم إنشاء الطلب ${p.orderId}${p.clientName ? ` · ${p.clientName}` : ''}`
          : `طلب جديد${p.clientName ? ` · ${p.clientName}` : ''}`;
        const cod = p.isCod ? ` · 💰 ${_ar_money(p.totalAmount, p.currency)}` : '';
        return `${base}${cod}`;
      },
    },
    'delivery.scheduled': {
      title: 'تمت جدولة الشحنة',
      message: (p: any) => {
        const parts = [`${p.clientName || 'العميل'} — تمت الجدولة`];
        if (p.driverName) parts.push(p.driverName);
        if (p.dropoffAddress) parts.push(p.dropoffAddress);
        if (p.isCod) parts.push(`💰 ${_ar_money(p.totalAmount, p.currency)}`);
        return `${_ar_ref(p)}${parts.join(' · ')}`;
      },
    },
    'delivery.picked_up': {
      title: 'تم استلام الطرد',
      message: (p: any) => `${_ar_ref(p)}${p.clientName || 'العميل'} — تم الاستلام`,
    },
    'delivery.in_transit': {
      title: 'جاري التوصيل',
      message: (p: any) => {
        const eta = _ar_fmtEta(p.etaAt);
        const parts = [`${p.clientName || 'العميل'} — السائق في الطريق`];
        if (p.driverName) parts.push(p.driverName);
        if (eta) parts.push(`الوصول ${eta}`);
        if (p.routeDistanceKm) parts.push(`${Number(p.routeDistanceKm).toFixed(1)} كم`);
        return `${_ar_ref(p)}${parts.join(' · ')}`;
      },
    },
    'delivery.completed': {
      title: 'توصيل ناجح',
      message: (p: any) => `${_ar_ref(p)}${p.clientName || 'العميل'} — تم التوصيل`,
    },
    'delivery.failed': {
      title: 'فشل التوصيل',
      message: (p: any) => {
        const parts = [`${p.clientName || 'العميل'} — فشل`];
        if (p.motif) parts.push(p.motif);
        if (p.driverName) parts.push(p.driverName);
        return `${_ar_ref(p)}${parts.join(' · ')}`;
      },
    },
    'delivery.cancelled': {
      title: 'تم إلغاء الشحنة',
      message: (p: any) => `${_ar_ref(p)}${p.clientName || 'العميل'} — تم الإلغاء`,
    },
    'delivery.reassigned': {
      title: 'إعادة تعيين الشحنة',
      message: (p: any) => `${_ar_ref(p)}${p.clientName || 'العميل'} — سائق جديد${p.driverName ? ` · ${p.driverName}` : ''}`,
    },
    'delivery.reassigned_away': {
      title: 'إزالة الشحنة',
      message: (p: any) => `${_ar_ref(p)}${p.clientName || 'العميل'} — أُزيلت من مسار السائق`,
    },
    'delivery.handoff_required': {
      title: 'تسليم مطلوب',
      message: (p: any) => `${_ar_ref(p)}${p.clientName || 'العميل'} — تسليم الطرد مطلوب${p.driverName ? ` · ${p.driverName}` : ''}`,
    },
    'delivery.replanned': {
      title: 'تأجيل الشحنة',
      message: (p: any) => `${_ar_ref(p)}${p.clientName || 'العميل'} — تم التأجيل`,
    },
    'route.validated': {
      title: 'تأكيد الرحلة',
      message: (p: any) => {
        const n = Number(p.stopCount);
        const win = _ar_window(p.plannedStartTime, p.plannedEndTime);
        const parts = [`"${p.routeName || 'الرحلة'}" — جاهزة للبدء`];
        if (Number.isFinite(n) && n > 0) parts.push(_ar_stops(n));
        if (win) parts.push(win);
        if (p.driverName) parts.push(p.driverName);
        return parts.join(' · ');
      },
    },
    'route.schedule_changed': {
      title: 'تحديث الجدول الزمني',
      message: (p: any) => {
        const win = _ar_window(p.plannedStartTime, p.plannedEndTime);
        return win
          ? `"${p.routeName || 'الرحلة'}" — موعد جديد ${win}`
          : `"${p.routeName || 'الرحلة'}" — تم تحديث الجدول الزمني`;
      },
    },
    'route.stop_added': {
      title: 'إضافة إيقاف',
      message: (p: any) => p.clientName ? `تمت إضافة ${p.clientName} إلى "${p.routeName || 'الرحلة'}"` : `"${p.routeName || 'الرحلة'}" — إيقاف جديد`,
    },
    'route.stop_removed': {
      title: 'إزالة إيقاف',
      message: (p: any) => p.clientName ? `تمت إزالة ${p.clientName}${p.erpOrderId ? ` [${p.erpOrderId}]` : ''} من "${p.routeName || 'الرحلة'}"${p.reason ? ` — ${p.reason}` : ''}` : `"${p.routeName || 'الرحلة'}" — تم إزالة إيقاف`,
    },
    'delivery.handoff_confirmed': {
      title: 'تأكيد تسليم الطرد',
      message: (p: any) => `تم تسليم طرد ${p.clientName || 'العميل'} إلى السائق الجديد`,
    },
    'handoff.requested': {
      title: 'تسليم مطلوب',
      message: (p: any) => `${_ar_ref(p)}${p.clientName || 'العميل'} — تسليم ${p.fromDriverName || '—'} ← ${p.toDriverName || '—'}`,
    },
    'handoff.overdue': {
      title: 'تأخّر التسليم',
      message: (p: any) => `${_ar_ref(p)}${p.clientName || 'العميل'} — لم يتم تأكيد التسليم (${p.fromDriverName || '—'} ← ${p.toDriverName || '—'})`,
    },
    'handoff.cancelled': {
      title: 'أُلغي التسليم',
      message: (p: any) => `${_ar_ref(p)}${p.clientName || 'العميل'} — أُلغي التسليم${p.reason ? ` · ${p.reason}` : ''}`,
    },
    'sla.breach': {
      title: 'تنبيه خرق SLA',
      message: (p: any) => {
        const head = `${_ar_ref(p)}${p.clientName || 'العميل'} — `;
        if (p.motif === 'SLA_WAITING') {
          return `${head}في انتظار الإسناد منذ ${p.elapsed} دقيقة (الحد ${p.limit} دقيقة)`;
        }
        if (p.motif === 'SLA_ASSIGNMENT') {
          return `${head}تأخّر الانطلاق: ${p.elapsed} دقيقة منذ الإسناد (الحد ${p.limit} دقيقة)`;
        }
        if (p.motif === 'SLA_PICKUP') {
          return `${head}تأخّر المغادرة من المستودع: ${p.elapsed} دقيقة (الحد ${p.limit} دقيقة)`;
        }
        if (p.motif === 'SLA_TRANSIT') {
          return `${head}تأخّر التسليم عن الموعد المحدد`;
        }
        return p.slaMessage || `${head}تأخير حرج (SLA)`;
      },
    },
    'STOPS_TRANSFERRED_OUT': {
      title: 'تم نقل الإيقافات للخارج',
      message: (p: any) => `تمت إزالة بعض الإيقافات من "${p.routeName || 'الرحلة'}"`,
    },
    'STOPS_TRANSFERRED_IN': {
      title: 'تم استلام إيقافات',
      message: (p: any) => `تمت إضافة إيقافات إلى "${p.routeName || 'الرحلة'}"`,
    },
    'erp.sync_failed': {
      title: 'فشل مزامنة ERP',
      message: (p: any) => {
        const op = ({ STOCK: 'تحديث المخزون', CANCELLATION: 'إلغاء', FAILURE_REPORT: 'تقرير الفشل' } as Record<string, string>)[p.motif] || '';
        return `${_ar_ref(p)}${p.clientName || 'الطلب'} — فشلت مزامنة ERP${op ? ` (${op})` : ''}. يلزم التدخل.`;
      },
    },
    'erp.orders_ready': {
      title: 'طلبات ERP جاهزة',
      message: (p: any) => `${p.count || 'طلبات'} ERP في انتظار الاستيراد — بحاجة إلى مراجعة`,
    },
  },
  landingPage: {
    metaTitle: "ASM Track — إدارة وتوجيه اللوجستيات",
    heroBadge: "منصة تنظيم العمليات اللوجستية",
    heroTitle1: "العمليات اللوجستية",
    heroTitle2: "برؤية جديدة.",
    heroDesc: "تنظيم فوري، توجيه ذكي ومتابعة الأسطول — في نظام عصبي موحد.",
    heroCta: "اتصل بالفريق",
    heroDashboard: "الدخول إلى لوحة التحكم ←",
    statDeliveries: "عمليات التوصيل",
    statOptimization: "أفضل مسار",
    statUptime: "جاهزية النظام",
    featuresTitle: "المميزات",
    featuresSubtitle1: "كل عملية توصيل،",
    featuresSubtitle2: "تحت السيطرة.",
    featureRealtime: "تتبع فوري",
    featureRealtimeDesc: "شاهد الموقع الدقيق لكل سائق وتقدم كل خط سير مباشرة.",
    featureDispatch: "التوجيه وإعادة التعيين",
    featureDispatchDesc: "أعد تعيين الشحنات أثناء الحركة مع تنبيهات تلقائية. دون أي عوائق، القرار في 3 نقرات.",
    featureErp: "الربط مع Odoo / ERP",
    featureErpDesc: "مزامنة ثنائية الاتجاه مع Odoo — الطلبات، المخزون وإيصالات التسليم.",
    manifestoTitle: "البيان",
    manifestoDesc: "تركز معظم الأدوات اللوجستية على التخطيط فقط.",
    manifestoFocus1: "أما نحن فنركز على",
    manifestoFocus2: "التنفيذ العملي.",
    manifestoPillar1: "سرعة الاستجابة",
    manifestoPillar1Desc: "يتم اكتشاف كل مشكلة ومعالجتها قبل أن يلاحظها العميل.",
    manifestoPillar2: "الشفافية",
    manifestoPillar2Desc: "يرى كل طرف ما يحتاجه تماماً، لا أكثر ولا أقل.",
    manifestoPillar3: "الموثوقية",
    manifestoPillar3Desc: "يتم الوفاء بوعود التسليم. بانتظام. وبدون استثناء.",
    methodTitle: "المنهجية",
    methodSubtitle1: "من الطلب",
    methodSubtitle2: "إلى التوقيع والاستلام.",
    methodStep1: "الاستيراد والتخطيط",
    methodStep1Desc: "يتم استيراد الطلبات تلقائياً. يقوم الذكاء الاصطناعي بتوليد المسارات المثلى في أقل من ثانيتين.",
    methodStep2: "التوجيه والمتابعة المباشرة",
    methodStep2Desc: "يتلقى السائقون مساراتهم فورياً. كل حركة يتم تعقبها ورصدها من لوحة التحكم.",
    methodStep3: "التحليل والتحسين",
    methodStep3Desc: "كل خط سير مكتمل يغذي خوارزميات التحسين للرفع المستمر من الأداء.",
    ctaTitle: "دعنا نبدأ",
    ctaSubtitle1: "هل أنت مستعد لبسط",
    ctaSubtitle2: "سيطرتك الكاملة؟",
    ctaDesc: "اتصل بفريق ASM Track للحصول على عرض توضيحي مخصص للمنصة.",
    ctaButton: "اتصل بالفريق ←",
    footerDesc: "النظام العصبي اللوجستي للجيل القادم.",
    footerNav: "التنقل",
    footerContact: "اتصل بنا",
    footerRights: "© 2026 ASMTRACK — برمجيات صناعية",
    navFeatures: "المميزات",
    navMethod: "المنهجية",
    navContact: "الاتصال",
    navLogin: "تسجيل الدخول",
  },
  loginPage: {
    auth: "تسجيل الدخول",
    welcomeBack: "مرحبًا بعودتك",
    signInToContinue: "سجّل الدخول إلى حسابك للمتابعة.",
    title1: "الدخول إلى",
    title2: "لوحة التحكم.",
    emailLabel: "البريد الإلكتروني",
    emailPh: "mail@example.com",
    passLabel: "كلمة المرور",
    passPh: "••••••••••",
    button: "الدخول إلى لوحة التحكم",
    buttonLoading: "جاري الدخول...",
    needAccess: "تحتاج إلى حساب؟",
    contactTeam: "اتصل بالفريق ←",
    back: "رجوع",
    errorDefault: "البريد الإلكتروني أو كلمة المرور غير صحيحة",
    successToast: "تم تسجيل الدخول بنجاح",
    brandTagline: "تخطيط. تتبع. تسليم.",
    pillLogistics: "التنسيق اللوجستي",
    pillRealtime: "تنسيق فوري وموثوقية تشغيلية كاملة.",
    pillPilotez: "قُد عملياتك بكل",
    pillCertitude: "ثقة ويقين.",
  },
  mapSection: {
    dispatch: {
      title: "تخطيط\nتلقائي",
      desc: "استيراد طلبات ERP. تحسين المسار الإجمالي في ثانيتين.",
      tag: "التوجيه",
    },
    scooter: {
      title: "إشعار\nالسائق",
      desc: "إرسال خط السير في الوقت الفعلي. نظام ملاحة مدمج.",
      tag: "في الطريق",
    },
    delivery: {
      title: "تأكيد\nالتسليم",
      desc: "التوقيع الإلكتروني. إثبات فوتوغرافي للتوصيل.",
      tag: "التوصيل",
    },
    confirm: {
      title: "تمت\nالمهمة بنجاح",
      desc: "توليد تلقائي للتقرير. تحديث مؤشرات الأداء الفورية.",
      tag: "مكتمل",
    },
  },
} as const;
