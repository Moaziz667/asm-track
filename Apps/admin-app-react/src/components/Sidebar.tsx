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
  IconChevronDown, IconChevronRight, IconPackageExport, IconHeartbeat, IconBan, IconCashBanknote,
  IconSun, IconMoon, IconMap2 as IconMap, IconUserCircle, IconLogout
} from '@tabler/icons-react';
import {
  getCurrentRole, usePermissions, Perm
} from '@/lib/api/auth';
import { AdminRole } from '@/types';
import { useT } from '@/lib/i18n/LocaleContext';
import { tlabel } from '@/lib/i18n/i18n-dict';
import { useLocaleStore } from '@/lib/i18n';
import { useSidebar } from '@/components/ui/sidebar';
import { useGlobalMapStore } from '@/lib/state/global-map-store';
import { useAuth } from 'react-oidc-context';
import { safeStorage } from '@/lib/storage';
import { useCurrentUser } from '@/hooks/useCurrentUser';
import { api } from '@/lib/api';
import s from './Sidebar.module.scss';
import { cn } from '@/lib/utils';
import { countNeedingAttention } from '@/lib/ops/needsAttention';
import { OPS_TELEMETRY_EVENT } from '@/lib/ops/opsTelemetry';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import LanguageSelector from './LanguageSelector';

// ── Types ───────────────────────────────────────────────────────────────────

export type NavItem = {
  labelKey: string;
  href: string;
  Icon: React.ComponentType<{ size?: number; stroke?: number; className?: string }>;
  // Permission required to SEE this page (perm:* — same strings the backend enforces). Absent = always
  // visible to any authenticated user. A page shown here is one the user can at least read; action
  // controls inside are gated separately with <Can> on the corresponding manage perm.
  perm?: Perm;
};

export type NavGroupDef = {
  groupKey: string;
  Icon: React.ComponentType<{ size?: number; stroke?: number; className?: string }>;
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
      { labelKey: 'overview',   href: '/overview',    Icon: IconCalendarEvent,           perm: 'perm:report:view' },
      { labelKey: 'dispatch',   href: '/dispatch-desk', Icon: IconCommand,          perm: 'perm:dispatch:operate' },
      { labelKey: 'systemHealth', href: '/system-health', Icon: IconHeartbeat,      perm: 'perm:settings:manage' },
    ],
  },
  {
    groupKey: 'deliveries',
    Icon: IconPackage,
    items: [
      { labelKey: 'tracking',   href: '/deliveries',    Icon: IconPackage,          perm: 'perm:delivery:view' },
      { labelKey: 'returns',    href: '/returns',       Icon: IconPackageExport,    perm: 'perm:dispatch:operate' },
      { labelKey: 'cash',       href: '/cash',          Icon: IconCashBanknote,     perm: 'perm:dispatch:operate' },
      { labelKey: 'import',     href: '/import',        Icon: IconUpload,           perm: 'perm:erp:sync' },
      { labelKey: 'failureReasons', href: '/failure-reasons', Icon: IconBan,        perm: 'perm:settings:manage' },
    ],
  },
  {
    groupKey: 'planning',
    Icon: IconRoute,
    items: [
      { labelKey: 'createRoute', href: '/route-builder', Icon: IconMapPlus,         perm: 'perm:route:manage' },
      { labelKey: 'routes',      href: '/routes-table',  Icon: IconRoute,           perm: 'perm:route:view' },
    ],
  },
  {
    groupKey: 'fleet',
    Icon: IconTruck,
    items: [
      { labelKey: 'drivers',    href: '/drivers',       Icon: IconUsers,            perm: 'perm:driver:view' },
      { labelKey: 'vehicles',   href: '/vehicles',      Icon: IconTruck,            perm: 'perm:driver:view' },
      { labelKey: 'depots',     href: '/depots',        Icon: IconBuildingWarehouse,perm: 'perm:route:view' },
      { labelKey: 'zones',      href: '/zones',         Icon: IconMap2,             perm: 'perm:route:view' },
    ],
  },
  {
    groupKey: 'analytics',
    Icon: IconChartLine,
    items: [
      { labelKey: 'performance', href: '/performance',  Icon: IconChartLine,        perm: 'perm:report:view' },
      { labelKey: 'audit',       href: '/audit-logs',   Icon: IconFileText,         perm: 'perm:audit:view' },
    ],
  },
];

export function AppSidebar() {
  const t = useT();
  const { pathname } = useLocation();
  const { open: isOpen, toggleSidebar, isMobile } = useSidebar();
  const isCollapsed = !isOpen && !isMobile;
  const auth = useAuth();
  const { data: user } = useCurrentUser();

  const [role, setRole] = useState<AdminRole>('UNKNOWN');
  const { has: hasPermReactive } = usePermissions();
  const [isClient, setIsClient] = useState(false);
  const [isDark, setIsDark] = useState(false);
  const [telemetry, setTelemetry] = useState<{ erpPending: number; activeRoutes: number; opsExceptions: number } | null>(null);
  const [activeGroup, setActiveGroup] = useState<string>('operations');

  const { mapMode, setMapMode } = useGlobalMapStore();
  const toggleMap = () => setMapMode(mapMode === 'hidden' ? 'collapsed' : 'hidden');
  const { locale: activeLocale } = useLocaleStore();

  // Auto-close drawer on mobile when routing changes
  useEffect(() => {
    if (isMobile && isOpen) {
      toggleSidebar();
    }
  }, [pathname, isMobile]);



  useEffect(() => {
    setIsClient(true);
    setRole(getCurrentRole());
    setIsDark(document.documentElement.classList.contains('dark'));

    const handleStorage = (e: StorageEvent) => {
      if (e.key === 'admin-color-scheme' && e.newValue) {
        const isNextDark = e.newValue === 'dark';
        setIsDark(isNextDark);
        document.documentElement.classList.toggle('dark', isNextDark);
        document.documentElement.setAttribute('data-mantine-color-scheme', e.newValue);
        document.cookie = `asm-theme=${e.newValue}; path=/; domain=localhost; max-age=31536000; SameSite=Strict`;
      }
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, []);

  // Auto-select group based on current pathname
  useEffect(() => {
    for (const group of GROUP_DEFS) {
      for (const item of group.items) {
        if (pathname === item.href || pathname.startsWith(`${item.href}/`)) {
          setActiveGroup(group.groupKey);
          return;
        }
      }
    }
    // Check settings
    if (pathname.startsWith('/settings')) {
      setActiveGroup('settings');
    }
  }, [pathname]);

  useEffect(() => {
    if (!isClient) return;
    let active = true;

    const fetchTelemetry = async () => {
      try {
        const [erpRes, routesRes, exceptionsRes] = await Promise.all([
          api.get('/admin/erp/pending-orders', { params: { limit: 200 } }).catch(() => ({ data: [] })),
          api.get('/admin/routes').catch(() => ({ data: [] })),
          // 'all', not 'day'. A delivery that failed yesterday and is still waiting on a dispatcher
          // is precisely what this badge is for, and asking only for today left it uncounted — the
          // badge went quiet on the backlog it exists to surface.
          api.get('/admin/ops/exceptions', { params: { period: 'all', limit: 200 } }).catch(() => ({ data: { items: [] } }))
        ]);
        if (!active) return;

        const pendingList = Array.isArray(erpRes.data) ? erpRes.data : [];
        const erpPending = pendingList.filter((x: { alreadyImported?: boolean }) => !x.alreadyImported).length;

        const routesList = Array.isArray(routesRes.data) ? routesRes.data : [];
        const activeRoutes = routesList.filter((r: { status?: string }) => r.status === 'IN_PROGRESS').length;

        // The rule lives in one file now, and the dispatch desk reads the same one — the badge and
        // the page it opens can no longer drift apart.
        const exceptionsList = Array.isArray(exceptionsRes.data?.items) ? exceptionsRes.data.items : [];
        const opsExceptions = countNeedingAttention(exceptionsList);

        setTelemetry({ erpPending, activeRoutes, opsExceptions });
      } catch (err) {
        console.error('[Sidebar telemetry error]', err);
      }
    };

    fetchTelemetry();
    const timer = setInterval(fetchTelemetry, 30000);
    // Thirty seconds is right for a figure that drifts on its own, and wrong the moment a dispatcher
    // acts: the row leaves the desk in front of him while the badge keeps the old total for half a
    // minute. Two numbers on one screen disagreeing about what he just did.
    window.addEventListener(OPS_TELEMETRY_EVENT, fetchTelemetry);
    return () => {
      active = false;
      clearInterval(timer);
      window.removeEventListener(OPS_TELEMETRY_EVENT, fetchTelemetry);
    };
  }, [isClient]);

  const groups = useMemo(() =>
    GROUP_DEFS.map((g) => ({
      labelKey: g.groupKey,
      label: tlabel(t.sidebar.groups, g.groupKey) || g.groupKey,
      Icon: g.Icon,
      items: g.items.map((item) => ({
        labelKey: item.labelKey,
        label: tlabel(t.sidebar.items, item.labelKey) || item.labelKey,
        href:  item.href,
        Icon:  item.Icon,
        perm: item.perm,
      })),
    })),
    [t],
  );

  const getBadgeFor = (labelKey: string) => {
    if (labelKey === 'dispatch' && telemetry && telemetry.opsExceptions > 0) {
      const n = telemetry.opsExceptions;
      return { text: String(n), rawCount: n, type: 'alert' as const };
    }
    if (labelKey === 'import' && telemetry && telemetry.erpPending > 0) {
      const n = telemetry.erpPending;
      return { text: String(n), rawCount: n, type: 'neutral' as const };
    }
    if (labelKey === 'routes' && telemetry && telemetry.activeRoutes > 0) {
      const n = telemetry.activeRoutes;
      return { text: String(n), rawCount: n, type: 'info' as const };
    }
    return null;
  };

  const toggleDark = () => {
    const next = !isDark;
    setIsDark(next);
    const themeVal = next ? 'dark' : 'light';
    document.documentElement.classList.toggle('dark', next);
    document.documentElement.setAttribute('data-mantine-color-scheme', themeVal);
    safeStorage.setItem('admin-color-scheme', themeVal);
    document.cookie = `asm-theme=${themeVal}; path=/; domain=localhost; max-age=31536000; SameSite=Strict`;
  };

  const handleLogout = async () => {
    safeStorage.removeItem('admin-operational-filters');
    const id_token_hint = auth.user?.id_token;
    try {
      await auth.removeUser();
      await auth.signoutRedirect(id_token_hint ? { id_token_hint } : undefined);
    } catch {
      window.location.href = '/';
    }
  };

  const displayName = isClient
    ? user?.name || (role !== 'UNKNOWN' ? role : t.topNav?.user || 'User')
    : t.topNav?.user || 'User';

  if (!isClient) return null;

  // Get current page title for the nav panel header
  const getCurrentPageTitle = () => {
    for (const group of groups) {
      for (const item of group.items) {
        if (pathname === item.href || pathname.startsWith(`${item.href}/`)) {
          return item.label;
        }
      }
    }
    return tlabel(t.sidebar.groups, 'operations') || 'Operations';
  };

  return (
    <>
      {isMobile && isOpen && (
        <div
          className="fixed inset-0 bg-black/40 z-45 transition-opacity duration-300 animate-fadeIn"
          onClick={toggleSidebar}
        />
      )}
      
      {/* Icon Rail */}
      <aside className={cn(s.iconRail, isCollapsed && s['iconRail__collapsed'], isMobile && isOpen && s['iconRail__mobileOpen'])}>
        {/* Brand Logo */}
        <Link to="/dashboard" className={s['iconRail__brand']}>
          <div className={s['iconRail__brandIcon']}>
            <img src="/icon.png" alt="ASM" className={s['iconRail__brandLogo']} />
          </div>
        </Link>

        {/* Navigation Icons */}
        <nav className={s['iconRail__nav']}>
          {groups.map((group) => {
            const visibleItems = group.items.filter(item => !item.perm || hasPermReactive(item.perm as Perm));
            if (visibleItems.length === 0) return null;

            const isActive = activeGroup === group.labelKey;
            const badge = getBadgeFor(group.items[0]?.labelKey);

            return (
              <button
                key={group.labelKey}
                type="button"
                className={cn(s.iconRail__item, isActive && s['iconRail__item--active'])}
                onClick={() => {
                  if (isCollapsed) {
                    // If collapsed, expand sidebar and set active group
                    if (!isOpen) toggleSidebar();
                    setActiveGroup(group.labelKey);
                  } else {
                    setActiveGroup(group.labelKey);
                  }
                }}
                title={group.label}
              >
                <group.Icon size={20} stroke={1.5} />
                {badge && (
                  <span className={cn(
                    s['iconRail__dot'],
                    badge.type === 'alert' && s['iconRail__dot--alert'],
                    badge.type === 'info' && s['iconRail__dot--info'],
                    badge.type === 'neutral' && s['iconRail__dot--neutral']
                  )} />
                )}
              </button>
            );
          })}

          {/* Settings */}
          {hasPermReactive('perm:settings:manage') && (
            <button
              type="button"
              className={cn(s.iconRail__item, activeGroup === 'settings' && s['iconRail__item--active'])}
              onClick={() => {
                if (isCollapsed) {
                  if (!isOpen) toggleSidebar();
                  setActiveGroup('settings');
                } else {
                  setActiveGroup('settings');
                }
              }}
              title={tlabel(t.sidebar.items, 'settings') || 'Settings'}
            >
              <IconSettings size={20} stroke={1.5} />
            </button>
          )}
        </nav>

        {/* Bottom Actions */}
        <div className={s['iconRail__actions']}>
          {/* Language Selector */}
          <div className={s['iconRail__item']}>
            <LanguageSelector />
          </div>

          {/* Dark Mode Toggle */}
          <Tooltip>
            <TooltipTrigger
              render={
                <button
                  type="button"
                  onClick={toggleDark}
                  className={s.iconRail__item}
                  title={isDark ? t.topNav?.enableLightMode : t.topNav?.enableDarkMode}
                />
              }
            >
              {isDark ? <IconSun size={20} stroke={1.5} /> : <IconMoon size={20} stroke={1.5} />}
            </TooltipTrigger>
            <TooltipContent>{isDark ? t.topNav?.lightMode : t.topNav?.darkMode}</TooltipContent>
          </Tooltip>

          {/* Map Toggle */}
          <Tooltip>
            <TooltipTrigger
              render={
                <button
                  type="button"
                  onClick={toggleMap}
                  className={cn(s.iconRail__item, mapMode !== 'hidden' && s['iconRail__item--active'])}
                  title={mapMode !== 'hidden' ? t.topNav?.hideMap : t.topNav?.showMap}
                />
              }
            >
              <IconMap size={20} stroke={1.5} />
            </TooltipTrigger>
            <TooltipContent>{mapMode !== 'hidden' ? t.topNav?.hideMap : t.topNav?.showMap}</TooltipContent>
          </Tooltip>

          {/* User Menu */}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                className={cn(s.iconRail__item, s['iconRail__item--user'])}
                title={displayName}
              >
                <div className={s['iconRail__avatar']}>
                  <span className={s['iconRail__avatarText']}>
                    {displayName.charAt(0).toUpperCase()}
                  </span>
                </div>
              </button>
            </DropdownMenuTrigger>

            <DropdownMenuContent align="start" className="w-56" side="right">
              <div className="px-3 py-2.5">
                <p className="text-sm font-bold text-[var(--text-primary)]">{displayName}</p>
                <span className="inline-block mt-1 text-2xs font-bold px-1.5 py-0.5 rounded bg-[var(--text-primary)] text-[var(--surface)]">
                  {role}
                </span>
              </div>
              <DropdownMenuSeparator />
              <DropdownMenuItem
                className="gap-2 text-xs font-semibold cursor-pointer"
                onClick={() => {
                  if (auth.settings.authority) {
                    window.open(`${auth.settings.authority}/account/?kc_locale=${activeLocale}`, '_blank');
                  }
                }}
              >
                <IconUserCircle size={14} /> {t.topNav?.myAccount || 'My Account'}
              </DropdownMenuItem>
              <DropdownMenuItem
                className="gap-2 text-xs font-semibold text-[var(--danger)] focus:text-[var(--danger)] cursor-pointer"
                onClick={handleLogout}
              >
                <IconLogout size={14} /> {t.topNav?.logout || 'Logout'}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>

        {/* Collapse Toggle */}
        {!isMobile && (
          <button
            type="button"
            onClick={toggleSidebar}
            className={s['iconRail__toggle']}
            aria-label={isCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            {isCollapsed ? <IconChevronsRight size={16} /> : <IconChevronsLeft size={16} />}
          </button>
        )}
      </aside>

      {/* Navigation Sidebar */}
      <aside className={cn(s.navPanel, isCollapsed && s['navPanel__collapsed'], isMobile && isOpen && s['navPanel__mobileOpen'])}>
        {/* Header */}
        <div className={s['navPanel__header']}>
          <span className={s['navPanel__title']}>{getCurrentPageTitle()}</span>
          {!isMobile && (
            <button
              type="button"
              onClick={toggleSidebar}
              className={s['navPanel__collapseBtn']}
              aria-label="Collapse sidebar"
            >
              <IconChevronsLeft size={16} />
            </button>
          )}
        </div>

        {/* Navigation Content */}
        <nav className={s['navPanel__content']}>
          {groups.map((group) => {
            const visibleItems = group.items.filter(item => !item.perm || hasPermReactive(item.perm as Perm));
            if (visibleItems.length === 0) return null;

            const isGroupExpanded = activeGroup === group.labelKey;

            return (
              <div key={group.labelKey} className={s.navPanel__group}>
                {/* Group Header */}
                <button
                  type="button"
                  className={cn(s['navPanel__groupHeader'], isGroupExpanded && s['navPanel__groupHeader--active'])}
                  onClick={() => setActiveGroup(group.labelKey)}
                  aria-expanded={isGroupExpanded}
                >
                  <div className="flex items-center gap-2 min-w-0">
                    <group.Icon size={14} className="text-[var(--panel-text-muted)] opacity-70 shrink-0" />
                    <span className={s['navPanel__groupLabel']}>{group.label}</span>
                  </div>
                  <div className="flex items-center gap-1 shrink-0">
                    {isGroupExpanded ? (
                      <IconChevronDown size={12} stroke={2.5} className="text-[var(--panel-text-muted)] opacity-60" />
                    ) : (
                      <IconChevronRight size={12} stroke={2.5} className="text-[var(--panel-text-muted)] opacity-60" />
                    )}
                  </div>
                </button>

                {/* Group Items */}
                <div className={cn(s['navPanel__items'], !isGroupExpanded && s['navPanel__items--collapsed'])}>
                  {visibleItems.map((item) => {
                    const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
                    const badge = getBadgeFor(item.labelKey);

                    return (
                      <Link
                        key={item.href}
                        to={item.href}
                        className={cn(
                          s['navPanel__item'],
                          s['navPanel__item--child'],
                          active && s['navPanel__item--child-active'],
                          active && s['navPanel__item--active']
                        )}
                      >
                        <span className={s['navPanel__itemIcon']}>
                          <item.Icon size={14} stroke={1.5} />
                        </span>
                        <span className={s['navPanel__itemLabel']}>{item.label}</span>
                        
                        {badge && (
                          <span className={cn(
                            s['navPanel__badge'],
                            badge.type === 'alert' && s['navPanel__badge--alert'],
                            badge.type === 'info' && s['navPanel__badge--info'],
                            badge.type === 'neutral' && s['navPanel__badge--neutral']
                          )}>
                            {badge.text}
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
          {hasPermReactive('perm:settings:manage') && (
            <div className={s.navPanel__group}>
              <button
                type="button"
                className={cn(s['navPanel__groupHeader'], activeGroup === 'settings' && s['navPanel__groupHeader--active'])}
                onClick={() => setActiveGroup('settings')}
                aria-expanded={activeGroup === 'settings'}
              >
                <div className="flex items-center gap-2 min-w-0">
                  <IconSettings size={14} className="text-[var(--panel-text-muted)] opacity-70 shrink-0" />
                  <span className={s['navPanel__groupLabel']}>{tlabel(t.sidebar.items, 'settings') || 'Settings'}</span>
                </div>
                <div className="flex items-center gap-1 shrink-0">
                  {activeGroup === 'settings' ? (
                    <IconChevronDown size={12} stroke={2.5} className="text-[var(--panel-text-muted)] opacity-60" />
                  ) : (
                    <IconChevronRight size={12} stroke={2.5} className="text-[var(--panel-text-muted)] opacity-60" />
                  )}
                </div>
              </button>
              <div className={cn(s['navPanel__items'], activeGroup !== 'settings' && s['navPanel__items--collapsed'])}>
                <Link
                  to="/settings"
                  className={cn(
                    s['navPanel__item'],
                    s['navPanel__item--child'],
                    pathname === '/settings' && s['navPanel__item--child-active'],
                    pathname === '/settings' && s['navPanel__item--active']
                  )}
                >
                  <span className={s['navPanel__itemIcon']}>
                    <IconSettings size={14} stroke={1.5} />
                  </span>
                  <span className={s['navPanel__itemLabel']}>{t.settingsPage.generalConfig || 'Général'}</span>
                </Link>
                <Link
                  to="/settings/erp"
                  className={cn(
                    s['navPanel__item'],
                    s['navPanel__item--child'],
                    pathname === '/settings/erp' && s['navPanel__item--child-active'],
                    pathname === '/settings/erp' && s['navPanel__item--active']
                  )}
                >
                  <span className={s['navPanel__itemIcon']}>
                    <IconDatabase size={14} stroke={1.5} />
                  </span>
                  <span className={s['navPanel__itemLabel']}>{tlabel(t.sidebar.items, 'erpIntegration') || 'Intégration ERP'}</span>
                </Link>
              </div>
            </div>
          )}
        </nav>
      </aside>
    </>
  );
}