

import type { TranslationSchema } from '@/lib/i18n/LocaleContext';
import { useLocation, useNavigate } from 'react-router-dom';
import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { safeStorage } from '@/lib/storage';
import { useAuth } from 'react-oidc-context';

import {
  IconSettings, IconMap2,
  IconCalendarEvent, IconChevronRight, IconSun, IconMoon,
  IconChevronDown, IconUserCircle, IconLogout } from '@tabler/icons-react';
import { useGlobalMapStore } from '@/lib/state/global-map-store';
import { SidebarTrigger } from '@/components/ui/sidebar';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import AlertBell from './AlertBell';
import GlobalSearch from './GlobalSearch';
import { getCurrentRole } from '@/lib/api/auth';
import { useCurrentUser } from '@/hooks/useCurrentUser';
import { AdminRole } from '@/types';
import { useBreadcrumb } from '@/lib/ui/breadcrumb';
import { GROUP_DEFS } from './Sidebar';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/i18n/LocaleContext';
import LanguageSelector from './LanguageSelector';

// Breadcrumbs are derived from GROUP_DEFS — the SAME structure the sidebar renders — so
// "Group › Page" always matches the nav and translates in all 3 languages. The only thing
// not in GROUP_DEFS is the bottom Settings group and a couple of routes (schedule alias,
// notifications); those are patched in via EXTRA_PAGES below. Nothing is hardcoded in a
// language here: labels resolve through t.sidebar.groups / t.sidebar.items.

type CrumbInfo = {
  groupKey: string;          // → t.sidebar.groups[groupKey]
  labelKey: string;          // → t.sidebar.items[labelKey] (page name)
  href: string;
  Icon: React.ComponentType<{ size?: number }>;
};

// Routes the sidebar doesn't list as primary nav items but that still need a breadcrumb.
// The Settings section in the sidebar is titled "Paramètres" (t.sidebar.items.settings), with
// its sub-pages "Configuration Générale" and "Intégration ERP" — the breadcrumb mirrors that:
// Paramètres › <sub-page>. We reuse the *items* dict for the group label by pointing groupKey
// at 'settings' and resolving the group through items when there's no matching groups[] entry.
const EXTRA_PAGES: CrumbInfo[] = [
  { groupKey: 'operations', labelKey: 'overview',       href: '/schedule',      Icon: IconCalendarEvent },
  { groupKey: 'settings',   labelKey: 'generalConfig',  href: '/settings',      Icon: IconSettings },
  { groupKey: 'settings',   labelKey: 'erpIntegration', href: '/settings/erp',  Icon: IconSettings },
];

// Flat href → {group, page} lookup, built once from the nav definition + extras.
const PAGE_BY_HREF: Record<string, CrumbInfo> = (() => {
  const map: Record<string, CrumbInfo> = {};
  for (const group of GROUP_DEFS) {
    for (const item of group.items) {
      map[item.href] = { groupKey: group.groupKey, labelKey: item.labelKey, href: item.href, Icon: item.Icon };
    }
  }
  for (const p of EXTRA_PAGES) map[p.href] ??= p;
  return map;
})();

function Breadcrumb({ t }: { t: TranslationSchema }) {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const { trail } = useBreadcrumb();
  const seg = pathname.split('/').filter(Boolean)[0] ?? '';

  const groups = (t.sidebar?.groups ?? {}) as Record<string, string>;
  const items = (t.sidebar?.items ?? {}) as Record<string, string>;
  const tx = (dict: Record<string, string>, key: string, fallback: string) => dict[key] || fallback;

  // The page the current URL belongs to. Try the FULL path first (so /settings/erp resolves to
  // the ERP sub-page, not /settings), then the base segment, then the page-supplied trail href.
  const current =
    PAGE_BY_HREF[pathname] ??
    PAGE_BY_HREF[`/${seg}`] ??
    (trail[0]?.href ? PAGE_BY_HREF[trail[0].href] : undefined);

  // Group label: most groups live in t.sidebar.groups, but the Settings section's title lives in
  // t.sidebar.items.settings — fall back to items so "Paramètres" resolves.
  const groupLabel = current
    ? (groups[current.groupKey] || items[current.groupKey] || '')
    : '';
  const pageLabel = current
    ? tx(items, current.labelKey, seg.replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase()))
    : (trail[0]?.label ?? seg.replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase()));
  const PageIcon = current?.Icon;

  // Extra crumbs the page appended after its own name (e.g. the route name on a detail
  // page). trail[0] is the page itself (already represented by pageLabel), so we take the
  // rest. Each keeps the page-supplied, already-localized label.
  const detailCrumbs = trail.length > 1 ? trail.slice(1) : [];

  // Sibling pages in the current group → the page crumb becomes a quick page-switcher dropdown
  // (matches the reference's "Section ⌄ / Page ⌄" breadcrumb). Only when there's more than one.
  const siblings = current ? (GROUP_DEFS.find(g => g.groupKey === current.groupKey)?.items ?? []) : [];

  const sep = (
    <span className="px-1.5 text-[var(--border-strong)] flex items-center">
      <IconChevronRight size={10} />
    </span>
  );

  return (
    <div className="flex items-center gap-0 min-w-0 flex-nowrap">
      {/* Group — plain muted text, not a link (groups have no landing page) */}
      {groupLabel && (
        <>
          <span className="flex items-center gap-1.5 text-sm font-medium text-[var(--text-muted)] whitespace-nowrap">
            {PageIcon && <PageIcon size={13} />}
            {groupLabel}
          </span>
          {sep}
        </>
      )}

      {/* Page — bold/active when it's the last crumb, else a link to itself. The active page
          title is the document's <h1> so every list page has a top-level heading (WCAG /
          axe page-has-heading-one); the inline size keeps it visually a breadcrumb. */}
      {detailCrumbs.length === 0 ? (
        siblings.length > 1 ? (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                className="group flex items-center gap-1.5 rounded-md px-1.5 py-0.5 -mx-1.5 hover:bg-[var(--hover-bg)] transition-colors outline-none"
                aria-label={pageLabel}
              >
                <h1 className="flex items-center gap-1.5 text-sm font-semibold text-[var(--text-primary)] whitespace-nowrap truncate m-0">
                  {!groupLabel && PageIcon && <PageIcon size={13} />}
                  {pageLabel}
                </h1>
                <IconChevronDown size={11} className="text-[var(--text-soft)] group-hover:text-[var(--text-muted)] transition-colors" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-52">
              {siblings.map((it) => {
                const active = it.href === current?.href;
                const ItemIcon = it.Icon;
                return (
                  <DropdownMenuItem
                    key={it.href}
                    className="gap-2 text-xs font-semibold cursor-pointer"
                    style={active ? { color: 'var(--brand)', background: 'var(--brand-soft)' } : undefined}
                    onClick={() => navigate(it.href)}
                  >
                    <ItemIcon size={14} /> {tx(items, it.labelKey, it.labelKey)}
                  </DropdownMenuItem>
                );
              })}
            </DropdownMenuContent>
          </DropdownMenu>
        ) : (
          <h1 className="flex items-center gap-1.5 text-sm font-semibold text-[var(--text-primary)] whitespace-nowrap truncate m-0">
            {!groupLabel && PageIcon && <PageIcon size={13} />}
            {pageLabel}
          </h1>
        )
      ) : (
        <Link to={current?.href ?? '#'} className="flex items-center gap-1.5 text-sm font-medium text-[var(--text-muted)] whitespace-nowrap hover:text-[var(--text-primary)] transition-colors no-underline">
          {!groupLabel && PageIcon && <PageIcon size={13} />}
          {pageLabel}
        </Link>
      )}

      {/* Detail crumbs (route name, order ref…) — page-supplied, already localized */}
      {detailCrumbs.map((item, idx) => {
        const isLast = idx === detailCrumbs.length - 1;
        return (
          <div key={idx} className="flex items-center gap-0 min-w-0 flex-nowrap">
            {sep}
            {item.href && !isLast ? (
              <Link to={item.href} className="text-sm font-medium text-[var(--text-muted)] whitespace-nowrap hover:text-[var(--text-primary)] transition-colors no-underline">
                {item.label}
              </Link>
            ) : (
              <span className="text-sm font-bold font-semibold text-[var(--text-primary)] whitespace-nowrap truncate">
                {item.label}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}

export default function TopNav({ onMenuClick: _onMenuClick }: { onMenuClick?: () => void }) {
  const t = useT();
  const auth = useAuth();
  const [role, setRole] = useState<AdminRole>('UNKNOWN');
  // Identity from /api/admin/me, which reads the name LIVE from Keycloak (the master). React Query
  // refetches on window focus (staleTime 0), so a name self-edited in "Mon compte" shows the moment
  // the user returns to the app tab — no manual re-login.
  const { data: user } = useCurrentUser();
  const [isClient, setIsClient] = useState(false);
  const [isDark, setIsDark] = useState(false);
  const { locale: activeLocale } = useLocaleStore();

  const { mapMode, setMapMode } = useGlobalMapStore();
  const toggleMap = () => setMapMode(mapMode === 'hidden' ? 'collapsed' : 'hidden');

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

  const toggleDark = () => {
    const next = !isDark;
    setIsDark(next);
    const themeVal = next ? 'dark' : 'light';
    document.documentElement.classList.toggle('dark', next);
    document.documentElement.setAttribute('data-mantine-color-scheme', themeVal);
    safeStorage.setItem('admin-color-scheme', themeVal);
    document.cookie = `asm-theme=${themeVal}; path=/; domain=localhost; max-age=31536000; SameSite=Strict`;
  };

  const displayName = isClient
    ? user?.name || (role !== 'UNKNOWN' ? role : t.topNav.user)
    : t.topNav.user;

  const handleLogout = async () => {
    safeStorage.removeItem('admin-operational-filters');
    // Capture the id_token BEFORE clearing the user: passing it as id_token_hint lets Keycloak skip
    // its (unstyled) logout-confirmation prompt and log out + redirect straight to /login.
    const id_token_hint = auth.user?.id_token;
    try {
      await auth.removeUser();
      await auth.signoutRedirect(id_token_hint ? { id_token_hint } : undefined);
    } catch {
      window.location.href = '/';
    }
  };

  return (
    <header className="sticky top-0 shrink-0 border-b border-[var(--border)] bg-[var(--surface)]/75 backdrop-blur-md z-40 h-14 flex items-center gap-4 md:gap-6 px-4 md:px-6 relative">
      {/* Sidebar toggle */}
      <SidebarTrigger aria-label={t.topNav?.toggleSidebar ?? 'Toggle sidebar'} className="-ml-1 text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--hover-bg)]" />

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

        {isClient && (
          <Tooltip>
            <TooltipTrigger
              render={
                <button
                  type="button"
                  onClick={toggleMap}
                  className="w-7 h-7 flex items-center justify-center rounded border transition-colors"
                  style={{
                    background: mapMode !== 'hidden' ? 'var(--brand-bg)' : 'transparent',
                    borderColor: mapMode !== 'hidden' ? 'var(--brand)' : 'var(--border)',
                    color: mapMode !== 'hidden' ? 'var(--brand)' : 'var(--text-muted)',
                  }}
                  aria-label={mapMode !== 'hidden' ? t.topNav.hideMap : t.topNav.showMap}
                />
              }
            >
              <IconMap2 size={14} stroke={2.5} />
            </TooltipTrigger>
            <TooltipContent>{mapMode !== 'hidden' ? t.topNav.hideMap : t.topNav.showMap}</TooltipContent>
          </Tooltip>
        )}


        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              className="flex items-center gap-2 px-2 py-1.5 rounded border border-transparent hover:border-[var(--border)] hover:bg-[var(--hover-bg)] transition-colors"
            >
              <span className="text-sm font-bold text-[var(--text-secondary)] max-w-[120px] truncate">
                {displayName}
              </span>
              <IconChevronDown size={10} className="text-[var(--text-soft)]" />
            </button>
          </DropdownMenuTrigger>

          <DropdownMenuContent align="end" className="w-56">
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
                  // Force the account console into the app's current language → no FR/EN/AR mismatch.
                  window.open(`${auth.settings.authority}/account/?kc_locale=${activeLocale}`, '_blank');
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

