

import { useLocation } from 'react-router-dom';
import { useState, useEffect, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { safeStorage } from '@/lib/storage';
import { useAuth } from 'react-oidc-context';

import {
  IconChevronRight, IconChevronDown, IconUserCircle, IconLogout,
  IconSun, IconMoon, IconLayoutDashboard, IconCommand,
  IconCalendarEvent, IconPackage, IconUpload, IconRoute, IconGitBranch,
  IconUsers, IconTruck, IconBuildingWarehouse, IconMap2, IconChartLine,
  IconFileText, IconSettings, IconAlertTriangle, IconBell, IconChartBar,
} from '@tabler/icons-react';
import { IconLayoutSidebar } from '@tabler/icons-react';
import { SidebarTrigger } from '@/components/ui/sidebar';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import AlertBell from './AlertBell';
import GlobalSearch from './GlobalSearch';
import { getCurrentRole, getCurrentUser } from '@/lib/auth';
import { AdminRole, AdminUser } from '@/types';
import { useBreadcrumb } from '@/lib/breadcrumb';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import adminLogo from '../../icons/adminlogo.jpg';
import LanguageSelector from './LanguageSelector';

type PageEntry = { label: string; icon: ReactNode; href: string };

const PAGE_MAP: Record<string, PageEntry> = {
  dashboard:       { label: 'Tableau de bord',     icon: <IconLayoutDashboard size={13} />, href: '/dashboard' },
  operations:      { label: "Planning",       icon: <IconCalendarEvent size={13} />,         href: '/schedule' },
  'dispatch-desk': { label: 'Dispatch',             icon: <IconCommand size={13} />,        href: '/dispatch-desk' },
  deliveries:      { label: 'Suivi des livraisons', icon: <IconPackage size={13} />,        href: '/deliveries' },
  import:          { label: 'Importation',          icon: <IconUpload size={13} />,         href: '/import' },
  'route-builder': { label: 'Créer une tournée',    icon: <IconGitBranch size={13} />,      href: '/route-builder' },
  'routes-table':  { label: 'Tournées',             icon: <IconRoute size={13} />,          href: '/routes-table' },
  routes:          { label: 'Tournées',             icon: <IconRoute size={13} />,          href: '/routes-table' },
  drivers:         { label: 'Chauffeurs',           icon: <IconUsers size={13} />,          href: '/drivers' },
  vehicles:        { label: 'Véhicules',            icon: <IconTruck size={13} />,          href: '/vehicles' },
  depots:          { label: 'Dépôts',               icon: <IconBuildingWarehouse size={13} />, href: '/depots' },
  zones:           { label: 'Zones',                icon: <IconMap2 size={13} />,           href: '/zones' },
  performance:     { label: 'Performance',          icon: <IconChartLine size={13} />,      href: '/performance' },
  'audit-logs':    { label: "Journal d'audit",      icon: <IconFileText size={13} />,       href: '/audit-logs' },
  settings:        { label: 'Paramètres',           icon: <IconSettings size={13} />,       href: '/settings' },
  exceptions:      { label: 'Exceptions',           icon: <IconAlertTriangle size={13} />,  href: '/exceptions' },
  notifications:   { label: 'Notifications',        icon: <IconBell size={13} />,           href: '/notifications' },
  reports:         { label: 'Rapports',             icon: <IconChartBar size={13} />,       href: '/reports' },
};

function Breadcrumb({ t }: { t: any }) {
  const { pathname } = useLocation();
  const { trail } = useBreadcrumb();
  const segments = pathname.split('/').filter(Boolean);
  const section = PAGE_MAP[segments[0]];

  const getPageLabel = (seg: string, fallback: string) => {
    const pagesDict = t.pages as any;
    const camelKey = seg.replace(/-([a-z])/g, (g) => g[1].toUpperCase());
    const keyMap: Record<string, string> = {
      'dispatchDesk': 'dispatch',
      'routesTable': 'routes',
    };
    const key = keyMap[camelKey] || camelKey || seg;
    return pagesDict[key]?.title || pagesDict[seg]?.title || fallback;
  };

  // Only use trail if it has multiple items (means it's a detail page breadcrumb)
  if (trail.length > 1) {
    return (
      <div className="flex items-center gap-0 min-w-0 flex-nowrap">
        {trail.map((item, idx) => {
          const isLast = idx === trail.length - 1;
          const isFirst = idx === 0;
          const sectionEntry = isFirst ? PAGE_MAP[segments[0]] : null;
          const currentLabel = isFirst && sectionEntry
            ? getPageLabel(segments[0], sectionEntry.label)
            : item.label;

          return (
            <div key={idx} className="flex items-center gap-0 min-w-0 flex-nowrap">
              {isFirst && sectionEntry && (
                <span className="text-[var(--text-muted)] flex items-center mr-1.5">{sectionEntry.icon}</span>
              )}
              {item.href && !isLast ? (
                <Link to={item.href} className="text-[12px] font-medium text-[var(--text-muted)] whitespace-nowrap hover:text-[var(--text-primary)] transition-colors no-underline">
                  {currentLabel}
                </Link>
              ) : (
                <span className={`text-[12px] whitespace-nowrap truncate ${isLast ? 'font-bold text-[var(--text-primary)] font-semibold' : 'font-medium text-[var(--text-muted)]'}`}>
                  {currentLabel}
                </span>
              )}
              {!isLast && (
                <span className="px-1.5 text-[var(--border-strong)] flex items-center">
                  <IconChevronRight size={10} />
                </span>
              )}
            </div>
          );
        })}
      </div>
    );
  }

  if (!section) {
    const defaultLabel = segments[0] ? segments[0].charAt(0).toUpperCase() + segments[0].slice(1) : t.breadcrumbs.home;
    return <span className="text-[12px] font-bold text-[var(--text-primary)] font-semibold">{getPageLabel(segments[0] || '', defaultLabel)}</span>;
  }

  return (
    <div className="flex items-center gap-1.5 flex-nowrap min-w-0">
      <span className="text-[var(--text-muted)] flex items-center">{section.icon}</span>
      <span className="text-[12px] font-bold text-[var(--text-primary)] whitespace-nowrap font-semibold">{getPageLabel(segments[0], section.label)}</span>
    </div>
  );
}

export default function TopNav({ onMenuClick: _onMenuClick }: { onMenuClick?: () => void }) {
  const t = useT();
  const auth = useAuth();
  const [role, setRole] = useState<AdminRole>('UNKNOWN');
  const [user, setUser] = useState<AdminUser | null>(null);
  const [isClient, setIsClient] = useState(false);
  const [isDark, setIsDark] = useState(false);
  const { locale: activeLocale, setLocale } = useLocaleStore();

  useEffect(() => {
    setIsClient(true);
    setRole(getCurrentRole());
    setUser(getCurrentUser());
    setIsDark(document.documentElement.classList.contains('dark'));

    const handleStorage = (e: StorageEvent) => {
      if (e.key === 'admin-color-scheme' && e.newValue) {
        const isNextDark = e.newValue === 'dark';
        setIsDark(isNextDark);
        document.documentElement.classList.toggle('dark', isNextDark);
        document.documentElement.setAttribute('data-mantine-color-scheme', e.newValue);
        document.cookie = `asm-theme=${e.newValue}; path=/; max-age=31536000; SameSite=Strict`;
      }
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, []);

  const toggleDark = () => {
    const next = !isDark;
    setIsDark(next);
    const themeVal = next ? 'dark' : 'light';
    document.documentElement.classList.toggle('dark', next);
    document.documentElement.setAttribute('data-mantine-color-scheme', themeVal);
    safeStorage.setItem('admin-color-scheme', themeVal);
    document.cookie = `asm-theme=${themeVal}; path=/; max-age=31536000; SameSite=Strict`;
  };

  const displayName = isClient
    ? user?.name || (role !== 'UNKNOWN' ? role : t.topNav.user)
    : t.topNav.user;

  const handleLogout = async () => {
    safeStorage.removeItem('admin-operational-filters');
    try {
      await auth.removeUser();
      await auth.signoutRedirect();
    } catch {
      window.location.href = '/';
    }
  };

  return (
    <header className="sticky top-0 shrink-0 border-b border-[var(--border)] bg-[var(--surface)]/75 backdrop-blur-md z-40 h-14 flex items-center gap-4 md:gap-6 px-4 md:px-6 relative">
      {/* Sidebar toggle */}
      <SidebarTrigger className="-ml-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)]" />

      {/* Breadcrumb */}
      <div className="hidden sm:flex shrink-0 min-w-0">
        <Breadcrumb t={t} />
      </div>

      {/* Search — absolutely centered in the header */}
      <div className="absolute left-1/2 -translate-x-1/2 hidden md:block">
        <GlobalSearch />
      </div>

      {/* Spacer to push right actions to the right */}
      <div className="flex-1" />

      {/* Right actions */}
      <div className="flex items-center gap-3 shrink-0">
        <AlertBell />

        {/* Language Selector Dropdown */}
        {isClient && <LanguageSelector />}

        {isClient && (
          <Tooltip>
            <TooltipTrigger
              render={
                <button
                  type="button"
                  onClick={toggleDark}
                  className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
                  aria-label={isDark ? t.topNav.enableLightMode : t.topNav.enableDarkMode}
                />
              }
            >
              {isDark ? <IconSun size={14} stroke={2.5} /> : <IconMoon size={14} stroke={2.5} />}
            </TooltipTrigger>
            <TooltipContent>{isDark ? t.topNav.lightMode : t.topNav.darkMode}</TooltipContent>
          </Tooltip>
        )}

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              className="flex items-center gap-2 px-2 py-1.5 rounded border border-transparent hover:border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors"
            >
              <div className="relative w-6 h-6 overflow-hidden rounded-sm border border-[var(--border)] bg-white shrink-0">
                <img
                  src={adminLogo}
                  alt="Admin logo"
                  width={24} height={24}
                  className="object-contain"
                />
              </div>
              <span className="hidden md:block text-[12px] font-bold text-[var(--text-secondary)] max-w-[120px] truncate">
                {displayName}
              </span>
              <IconChevronDown size={10} className="text-[var(--text-soft)]" />
            </button>
          </DropdownMenuTrigger>

          <DropdownMenuContent align="end" className="w-56">
            <div className="px-3 py-2.5">
              <p className="text-sm font-bold text-[var(--text-primary)]">{displayName}</p>
              <span className="inline-block mt-1 text-[10px] font-bold px-1.5 py-0.5 rounded bg-[var(--text-primary)] text-[var(--surface)]">
                {role}
              </span>
            </div>
            <DropdownMenuSeparator />
            <DropdownMenuItem 
              className="gap-2 text-xs font-semibold cursor-pointer"
              onClick={() => {
                if (auth.settings.authority) {
                  window.open(`${auth.settings.authority}/account/`, '_blank');
                }
              }}
            >
              <IconUserCircle size={14} /> {t.topNav.myAccount}
            </DropdownMenuItem>
            <DropdownMenuItem
              className="gap-2 text-xs font-semibold text-[var(--danger)] focus:text-[var(--danger)] cursor-pointer"
              onClick={handleLogout}
            >
              <IconLogout size={14} /> {t.topNav.logout}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  );
}

