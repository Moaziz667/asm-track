import { useLocation, useNavigate } from 'react-router-dom';
import { Link } from 'react-router-dom';
import {
  IconSettings, IconCalendarEvent, IconChevronDown,
} from '@tabler/icons-react';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useBreadcrumb } from '@/lib/ui/breadcrumb';
import { GROUP_DEFS } from './Sidebar';
import { useT } from '@/lib/i18n/LocaleContext';

type CrumbInfo = {
  groupKey: string;
  labelKey: string;
  href: string;
  Icon: React.ComponentType<{ size?: number }>;
};

const EXTRA_PAGES: CrumbInfo[] = [
  { groupKey: 'operations', labelKey: 'overview',       href: '/schedule',      Icon: IconCalendarEvent },
  { groupKey: 'settings',   labelKey: 'generalConfig',  href: '/settings',      Icon: IconSettings },
  { groupKey: 'settings',   labelKey: 'erpIntegration', href: '/settings/erp',  Icon: IconSettings },
];

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

export default function ContentBreadcrumb() {
  const t = useT();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const { trail } = useBreadcrumb();
  const seg = pathname.split('/').filter(Boolean)[0] ?? '';

  const groups = (t.sidebar?.groups ?? {}) as Record<string, string>;
  const items = (t.sidebar?.items ?? {}) as Record<string, string>;
  const tx = (dict: Record<string, string>, key: string, fallback: string) => dict[key] || fallback;

  const current =
    PAGE_BY_HREF[pathname] ??
    PAGE_BY_HREF[`/${seg}`] ??
    (trail[0]?.href ? PAGE_BY_HREF[trail[0].href] : undefined);

  const groupLabel = current
    ? (groups[current.groupKey] || items[current.groupKey] || '')
    : '';
  const pageLabel = current
    ? tx(items, current.labelKey, seg.replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase()))
    : (trail[0]?.label ?? seg.replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase()));
  const PageIcon = current?.Icon;

  const detailCrumbs = trail.length > 1 ? trail.slice(1) : [];
  const siblings = current ? (GROUP_DEFS.find(g => g.groupKey === current.groupKey)?.items ?? []) : [];

  const sep = (
    <span className="px-2 text-[var(--text-muted)] flex items-center font-medium">/</span>
  );

  return (
    <div className="flex items-center gap-0 min-w-0 flex-nowrap text-sm">
      {groupLabel && (
        <>
          <span className="flex items-center gap-1.5 font-medium text-[var(--text-muted)] whitespace-nowrap">
            {PageIcon && <PageIcon size={14} />}
            {groupLabel}
          </span>
          {sep}
        </>
      )}

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
                  {!groupLabel && PageIcon && <PageIcon size={14} />}
                  {pageLabel}
                </h1>
                <IconChevronDown size={12} className="text-[var(--text-soft)] group-hover:text-[var(--text-muted)] transition-colors" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-52">
              {siblings.map((it) => {
                const active = it.href === current?.href;
                const ItemIcon = it.Icon;
                return (
                  <DropdownMenuItem
                    key={it.href}
                    className="gap-2 text-sm font-semibold cursor-pointer"
                    style={active ? { color: 'var(--brand)', background: 'var(--brand-soft)' } : undefined}
                    onClick={() => navigate(it.href)}
                  >
                    <ItemIcon size={16} /> {tx(items, it.labelKey, it.labelKey)}
                  </DropdownMenuItem>
                );
              })}
            </DropdownMenuContent>
          </DropdownMenu>
        ) : (
          <h1 className="flex items-center gap-1.5 text-sm font-semibold text-[var(--text-primary)] whitespace-nowrap truncate m-0">
            {!groupLabel && PageIcon && <PageIcon size={14} />}
            {pageLabel}
          </h1>
        )
      ) : (
        <Link to={current?.href ?? '#'} className="flex items-center gap-1.5 font-medium text-[var(--text-muted)] whitespace-nowrap hover:text-[var(--text-primary)] transition-colors no-underline">
          {!groupLabel && PageIcon && <PageIcon size={14} />}
          {pageLabel}
        </Link>
      )}

      {detailCrumbs.map((item, idx) => {
        const isLast = idx === detailCrumbs.length - 1;
        return (
          <div key={idx} className="flex items-center gap-0 min-w-0 flex-nowrap">
            {sep}
            {item.href && !isLast ? (
              <Link to={item.href} className="font-medium text-[var(--text-muted)] whitespace-nowrap hover:text-[var(--text-primary)] transition-colors no-underline">
                {item.label}
              </Link>
            ) : (
              <span className="font-bold font-semibold text-[var(--text-primary)] whitespace-nowrap truncate">
                {item.label}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}