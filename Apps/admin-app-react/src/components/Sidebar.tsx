import React from 'react';
import { Link } from 'react-router-dom';
import { useLocation } from 'react-router-dom';
import { useEffect, useMemo, useState } from 'react';
import {
  IconLayoutDashboard, IconCommand, IconCalendarEvent,
  IconPackage, IconUpload, IconRoute, IconMapPlus,
  IconUsers, IconTruck, IconBuildingWarehouse, IconMap2,
  IconChartLine, IconFileText, IconSettings, IconDatabase,
  IconChevronsLeft, IconChevronsRight,
  IconChevronDown, IconChevronRight, IconPackageExport, IconHeartbeat, IconBan
} from '@tabler/icons-react';
import {
  canManageSettings, getCurrentRole, canImportErp,
  canManageRoutes, canDispatch, canViewReadOnly
} from '@/lib/auth';
import { AdminRole } from '@/types';
import { useT } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { useSidebar } from '@/components/ui/sidebar';
import { useAlerts } from '@/components/AlertsProvider';
import { api } from '@/lib/api';
import s from './Sidebar.module.scss';
import { cn } from '@/lib/utils';

// ── Types ───────────────────────────────────────────────────────────────────

export type NavItem = {
  labelKey: string;
  href: string;
  Icon: React.ComponentType<{ size?: number; stroke?: number; className?: string }>;
  roleCheck?: (role: AdminRole) => boolean;
};

export type NavGroupDef = {
  groupKey: string;
  Icon: React.ComponentType<{ size?: number; className?: string }>;
  items: NavItem[];
};

// Static structural definition — the single source of truth for the nav AND for
// breadcrumbs (TopNav derives "Group › Page" from this so the two never drift).
export const GROUP_DEFS: NavGroupDef[] = [
  {
    groupKey: 'operations',
    Icon: IconCalendarEvent,
    items: [
      { labelKey: 'dashboard',  href: '/dashboard',     Icon: IconLayoutDashboard },
      { labelKey: 'overview',   href: '/overview',    Icon: IconCalendarEvent,           roleCheck: canViewReadOnly },
      { labelKey: 'dispatch',   href: '/dispatch-desk', Icon: IconCommand,          roleCheck: canDispatch },
      { labelKey: 'systemHealth', href: '/system-health', Icon: IconHeartbeat,      roleCheck: canManageSettings },
    ],
  },
  {
    groupKey: 'deliveries',
    Icon: IconPackage,
    items: [
      { labelKey: 'tracking',   href: '/deliveries',    Icon: IconPackage,          roleCheck: canDispatch },
      { labelKey: 'returns',    href: '/returns',       Icon: IconPackageExport,    roleCheck: canDispatch },
      { labelKey: 'import',     href: '/import',        Icon: IconUpload,           roleCheck: canImportErp },
      { labelKey: 'failureReasons', href: '/failure-reasons', Icon: IconBan,        roleCheck: canManageSettings },
    ],
  },
  {
    groupKey: 'planning',
    Icon: IconRoute,
    items: [
      { labelKey: 'createRoute', href: '/route-builder', Icon: IconMapPlus,         roleCheck: canManageRoutes },
      { labelKey: 'routes',      href: '/routes-table',  Icon: IconRoute,           roleCheck: canViewReadOnly },
    ],
  },
  {
    groupKey: 'fleet',
    Icon: IconTruck,
    items: [
      { labelKey: 'drivers',    href: '/drivers',       Icon: IconUsers,            roleCheck: canDispatch },
      { labelKey: 'vehicles',   href: '/vehicles',      Icon: IconTruck,            roleCheck: canDispatch },
      { labelKey: 'depots',     href: '/depots',        Icon: IconBuildingWarehouse,roleCheck: canDispatch },
      { labelKey: 'zones',      href: '/zones',         Icon: IconMap2,             roleCheck: canDispatch },
    ],
  },
  {
    groupKey: 'analytics',
    Icon: IconChartLine,
    items: [
      { labelKey: 'performance', href: '/performance',  Icon: IconChartLine },
      { labelKey: 'audit',       href: '/audit-logs',   Icon: IconFileText,         roleCheck: canDispatch },
    ],
  },
];

export function AppSidebar() {
  const t = useT();
  const { pathname } = useLocation();
  const { open: isOpen, toggleSidebar, isMobile } = useSidebar();
  const isCollapsed = !isOpen && !isMobile;

  const [role, setRole] = useState<AdminRole>('UNKNOWN');
  const [isClient, setIsClient] = useState(false);
  const [telemetry, setTelemetry] = useState<{ erpPending: number; activeRoutes: number; opsExceptions: number } | null>(null);

  // Auto-close drawer on mobile when routing changes
  useEffect(() => {
    if (isMobile && isOpen) {
      toggleSidebar();
    }
  }, [pathname, isMobile]);

  const { locale: activeLocale } = useLocaleStore();
  const { unreadCount } = useAlerts();

  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>({
    operations: true,
    deliveries: true,
    planning: true,
    fleet: true,
    analytics: false,
    settings: true,
  });

  const toggleGroup = (groupKey: string) => {
    setExpandedGroups(prev => ({
      ...prev,
      [groupKey]: !prev[groupKey]
    }));
  };

  useEffect(() => {
    setIsClient(true);
    setRole(getCurrentRole());
  }, []);

  useEffect(() => {
    if (!isClient) return;
    let active = true;

    const fetchTelemetry = async () => {
      try {
        const [erpRes, routesRes, exceptionsRes] = await Promise.all([
          api.get('/api/admin/erp/pending-orders', { params: { limit: 200 } }).catch(() => ({ data: [] })),
          api.get('/api/admin/routes').catch(() => ({ data: [] })),
          api.get('/api/admin/ops/exceptions', { params: { period: 'all', limit: 200 } }).catch(() => ({ data: { items: [] } }))
        ]);
        if (!active) return;

        const pendingList = Array.isArray(erpRes.data) ? erpRes.data : [];
        const erpPending = pendingList.filter((x: any) => !x.alreadyImported).length;

        const routesList = Array.isArray(routesRes.data) ? routesRes.data : [];
        const activeRoutes = routesList.filter((r: any) => r.status === 'IN_PROGRESS').length;

        const exceptionsList = Array.isArray(exceptionsRes.data?.items) ? exceptionsRes.data.items : [];
        const opsExceptions = exceptionsList.length;

        setTelemetry({ erpPending, activeRoutes, opsExceptions });
      } catch (err) {
        console.error('[Sidebar telemetry error]', err);
      }
    };

    fetchTelemetry();
    const timer = setInterval(fetchTelemetry, 30000); // Polling every 30s for organic accuracy
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [isClient]);

  const groups = useMemo(() =>
    GROUP_DEFS.map((g) => ({
      labelKey: g.groupKey,
      label: (t.sidebar.groups as any)[g.groupKey] || g.groupKey,
      Icon: g.Icon,
      items: g.items.map((item) => ({
        labelKey: item.labelKey,
        label: (t.sidebar.items as any)[item.labelKey] || item.labelKey,
        href:  item.href,
        Icon:  item.Icon,
        roleCheck: item.roleCheck,
      })),
    })),
    [t],
  );

  const getBadgeFor = (labelKey: string) => {
    const sb = t.sidebar.badges;
    if (labelKey === 'dispatch' && telemetry && telemetry.opsExceptions > 0) {
      const n = telemetry.opsExceptions;
      const text = activeLocale === 'ar'
        ? (n === 1 ? sb.arDispatchOne : `${n} ${sb.arDispatchMany}`)
        : activeLocale === 'fr'
        ? (n === 1 ? `1 ${sb.dispatchActionRequired}` : `${n} ${sb.dispatchActionsRequired}`)
        : (n === 1 ? `1 ${sb.dispatchActionRequired}` : `${n} ${sb.dispatchActionsRequired}`);
        
      return { text, rawCount: n, type: 'alert' as const };
    }
    if (labelKey === 'import' && telemetry && telemetry.erpPending > 0) {
      const n = telemetry.erpPending;
      const text = activeLocale === 'ar'
        ? (n === 1 ? sb.arImportOne : `${n} ${sb.arImportMany}`)
        : activeLocale === 'fr'
        ? (n === 1 ? `1 ${sb.importOne}` : `${n} ${sb.imports}`)
        : (n === 1 ? `1 ${sb.importOne}` : `${n} ${sb.imports}`);

      return { text, rawCount: n, type: 'neutral' as const };
    }
    if (labelKey === 'routes' && telemetry && telemetry.activeRoutes > 0) {
      const n = telemetry.activeRoutes;
      const text = activeLocale === 'ar'
        ? (n === 1 ? sb.arRouteActive : `${n} ${sb.arRouteActiveMany}`)
        : activeLocale === 'fr'
        ? (n === 1 ? `1 ${sb.routeActive}` : `${n} ${sb.routesActive}`)
        : (n === 1 ? `1 ${sb.routeActive}` : `${n} ${sb.routesActive}`);

      return { text, rawCount: n, type: 'info' as const };
    }
    return null;
  };

  if (!isClient) return null;

  return (
    <>
      {isMobile && isOpen && (
        <div
          className="fixed inset-0 bg-black/40 z-45 transition-opacity duration-300 animate-fadeIn"
          onClick={toggleSidebar}
        />
      )}
      <aside className={cn(s.sidebar, isCollapsed && s.collapsed, isMobile && isOpen && s.mobileOpen)}>
      
      {/* ── Brand Header ── */}
      <Link 
        to="/dashboard" 
        className={s.brand} 
      >
        <div className={s.brand__icon}>
          <img src="/icon.png" alt="ASM" className={s.brand__logo} />
        </div>
        <div className={s.brand__text}>
          <span 
            className={s['brand__text-name']} 
            style={{ 
              letterSpacing: '0.25em', 
              textTransform: 'uppercase', 
              fontSize: '11px', 
              fontFamily: "var(--font-heading)" 
            }}
          >
            ASM Track
          </span>
          <span className={s['brand__text-sub']}>{t.loginPage.brandTagline}</span>
        </div>
      </Link>

      {/* ── Navigation ── */}
      <nav className={s.nav}>
        {groups.map((group) => {
          const visibleItems = group.items.filter(item => !item.roleCheck || item.roleCheck(role));
          if (visibleItems.length === 0) return null;

          const isExpanded = isCollapsed ? true : (expandedGroups[group.labelKey] ?? true);

          // Calculate collapsed group indicator status
          const groupBadges = group.items.map(item => getBadgeFor(item.labelKey)).filter(Boolean);
          const hasAlert = groupBadges.some(b => b?.type === 'alert');
          const hasInfo = groupBadges.some(b => b?.type === 'info');
          const hasNeutral = groupBadges.some(b => b?.type === 'neutral');

          return (
            <div key={group.label} className={cn(s.group, !isExpanded && s['group--collapsed'])}>
              
              {/* Group Dropdown Header Toggle */}
              {isCollapsed ? (
                <div className={s.group__label_collapsed_sep} />
              ) : (
                <button
                  type="button"
                  className={s.group__header}
                  onClick={() => toggleGroup(group.labelKey)}
                  aria-expanded={isExpanded}
                  aria-label={group.label}
                >
                  <div className="flex items-center gap-2 min-w-0">
                    <group.Icon size={12} className="text-[var(--sb-label)] opacity-70 shrink-0" />
                    <span className={s.group__label}>{group.label}</span>
                  </div>
                  <div className="flex items-center gap-1.5 shrink-0">
                    {!isExpanded && groupBadges.length > 0 && (
                      <span className={cn(
                        s.group__indicator,
                        hasAlert && s['group__indicator--alert'],
                        hasInfo && s['group__indicator--info'],
                        hasNeutral && s['group__indicator--neutral']
                      )} />
                    )}
                    {isExpanded ? (
                      <IconChevronDown size={11} stroke={2.5} className="text-[var(--sb-label)] opacity-60" />
                    ) : (
                      <IconChevronRight size={11} stroke={2.5} className="text-[var(--sb-label)] opacity-60" />
                    )}
                  </div>
                </button>
              )}

              {/* Group Items Container */}
              <div 
                className={cn(s.group__items, !isExpanded && s['group__items--collapsed'])}
              >
                {visibleItems.map(item => {
                  const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
                  const badge = getBadgeFor(item.labelKey);

                  return (
                    <Link
                      key={item.href}
                      to={item.href}
                      className={cn(s.item, active && s['item--active'])}
                    >
                      <span className={s.item__icon}>
                        <item.Icon size={16} stroke={active ? 2 : 1.5} />
                        {badge && isCollapsed && (
                          <span className={cn(
                            s.item__dot,
                            badge.type === 'alert' && s['item__dot--alert'],
                            badge.type === 'info' && s['item__dot--info'],
                            badge.type === 'neutral' && s['item__dot--neutral']
                          )} />
                        )}
                      </span>
                      <span className={s.item__label}>{item.label}</span>
                      
                      {badge && !isCollapsed && (
                        <span className={cn(
                          s.item__badge,
                          badge.type === 'alert' && s['item__badge--alert'],
                          badge.type === 'info' && s['item__badge--info'],
                          badge.type === 'neutral' && s['item__badge--neutral']
                        )}>
                          {badge.text}
                        </span>
                      )}
                      
                      {/* Collapsed Tooltip */}
                      {isCollapsed && (
                        <span className={s.item__tooltip}>
                          {item.label}
                          {badge && ` (${badge.text})`}
                        </span>
                      )}
                    </Link>
                  );
                })}
              </div>
            </div>
          );
        })}
        
        {/* Settings Group */}
        {(!canManageSettings || canManageSettings(role)) && (
          <div className={cn(s.group, !expandedGroups.settings && s['group--collapsed'])} style={{ marginTop: 'auto', borderTop: '1px solid var(--sb-sep-h)', paddingTop: '12px' }}>
            {isCollapsed ? (
              <div className={s.group__label_collapsed_sep} />
            ) : (
              <button
                type="button"
                className={s.group__header}
                onClick={() => toggleGroup('settings')}
                aria-expanded={expandedGroups.settings}
                aria-label={(t.sidebar.items as any)['settings'] || 'Settings'}
              >
                <div className="flex items-center gap-2 min-w-0">
                  <IconSettings size={12} className="text-[var(--sb-label)] opacity-70 shrink-0" />
                  <span className={s.group__label}>{(t.sidebar.items as any)['settings'] || 'Settings'}</span>
                </div>
                <div className="flex items-center gap-1.5 shrink-0">
                  {expandedGroups.settings ? (
                    <IconChevronDown size={11} stroke={2.5} className="text-[var(--sb-label)] opacity-60" />
                  ) : (
                    <IconChevronRight size={11} stroke={2.5} className="text-[var(--sb-label)] opacity-60" />
                  )}
                </div>
              </button>
            )}
            <div className={cn(s.group__items, !expandedGroups.settings && s['group__items--collapsed'])}>
              <Link
                to="/settings"
                className={cn(s.item, (pathname === '/settings') && s['item--active'])}
              >
                <span className={s.item__icon}>
                  <IconSettings size={16} stroke={(pathname === '/settings') ? 2 : 1.5} />
                </span>
                <span className={s.item__label}>{(t.settingsPage.generalConfig) || 'Général'}</span>
                {isCollapsed && (
                  <span className={s.item__tooltip}>
                    {(t.settingsPage.generalConfig) || 'Général'}
                  </span>
                )}
              </Link>
              <Link
                to="/settings/erp"
                className={cn(s.item, (pathname === '/settings/erp') && s['item--active'])}
              >
                <span className={s.item__icon}>
                  <IconDatabase size={16} stroke={(pathname === '/settings/erp') ? 2 : 1.5} />
                </span>
                <span className={s.item__label}>{(t.sidebar.items as any)['erpIntegration'] || 'Intégration ERP'}</span>
                {isCollapsed && (
                  <span className={s.item__tooltip}>
                    {(t.sidebar.items as any)['erpIntegration'] || 'Intégration ERP'}
                  </span>
                )}
              </Link>
            </div>
          </div>
        )}
      </nav>

      {/* ── Footer ── */}
      <div className={s.footer}>
        
        {/* User Profile Indicator */}
        <div 
          className="flex items-center px-3 py-2 border-b border-[var(--sb-sep-h)] mb-2 gap-2 text-left" 
          style={{ 
            justifyContent: isCollapsed ? 'center' : 'flex-start', 
            borderBottom: isCollapsed ? 'none' : '1px solid var(--sb-sep-h)',
            paddingLeft: isCollapsed ? 0 : 12,
            paddingRight: isCollapsed ? 0 : 12
          }}
        >
          <div className="flex items-center gap-2 min-w-0">
            <div className="w-6 h-6 rounded-full bg-[var(--sb-bg-hover)] border border-[var(--sb-sep-h)] flex items-center justify-center shrink-0 font-bold text-2xs text-[var(--sb-item-active)] shadow-2xs">
              SU
            </div>
            {!isCollapsed && (
              <div className="flex flex-col min-w-0">
                <span className="font-semibold text-xs text-[var(--sb-item-active)] truncate leading-tight">Super Admin</span>
                <span className="text-2xs text-[var(--sb-label)] tracking-wider leading-none mt-0.5">Admin</span>
              </div>
            )}
          </div>
        </div>

        {/* Collapse toggle button */}
        {!isMobile && (
          <button
            type="button"
            onClick={toggleSidebar}
            className={s.footer__toggle}
            aria-label={isCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            <svg 
              data-testid={isCollapsed ? "collapse-right-icon" : "collapse-left-icon"} 
              role="img" 
              aria-hidden="true" 
              className="gl-button-icon gl-icon s16 gl-fill-current shrink-0" 
              style={{ 
                width: 14, 
                height: 14, 
                fill: 'currentColor'
              }}
              viewBox="0 0 16 16"
            >
              {isCollapsed ? (
                <path fillRule="evenodd" clipRule="evenodd" d="M2 3a.5.5 0 0 1 .5-.5h1a.5.5 0 0 1 .5.5v10a.5.5 0 0 1-.5.5h-1a.5.5 0 0 1-.5-.5V3zm9.146 4.146a.5.5 0 0 1 0 .708l-3.5 3.5a.5.5 0 0 1-.708-.708L9.293 8.5H4.5a.5.5 0 0 1 0-1h4.793L6.938 4.854a.5.5 0 0 1 .708-.708l3.5 3.5z" />
              ) : (
                <path fillRule="evenodd" clipRule="evenodd" d="M2 3a.5.5 0 0 1 .5-.5h1a.5.5 0 0 1 .5.5v10a.5.5 0 0 1-.5.5h-1a.5.5 0 0 1-.5-.5V3zm5.854 1.146a.5.5 0 0 1 0 .708L5.207 7.5H13.5a.5.5 0 0 1 0 1H5.207l2.647 2.646a.5.5 0 0 1-.708.708l-3.5-3.5a.5.5 0 0 1 0-.708l3.5-3.5a.5.5 0 0 1 .708 0z" />
              )}
            </svg>
            {!isCollapsed && (
              <span className={s.footer__label}>
                {activeLocale === 'ar' ? 'تصغير الشريط الجانبي' : activeLocale === 'fr' ? 'Réduire la barre latérale' : 'Collapse sidebar'}
              </span>
            )}
          </button>
        )}
      </div>

    </aside>
    </>
  );
}
