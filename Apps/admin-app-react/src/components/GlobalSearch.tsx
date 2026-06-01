

import { useEffect, useMemo, useRef, useState, useTransition, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate as useRouter } from 'react-router-dom';
import {
  IconSearch, IconLayoutDashboard, IconAdjustments, IconMap2, IconTruck, IconUpload,
  IconRoute, IconTable, IconUser, IconCar, IconBuildingWarehouse, IconMapPin,
  IconChartBar, IconClipboardList, IconSettings, IconFileText,
  IconPackage, IconRefresh, IconSun, IconPlus,
} from '@tabler/icons-react'
import { api } from '@/lib/api'
import StatusBadge from '@/components/StatusBadge'
import { cn } from '@/lib/utils'
import { useLocaleStore } from '@/lib/i18n'
import { useT } from '@/lib/LocaleContext'
import { safeStorage } from '@/lib/storage'

// ── Types ─────────────────────────────────────────────────────────────────────

interface SearchAction {
  id: string
  label: string
  description?: string
  group: string
  icon: React.ReactNode
  badge?: React.ReactNode
  onClick: () => void
}

interface SearchResult {
  deliveries: Array<{ deliveryId: string; erpOrderId: string; clientName: string; clientPhone: string; dropoffCity: string; status: string }>
  routes:     Array<{ routeId: string; name: string; status: string; date: string }>
  vehicles:   Array<{ vehicleId: string; plate: string; make: string; model: string; vehicleStatus: string }>
  zones:      Array<{ zoneId: string; name: string; description: string }>
  depots:     Array<{ depotId: string; name: string; address: string }>
  drivers:    Array<{ driverId: string; name: string; phone: string }>
}

interface RecentItem {
  id: string
  label: string
  description?: string
  group: string
  path: string
  iconType: string
  status?: string
}

// ── Status config ─────────────────────────────────────────────────────────────

function StatusPill({ label, color, bg }: { label: string; color: string; bg: string }) {
  return (
    <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded shrink-0 select-none" style={{ color, background: bg }}>
      {label}
    </span>
  )
}

// ── Nav page definitions (icons + paths, labels come from translations at runtime) ─

type NavPageDef = {
  id: string
  labelKey: string
  path: string
  Icon: React.ComponentType<{ size?: number; className?: string }>
}

const NAV_PAGE_DEFS: NavPageDef[] = [
  { id: 'nav-dashboard',     labelKey: 'dashboard',    path: '/dashboard',     Icon: IconLayoutDashboard },
  { id: 'nav-operations',    labelKey: 'overview',     path: '/operations',    Icon: IconAdjustments },
  { id: 'nav-dispatch',      labelKey: 'dispatch',     path: '/dispatch-desk', Icon: IconMap2 },
  { id: 'nav-deliveries',    labelKey: 'tracking',     path: '/deliveries',    Icon: IconTruck },
  { id: 'nav-import',        labelKey: 'import',       path: '/import',        Icon: IconUpload },
  { id: 'nav-route-builder', labelKey: 'createRoute',  path: '/route-builder', Icon: IconRoute },
  { id: 'nav-routes-table',  labelKey: 'routes',       path: '/routes-table',  Icon: IconTable },
  { id: 'nav-drivers',       labelKey: 'drivers',      path: '/drivers',       Icon: IconUser },
  { id: 'nav-vehicles',      labelKey: 'vehicles',     path: '/vehicles',      Icon: IconCar },
  { id: 'nav-depots',        labelKey: 'depots',       path: '/depots',        Icon: IconBuildingWarehouse },
  { id: 'nav-zones',         labelKey: 'zones',        path: '/zones',         Icon: IconMapPin },
  { id: 'nav-performance',   labelKey: 'performance',  path: '/performance',   Icon: IconChartBar },
  { id: 'nav-audit-logs',    labelKey: 'audit',        path: '/audit-logs',    Icon: IconClipboardList },
  { id: 'nav-settings',      labelKey: 'settings',     path: '/settings',      Icon: IconSettings },
]

// ── Highlight ─────────────────────────────────────────────────────────────────

function Highlight({ text, query }: { text: string; query: string }) {
  if (!text) return null
  if (!query) return <>{text}</>
  const idx = text.toLowerCase().indexOf(query.toLowerCase())
  if (idx === -1) return <>{text}</>
  return (
    <>
      {text.slice(0, idx)}
      <mark className="bg-amber-500/20 text-[var(--text-primary)] rounded-[2px] px-0.5">{text.slice(idx, idx + query.length)}</mark>
      {text.slice(idx + query.length)}
    </>
  )
}

function getIconComponent(type: string) {
  switch (type) {
    case 'd':   return <IconPackage size={14} />
    case 'r':   return <IconRoute size={14} />
    case 'dr':  return <IconUser size={14} />
    case 'v':   return <IconCar size={14} />
    case 'dep': return <IconBuildingWarehouse size={14} />
    case 'z':   return <IconMapPin size={14} />
    default:    return <IconPackage size={14} />
  }
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function GlobalSearch() {
  const t = useT()
  const router = useRouter()
  const { locale } = useLocaleStore()
  const [query, setQuery]     = useState('')
  const [isOpen, setIsOpen]   = useState(false)
  const [loading, setLoading] = useState(false)
  const [focused, setFocused] = useState(0)
  const [results, setResults] = useState<SearchResult | null>(null)
  const [recentItems, setRecentItems] = useState<RecentItem[]>([])
  const [isPending, startTransition] = useTransition()
  const [isMounted, setIsMounted] = useState(false)

  const inputRef    = useRef<HTMLInputElement>(null)
  const modalRef    = useRef<HTMLDivElement>(null)
  const fetchIdRef  = useRef(0)

  // Mount tracking for Portal SSR safety
  useEffect(() => {
    setIsMounted(true)
  }, [])

  // Load recent items from localStorage on mount
  useEffect(() => {
    const stored = safeStorage.getItem('asm-recent-searches')
    if (stored) {
      try {
        setRecentItems(JSON.parse(stored))
      } catch {
        setRecentItems([])
      }
    }
  }, [isOpen])

  function navigate(path: string) {
    startTransition(() => {
      router(path)
      setIsOpen(false)
      setQuery('')
      setFocused(0)
    })
  }

  const toggleDarkTheme = () => {
    const isDark = document.documentElement.classList.contains('dark')
    const next = !isDark
    document.documentElement.classList.toggle('dark', next)
    document.documentElement.setAttribute('data-mantine-color-scheme', next ? 'dark' : 'light')
    safeStorage.setItem('admin-color-scheme', next ? 'dark' : 'light')
    setIsOpen(false)
  }

  // Keyboard hooks for shortcut activation (⌘K, Ctrl+K, and /)
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
        e.preventDefault()
        setIsOpen(prev => !prev)
        setQuery('')
      } else if (e.key === '/' && document.activeElement?.tagName !== 'INPUT' && document.activeElement?.tagName !== 'TEXTAREA') {
        e.preventDefault()
        setIsOpen(true)
        setQuery('')
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [])

  // Auto-focus input when modal opens
  useEffect(() => {
    if (isOpen) {
      setTimeout(() => {
        inputRef.current?.focus()
      }, 50)
    }
  }, [isOpen])

  // Debounced API search call
  useEffect(() => {
    const q = query.trim()
    if (q.length < 2) {
      setResults(null)
      setLoading(false)
      return
    }
    setLoading(true)
    const id = ++fetchIdRef.current
    const timer = setTimeout(async () => {
      try {
        const res = await api.get<SearchResult>('/api/admin/search', { params: { q, limit: 6 } })
        if (fetchIdRef.current !== id) return
        setResults(res.data)
      } catch {
        if (fetchIdRef.current === id) setResults(null)
      } finally {
        if (fetchIdRef.current === id) setLoading(false)
      }
    }, 200)
    return () => clearTimeout(timer)
  }, [query])

  // Click outside to close
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (modalRef.current && !modalRef.current.contains(e.target as Node)) {
        setIsOpen(false)
      }
    }
    if (isOpen) {
      document.addEventListener('mousedown', handler)
    }
    return () => document.removeEventListener('mousedown', handler)
  }, [isOpen])

  // Save selected element to localStorage recent history list
  const trackRecentSelection = (action: { id: string; label: string; description?: string; group: string; path: string; iconType: string; status?: string }) => {
    if (!action.path || !action.iconType) return
    const stored = safeStorage.getItem('asm-recent-searches')
    let list: RecentItem[] = stored ? JSON.parse(stored) : []
    list = list.filter(item => item.id !== action.id)
    list.unshift({
      id: action.id,
      label: action.label,
      description: action.description,
      group: action.group,
      path: action.path,
      iconType: action.iconType,
      status: action.status,
    })
    list = list.slice(0, 5)
    safeStorage.setItem('asm-recent-searches', JSON.stringify(list))
  }

  // ── Reactive translations — re-computed on every locale change ───────────────

  // Static quick actions & system actions list — reactive to locale
  const systemActions = useMemo((): SearchAction[] => [
    {
      id: 'sys-theme',
      label: t.globalSearch.system.themeTitle,
      description: t.globalSearch.system.themeDesc,
      group: t.globalSearch.groups.systemActions,
      icon: <IconSun size={14} className="dark:hidden text-amber-500" />,
      badge: <kbd className="font-mono text-[9px] px-1 py-0.5 rounded border border-[var(--border)] bg-[var(--hover-bg)] select-none">⌘K</kbd>,
      onClick: toggleDarkTheme,
    },
    {
      id: 'sys-refresh',
      label: t.globalSearch.system.refreshTitle,
      description: t.globalSearch.system.refreshDesc,
      group: t.globalSearch.groups.systemActions,
      icon: <IconRefresh size={14} className="text-emerald-500" />,
      onClick: () => { window.location.reload() },
    },
    {
      id: 'sys-new-driver',
      label: t.globalSearch.system.driverTitle,
      description: t.globalSearch.system.driverDesc,
      group: t.globalSearch.groups.systemActions,
      icon: <IconPlus size={14} className="text-[var(--brand)]" />,
      onClick: () => navigate('/drivers?new=true'),
    },
  ], [t]) // eslint-disable-line react-hooks/exhaustive-deps

  // Quick navigation shortcuts list — reactive to locale
  const navActions = useMemo((): SearchAction[] =>
    NAV_PAGE_DEFS.map(({ id, labelKey, path, Icon }) => ({
      id,
      label: t.sidebar.items[labelKey as keyof typeof t.sidebar.items],
      group: t.globalSearch.groups.quickNav,
      icon: <Icon size={14} />,
      onClick: () => navigate(path),
    })),
    [t], // eslint-disable-line react-hooks/exhaustive-deps
  )

  // Generate list of items from search query results — reactive to locale
  const dynamicActions = useMemo((): SearchAction[] => {
    if (!results && !query.trim()) return []
    const actions: SearchAction[] = []

    for (const d of results?.deliveries ?? []) {
      const path = `/deliveries/${d.deliveryId}`
      actions.push({
        id: `d-${d.deliveryId}`,
        label: d.clientName ?? '—',
        description: [d.erpOrderId, d.clientPhone, d.dropoffCity].filter(Boolean).join(' · '),
        group: t.globalSearch.groups.deliveries,
        icon: <IconPackage size={14} />,
        badge: <StatusBadge status={d.status || 'PENDING'} size="sm" />,
        onClick: () => {
          trackRecentSelection({ id: `d-${d.deliveryId}`, label: d.clientName ?? '—', description: d.dropoffCity, group: t.globalSearch.groups.deliveries, path, iconType: 'd', status: d.status })
          navigate(path)
        },
      })
    }
    for (const r of results?.routes ?? []) {
      const path = `/routes/${r.routeId}`
      actions.push({
        id: `r-${r.routeId}`,
        label: r.name,
        description: r.date,
        group: t.globalSearch.groups.routes,
        icon: <IconRoute size={14} />,
        badge: <StatusBadge status={r.status || 'DRAFT'} size="sm" />,
        onClick: () => {
          trackRecentSelection({ id: `r-${r.routeId}`, label: r.name, description: r.date, group: t.globalSearch.groups.routes, path, iconType: 'r', status: r.status })
          navigate(path)
        },
      })
    }
    for (const d of results?.drivers ?? []) {
      const path = '/drivers'
      actions.push({
        id: `dr-${d.driverId}`,
        label: d.name ?? '—',
        description: d.phone ?? '',
        group: t.globalSearch.groups.drivers,
        icon: <IconUser size={14} />,
        badge: <StatusPill label={t.globalSearch.groups.drivers} color="#0891B2" bg="#ECFEFF" />,
        onClick: () => {
          trackRecentSelection({ id: `dr-${d.driverId}`, label: d.name ?? '—', description: d.phone, group: t.globalSearch.groups.drivers, path, iconType: 'dr' })
          navigate(path)
        },
      })
    }
    for (const v of results?.vehicles ?? []) {
      const path = '/vehicles'
      actions.push({
        id: `v-${v.vehicleId}`,
        label: v.plate,
        description: `${v.make ?? ''} ${v.model ?? ''}`.trim(),
        group: t.globalSearch.groups.vehicles,
        icon: <IconCar size={14} />,
        badge: <StatusBadge status={v.vehicleStatus || 'AVAILABLE'} size="sm" />,
        onClick: () => {
          trackRecentSelection({ id: `v-${v.vehicleId}`, label: v.plate, description: v.make, group: t.globalSearch.groups.vehicles, path, iconType: 'v', status: v.vehicleStatus })
          navigate(path)
        },
      })
    }
    for (const d of results?.depots ?? []) {
      const path = '/depots'
      actions.push({
        id: `dep-${d.depotId}`,
        label: d.name,
        description: d.address ?? '',
        group: t.globalSearch.groups.depots,
        icon: <IconBuildingWarehouse size={14} />,
        badge: <StatusPill label={t.globalSearch.groups.depots} color="#D97706" bg="#FFFBEB" />,
        onClick: () => {
          trackRecentSelection({ id: `dep-${d.depotId}`, label: d.name, description: d.address, group: t.globalSearch.groups.depots, path, iconType: 'dep' })
          navigate(path)
        },
      })
    }
    for (const z of results?.zones ?? []) {
      const path = '/zones'
      actions.push({
        id: `z-${z.zoneId}`,
        label: z.name,
        description: z.description ?? '',
        group: t.globalSearch.groups.zones,
        icon: <IconMapPin size={14} />,
        badge: <StatusPill label={t.globalSearch.groups.zones} color="#7C3AED" bg="#F5F3FF" />,
        onClick: () => {
          trackRecentSelection({ id: `z-${z.zoneId}`, label: z.name, description: z.description, group: t.globalSearch.groups.zones, path, iconType: 'z' })
          navigate(path)
        },
      })
    }
    return actions
  }, [results, query, t]) // eslint-disable-line react-hooks/exhaustive-deps

  // Generate structured Action list — reactive to locale
  const actions = useMemo((): SearchAction[] => {
    const q = query.trim().toLowerCase()
    if (!q) {
      const recents: SearchAction[] = recentItems.map(item => ({
        id: item.id,
        label: item.label,
        description: item.description,
        group: t.globalSearch.groups.recents,
        icon: getIconComponent(item.iconType),
        badge: item.status ? <StatusBadge status={item.status} size="sm" /> : undefined,
        onClick: () => navigate(item.path),
      }))
      return [...recents, ...systemActions, ...navActions]
    }
    if (q.length < 2) {
      return navActions.filter(a => a.label.toLowerCase().includes(q))
    }
    return dynamicActions
  }, [query, recentItems, navActions, systemActions, dynamicActions, t]) // eslint-disable-line react-hooks/exhaustive-deps

  // Grouped actions for category mapping
  const groups = useMemo(() => {
    const map = new Map<string, SearchAction[]>()
    for (const a of actions) {
      if (!map.has(a.group)) map.set(a.group, [])
      map.get(a.group)!.push(a)
    }
    return [...map.entries()]
  }, [actions])

  // Reset focus when query changes
  useEffect(() => {
    setFocused(0)
  }, [query])

  function handleKeyDown(e: React.KeyboardEvent) {
    if (!isOpen) return
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setFocused(f => Math.min(f + 1, actions.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setFocused(f => Math.max(f - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (actions[focused]) {
        actions[focused].onClick()
      }
    } else if (e.key === 'Escape') {
      e.preventDefault()
      setIsOpen(false)
    }
  }

  let idx = 0

  return (
    <>
      {/* Header static Omnibox trigger bar */}
      <div className="relative w-[380px] select-none">
        <span className="absolute left-3.5 top-1/2 -translate-y-1/2 flex items-center pointer-events-none text-[var(--text-muted)]">
          <IconSearch size={13} />
        </span>
        <button
          type="button"
          onClick={() => setIsOpen(true)}
          className="w-full h-9 pl-9 pr-3 text-left text-[12px] font-normal bg-[var(--app-bg)] border border-[var(--border)] rounded-full text-[var(--text-soft)] hover:border-[var(--brand)] hover:shadow-sm transition-all flex items-center cursor-pointer justify-between outline-none gap-2"
        >
          <span className="truncate text-[var(--text-soft)]/85">{t.globalSearch.triggerPlaceholder}</span>
          <kbd className="font-mono text-[9px] px-1.5 py-0.5 rounded-full border border-[var(--border)] bg-[var(--surface)] text-[var(--text-soft)] select-none leading-none shadow-[0_1px_0_rgba(0,0,0,0.05)] shrink-0">⌘K</kbd>
        </button>
      </div>

      {/* Floating Centered Command Palette Portal */}
      {isMounted && isOpen && createPortal(
        <div className="fixed inset-0 z-[9999] flex items-start justify-center pt-[12vh] p-4 select-none animate-in fade-in duration-100">

          {/* Backdrop Blur Layer */}
          <div
            className="fixed inset-0 bg-zinc-950/40 backdrop-blur-sm transition-opacity"
            onClick={() => setIsOpen(false)}
          />

          {/* Modal Panel Surface */}
          <div
            ref={modalRef}
            className="relative z-10 w-full max-w-[700px] border border-[var(--border)] bg-[var(--surface)] rounded-xl shadow-2xl flex flex-col overflow-hidden max-h-[520px] transition-transform scale-100 duration-100"
            style={{ boxShadow: '0 20px 25px -5px rgba(0,0,0,0.15), 0 10px 10px -5px rgba(0,0,0,0.10)' }}
          >
            {/* Command search input bar */}
            <div className="h-12 border-b border-[var(--border)] px-4 flex items-center gap-3 bg-[var(--surface)] shrink-0">
              {loading || isPending ? (
                <svg className="animate-spin h-4 w-4 text-[var(--brand)] shrink-0" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z" />
                </svg>
              ) : (
                <IconSearch size={15} className="text-[var(--text-muted)] shrink-0" />
              )}
              <input
                ref={inputRef}
                type="text"
                value={query}
                onChange={e => setQuery(e.currentTarget.value)}
                onKeyDown={handleKeyDown}
                placeholder={t.globalSearch.inputPlaceholder}
                className="flex-1 bg-transparent text-[13px] font-[500] text-[var(--text-primary)] placeholder:text-[var(--text-soft)] outline-none h-full w-full"
                dir={locale === 'ar' ? 'rtl' : 'ltr'}
              />
              <button
                type="button"
                onClick={() => setIsOpen(false)}
                className="text-[10px] font-mono font-bold text-[var(--text-soft)] hover:text-[var(--text-primary)] border border-[var(--border)] px-2 py-0.5 rounded transition-all cursor-pointer bg-[var(--app-bg)] shadow-sm"
              >
                ESC
              </button>
            </div>

            {/* List results view panel */}
            <div className="flex-1 min-h-0 overflow-y-auto py-1.5 scrollbar-thin">
              {groups.length === 0 && (
                <div className="text-center py-10 flex flex-col items-center gap-1.5 opacity-55">
                  <IconSearch size={22} className="text-[var(--text-soft)]" />
                  <p className="text-[11px] font-semibold text-[var(--text-muted)] uppercase tracking-wider">
                    {loading
                      ? t.globalSearch.searching
                      : query.trim().length < 2
                        ? t.globalSearch.minChars
                        : t.globalSearch.noResults}
                  </p>
                </div>
              )}

              {groups.map(([groupName, items], gi) => {
                const groupStart = idx
                idx += items.length
                return (
                  <div key={groupName} className="mb-2 last:mb-0">
                    <p className="text-[10px] font-extrabold uppercase tracking-[0.10em] text-[var(--text-soft)] px-4 py-1.5 sticky top-0 bg-[var(--surface)] z-10 select-none">
                      {groupName} · <span className="font-mono text-[9px] font-bold text-[var(--text-soft)]/75">{items.length}</span>
                    </p>
                    <div className="flex flex-col gap-0.5 px-2">
                      {items.map((action, i) => {
                        const actionIdx = groupStart + i
                        const isFocused = actionIdx === focused
                        return (
                          <button
                            key={action.id}
                            type="button"
                            onClick={() => action.onClick()}
                            onMouseEnter={() => setFocused(actionIdx)}
                            className="flex items-center gap-3 w-full px-3.5 py-2 rounded-lg text-left transition-all relative border-l-2 outline-none"
                            style={{
                              background: isFocused ? 'var(--hover-bg)' : 'transparent',
                              borderLeftColor: isFocused ? 'var(--brand)' : 'transparent',
                            }}
                          >
                            <span className="text-[var(--text-soft)] shrink-0 flex" style={{ color: isFocused ? 'var(--brand)' : 'var(--text-soft)' }}>
                              {action.icon}
                            </span>
                            <div className="flex-1 min-w-0">
                              <p
                                className="text-[12px] font-[600] truncate leading-tight transition-colors"
                                style={{ color: isFocused ? 'var(--text-primary)' : 'var(--text-secondary)' }}
                              >
                                <Highlight text={action.label} query={query.trim()} />
                              </p>
                              {action.description && (
                                <p className="text-[10px] font-semibold text-[var(--text-muted)] truncate leading-tight mt-0.5">
                                  <Highlight text={action.description} query={query.trim()} />
                                </p>
                              )}
                            </div>
                            
                            {/* Action metadata badge and key shortcut badge */}
                            <div className="flex items-center gap-1.5 shrink-0">
                              {action.badge && <span className="shrink-0 leading-none">{action.badge}</span>}
                              {isFocused && (
                                <span className="font-mono text-[10px] font-bold text-[var(--brand)] select-none opacity-85 shrink-0">
                                  ↵
                                </span>
                              )}
                            </div>
                          </button>
                        )
                      })}
                    </div>
                  </div>
                )
              })}
            </div>

            {/* Sticky key shortcuts helper footer bar */}
            {actions.length > 0 && (
              <div className="border-t border-[var(--border)] px-4 py-2 flex items-center justify-between shrink-0 bg-[var(--app-bg)]">
                <div className="flex gap-4">
                  {[
                    ['↑↓', t.globalSearch.shortcuts.navigate],
                    ['↵ Enter', t.globalSearch.shortcuts.open],
                    ['Esc', t.globalSearch.shortcuts.close],
                  ].map(([key, label]) => (
                    <span key={key} className="text-[10px] font-semibold text-[var(--text-soft)] select-none flex items-center gap-1">
                      <kbd className="font-mono bg-[var(--surface)] px-1.5 py-0.5 rounded border border-[var(--border)] text-[var(--text-soft)] text-[9px] shadow-[0_1px_0_rgba(0,0,0,0.05)]">{key}</kbd>
                      {label}
                    </span>
                  ))}
                </div>
                <span className="text-[9px] font-mono font-bold text-[var(--text-muted)] select-none tracking-wider uppercase opacity-75">
                  ASM Track Omnibox
                </span>
              </div>
            )}
          </div>
        </div>
      , document.body)}
    </>
  )
}

