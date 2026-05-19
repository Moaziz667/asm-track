'use client'

import { useState, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Header } from '@/components/layout/header'
import { Main } from '@/components/layout/main'
import { ProfileDropdown } from '@/components/profile-dropdown'
import { ThemeSwitch } from '@/components/theme-switch'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Separator } from '@/components/ui/separator'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table'
import { ScrollText, SearchIcon, ChevronLeftIcon, ChevronRightIcon } from 'lucide-react'

// ── Types ─────────────────────────────────────────────────────────────────────

interface AuditLog {
  id: string
  actorName: string
  actorRole: string
  action: string
  targetEntity: string
  resourceId?: string
  details?: string
  ipAddress?: string
  companyId?: string
  createdAt: string
}
interface AuditPage { content: AuditLog[]; totalPages: number; number: number; totalElements: number }
interface Company   { id: string; name: string }

// ── Helpers ───────────────────────────────────────────────────────────────────

const ROLE_VARIANT: Record<string, 'default' | 'secondary' | 'outline'> = {
  SUPER_ADMIN: 'default',
  ADMIN:       'secondary',
  DISPATCHER:  'outline',
  MANAGER:     'outline',
}

const ACTION_LABELS: Record<string, string> = {
  CREATE_ROUTE:          'Tournée créée',
  UPDATE_ROUTE:          'Tournée modifiée',
  DELETE_ROUTE:          'Tournée supprimée',
  VALIDATE_ROUTE:        'Tournée validée',
  CREATE_COMPANY:        'Entreprise créée',
  UPDATE_COMPANY:        'Entreprise modifiée',
  CREATE_VEHICLE:        'Véhicule créé',
  UPDATE_VEHICLE:        'Véhicule modifié',
  DELETE_VEHICLE:        'Véhicule supprimé',
  ASSIGN_VEHICLE:        'Véhicule affecté',
  UPDATE_VEHICLE_STATUS: 'Statut véhicule',
  CREATE_DEPOT:          'Dépôt créé',
  UPDATE_DEPOT:          'Dépôt modifié',
  DELETE_DEPOT:          'Dépôt supprimé',
}

function fmtDate(s: string) {
  const d = new Date(s)
  return {
    date: d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: '2-digit' }),
    time: d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }),
  }
}

function parseDetails(raw?: string): string {
  if (!raw) return '—'
  try {
    const obj = JSON.parse(raw)
    return Object.entries(obj)
      .filter(([k]) => k !== 'action')
      .map(([k, v]) => `${k}: ${v}`)
      .join(' · ')
      .slice(0, 80)
  } catch {
    return raw.slice(0, 80)
  }
}

// ── Component ─────────────────────────────────────────────────────────────────

export function Audit() {
  const [page,        setPage]        = useState(0)
  const [search,      setSearch]      = useState('')
  const [roleFilter,  setRoleFilter]  = useState('all')
  const [actionFilter, setActionFilter] = useState('')

  const { data, isLoading } = useQuery<AuditPage>({
    queryKey: ['audit-logs', page, actionFilter],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '50' })
      if (actionFilter.trim()) params.set('action', actionFilter.trim().toUpperCase())
      return api.get(`/api/admin/audit?${params}`).then(r => r.data)
    },
    placeholderData: prev => prev,
  })

  const { data: companies = [] } = useQuery<Company[]>({
    queryKey: ['companies'],
    queryFn: () => api.get('/api/admin/companies').then(r => r.data),
  })

  const companyName = (id?: string) =>
    id ? (companies.find(c => c.id === id)?.name ?? id.slice(0, 8) + '…') : '—'

  const logs = useMemo(() => {
    const q = search.toLowerCase().trim()
    return (data?.content ?? []).filter(l => {
      if (roleFilter !== 'all' && l.actorRole !== roleFilter) return false
      if (!q) return true
      return (
        l.actorName?.toLowerCase().includes(q) ||
        l.action?.toLowerCase().includes(q) ||
        companyName(l.companyId).toLowerCase().includes(q) ||
        l.targetEntity?.toLowerCase().includes(q)
      )
    })
  }, [data, search, roleFilter, companies])

  const hasFilters = search || roleFilter !== 'all' || actionFilter

  return (
    <>
      <Header fixed>
        <div className='flex items-center gap-2'>
          <span className='text-sm font-semibold'>Journal d'audit</span>
          <Separator orientation='vertical' className='h-4' />
          <span className='text-xs text-muted-foreground'>Traçabilité complète de la plateforme</span>
        </div>
        <div className='ms-auto flex items-center gap-2'>
          <ThemeSwitch />
          <ProfileDropdown />
        </div>
      </Header>

      <Main>
        {/* ── Page header ──────────────────────────────────────────────── */}
        <div className='mb-5 flex items-center justify-between'>
          <div>
            <h1 className='text-xl font-semibold tracking-tight'>Journal d'audit</h1>
            <p className='text-xs text-muted-foreground mt-0.5'>
              {data?.totalElements != null ? `${data.totalElements.toLocaleString('fr-FR')} événements enregistrés` : 'Chargement…'}
            </p>
          </div>
        </div>

        {/* ── Toolbar ──────────────────────────────────────────────────── */}
        <div className='mb-4 flex flex-wrap items-center gap-2'>
          <div className='relative flex-1 min-w-48 max-w-xs'>
            <SearchIcon className='absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground' />
            <Input
              placeholder='Acteur, entreprise…'
              value={search}
              onChange={e => setSearch(e.target.value)}
              className='pl-8 h-8 text-sm'
            />
          </div>

          <Input
            placeholder='Action (ex: CREATE_ROUTE)'
            value={actionFilter}
            onChange={e => { setActionFilter(e.target.value); setPage(0) }}
            className='h-8 w-52 text-sm font-mono'
          />

          <Select value={roleFilter} onValueChange={v => { setRoleFilter(v); setPage(0) }}>
            <SelectTrigger className='h-8 w-36 text-xs'>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value='all'>Tous les rôles</SelectItem>
              <SelectItem value='SUPER_ADMIN'>Super Admin</SelectItem>
              <SelectItem value='ADMIN'>Admin</SelectItem>
              <SelectItem value='DISPATCHER'>Dispatcher</SelectItem>
            </SelectContent>
          </Select>

          {hasFilters && (
            <Button variant='ghost' size='sm' className='h-8 text-xs text-muted-foreground'
              onClick={() => { setSearch(''); setRoleFilter('all'); setActionFilter(''); setPage(0) }}>
              Réinitialiser
            </Button>
          )}

          <span className='ml-auto text-xs text-muted-foreground'>
            {logs.length} résultat{logs.length !== 1 ? 's' : ''} sur cette page
          </span>
        </div>

        {/* ── Table ────────────────────────────────────────────────────── */}
        <div className='rounded-lg border overflow-hidden'>
          <Table>
            <TableHeader>
              <TableRow className='bg-muted/40'>
                <TableHead className='font-medium w-28'>Horodatage</TableHead>
                <TableHead className='font-medium'>Acteur</TableHead>
                <TableHead className='font-medium w-24'>Rôle</TableHead>
                <TableHead className='font-medium'>Entreprise</TableHead>
                <TableHead className='font-medium'>Action</TableHead>
                <TableHead className='font-medium'>Entité</TableHead>
                <TableHead className='font-medium'>Détails</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                Array.from({ length: 8 }).map((_, i) => (
                  <TableRow key={i}>
                    {Array.from({ length: 7 }).map((_, j) => (
                      <TableCell key={j}>
                        <div className='h-3.5 animate-pulse rounded bg-muted' />
                      </TableCell>
                    ))}
                  </TableRow>
                ))
              ) : logs.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} className='py-16 text-center'>
                    <div className='flex flex-col items-center gap-2'>
                      <ScrollText className='h-8 w-8 text-muted-foreground/30' />
                      <p className='text-sm text-muted-foreground'>
                        {hasFilters ? 'Aucun résultat pour ces filtres' : 'Aucun événement enregistré'}
                      </p>
                    </div>
                  </TableCell>
                </TableRow>
              ) : (
                logs.map(l => {
                  const { date, time } = fmtDate(l.createdAt)
                  return (
                    <TableRow key={l.id} className='text-sm'>
                      <TableCell className='whitespace-nowrap'>
                        <div className='text-xs text-muted-foreground'>{date}</div>
                        <div className='text-xs font-mono font-semibold'>{time}</div>
                      </TableCell>
                      <TableCell className='font-medium'>{l.actorName ?? '—'}</TableCell>
                      <TableCell>
                        <Badge variant={ROLE_VARIANT[l.actorRole] ?? 'outline'} className='text-[10px] py-0'>
                          {l.actorRole}
                        </Badge>
                      </TableCell>
                      <TableCell className='text-xs text-muted-foreground'>
                        {companyName(l.companyId)}
                      </TableCell>
                      <TableCell>
                        <div className='flex flex-col gap-0.5'>
                          <code className='text-[10px] bg-muted px-1.5 py-0.5 rounded font-mono'>
                            {l.action}
                          </code>
                          {ACTION_LABELS[l.action] && (
                            <span className='text-[11px] text-muted-foreground'>
                              {ACTION_LABELS[l.action]}
                            </span>
                          )}
                        </div>
                      </TableCell>
                      <TableCell className='text-xs text-muted-foreground'>{l.targetEntity ?? '—'}</TableCell>
                      <TableCell className='max-w-xs'>
                        <p className='text-[11px] text-muted-foreground truncate' title={l.details ?? ''}>
                          {parseDetails(l.details)}
                        </p>
                      </TableCell>
                    </TableRow>
                  )
                })
              )}
            </TableBody>
          </Table>
        </div>

        {/* ── Pagination ────────────────────────────────────────────────── */}
        {data && data.totalPages > 1 && (
          <div className='mt-3 flex items-center justify-between text-xs text-muted-foreground'>
            <span>Page {(data.number ?? 0) + 1} sur {data.totalPages}</span>
            <div className='flex items-center gap-1'>
              <Button variant='outline' size='icon' className='h-7 w-7'
                disabled={page === 0} onClick={() => setPage(p => p - 1)}>
                <ChevronLeftIcon className='h-3.5 w-3.5' />
              </Button>
              <span className='px-2'>{page + 1} / {data.totalPages}</span>
              <Button variant='outline' size='icon' className='h-7 w-7'
                disabled={page >= data.totalPages - 1} onClick={() => setPage(p => p + 1)}>
                <ChevronRightIcon className='h-3.5 w-3.5' />
              </Button>
            </div>
          </div>
        )}
      </Main>
    </>
  )
}
