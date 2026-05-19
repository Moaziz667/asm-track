'use client'

import { useQuery } from '@tanstack/react-query'
import {
  Building2, Users, Truck, TrendingUp, Clock, CheckCircle2,
  AlertCircle, Wrench, RefreshCw, Activity,
} from 'lucide-react'
import { api } from '@/lib/api'
import { Header } from '@/components/layout/header'
import { Main } from '@/components/layout/main'
import { ProfileDropdown } from '@/components/profile-dropdown'
import { ThemeSwitch } from '@/components/theme-switch'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
} from 'recharts'

// ── Types ─────────────────────────────────────────────────────────────────────

interface DailyVolume { date: string; count: number }
interface DashboardKpi {
  avgDelayMinutes: number
  totalOrdersToday: number
  weeklyTrend: DailyVolume[]
  totalReassigned: number
  totalReplanned: number
}
interface AuditEntry {
  id: string
  actorName: string
  actorRole: string
  action: string
  targetEntity: string
  createdAt: string
}
interface Company { id: string; name: string; active: boolean; logoUrl?: string; primaryColor?: string }
interface Vehicle { id: string; active: boolean; assigned: boolean; vehicleStatus?: string }
interface Driver  { id: string; active: boolean }

// ── Helpers ───────────────────────────────────────────────────────────────────

function fmtTime(iso: string) {
  try { return new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) }
  catch { return '—' }
}

function fmtAction(action: string) {
  const map: Record<string, string> = {
    CREATE_ROUTE:    'Tournée créée',
    UPDATE_ROUTE:    'Tournée modifiée',
    DELETE_ROUTE:    'Tournée supprimée',
    VALIDATE_ROUTE:  'Tournée validée',
    CREATE_COMPANY:  'Entreprise créée',
    UPDATE_COMPANY:  'Entreprise modifiée',
    CREATE_VEHICLE:  'Véhicule ajouté',
    UPDATE_VEHICLE:  'Véhicule modifié',
    ASSIGN_VEHICLE:  'Véhicule affecté',
    UPDATE_VEHICLE_STATUS: 'Statut véhicule',
  }
  return map[action] ?? action.replace(/_/g, ' ')
}

function roleBadge(role: string) {
  if (role === 'SUPER_ADMIN') return <Badge variant='default' className='text-[10px] py-0'>Super Admin</Badge>
  if (role === 'ADMIN')       return <Badge variant='secondary' className='text-[10px] py-0'>Admin</Badge>
  return <Badge variant='outline' className='text-[10px] py-0'>{role}</Badge>
}

// ── Stat card ─────────────────────────────────────────────────────────────────

function StatCard({
  label, value, sub, icon: Icon, loading, accent,
}: {
  label: string; value: string | number; sub?: string
  icon: React.ElementType; loading: boolean; accent?: string
}) {
  return (
    <div className='relative overflow-hidden rounded-lg border bg-card px-5 py-4'>
      <div
        className='absolute inset-y-0 left-0 w-[3px] rounded-l-lg'
        style={{ background: accent ?? 'hsl(var(--primary))' }}
      />
      <div className='flex items-center justify-between mb-2'>
        <span className='text-xs font-medium text-muted-foreground uppercase tracking-wide'>{label}</span>
        <Icon className='h-3.5 w-3.5 text-muted-foreground' />
      </div>
      {loading ? (
        <div className='h-7 w-16 animate-pulse rounded bg-muted' />
      ) : (
        <div className='text-2xl font-bold tabular-nums tracking-tight'>{value}</div>
      )}
      {sub && <p className='mt-1 text-[11px] text-muted-foreground'>{sub}</p>}
    </div>
  )
}

// ── Fleet status bar ──────────────────────────────────────────────────────────

function FleetStatusPanel({ vehicles, loading }: { vehicles: Vehicle[]; loading: boolean }) {
  const available   = vehicles.filter(v => v.active && v.vehicleStatus === 'AVAILABLE' && !v.assigned).length
  const onRoute     = vehicles.filter(v => v.assigned).length
  const maintenance = vehicles.filter(v => v.vehicleStatus === 'IN_MAINTENANCE').length
  const outOfSvc    = vehicles.filter(v => v.vehicleStatus === 'OUT_OF_SERVICE').length
  const inactive    = vehicles.filter(v => !v.active).length

  const rows = [
    { label: 'Disponibles',     value: available,   color: 'bg-emerald-500', icon: CheckCircle2 },
    { label: 'En tournée',      value: onRoute,     color: 'bg-blue-500',    icon: Activity },
    { label: 'En maintenance',  value: maintenance, color: 'bg-amber-500',   icon: Wrench },
    { label: 'Hors service',    value: outOfSvc,    color: 'bg-red-500',     icon: AlertCircle },
    { label: 'Inactifs',        value: inactive,    color: 'bg-zinc-400',    icon: Truck },
  ]

  return (
    <div className='rounded-lg border bg-card p-5 h-full'>
      <div className='flex items-center justify-between mb-4'>
        <span className='text-sm font-semibold'>État de la flotte</span>
        <span className='text-xs text-muted-foreground'>{vehicles.length} véhicules</span>
      </div>

      {loading ? (
        <div className='space-y-3'>
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className='h-8 animate-pulse rounded bg-muted' />
          ))}
        </div>
      ) : (
        <div className='space-y-2.5'>
          {rows.map(r => {
            const pct = vehicles.length > 0 ? (r.value / vehicles.length) * 100 : 0
            return (
              <div key={r.label}>
                <div className='flex items-center justify-between mb-1'>
                  <span className='text-xs text-muted-foreground'>{r.label}</span>
                  <span className='text-xs font-semibold tabular-nums'>{r.value}</span>
                </div>
                <div className='h-1.5 w-full rounded-full bg-muted overflow-hidden'>
                  <div className={`h-full rounded-full ${r.color}`} style={{ width: `${pct}%` }} />
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

// ── Activity feed ─────────────────────────────────────────────────────────────

function ActivityFeed({ logs, loading }: { logs: AuditEntry[]; loading: boolean }) {
  return (
    <div className='rounded-lg border bg-card p-5 h-full'>
      <div className='flex items-center justify-between mb-4'>
        <span className='text-sm font-semibold'>Activité récente</span>
        <Activity className='h-3.5 w-3.5 text-muted-foreground' />
      </div>

      {loading ? (
        <div className='space-y-3'>
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className='h-9 animate-pulse rounded bg-muted' />
          ))}
        </div>
      ) : logs.length === 0 ? (
        <div className='flex flex-col items-center justify-center py-10 gap-2'>
          <Activity className='h-8 w-8 text-muted-foreground/30' />
          <p className='text-xs text-muted-foreground'>Aucune activité récente</p>
        </div>
      ) : (
        <div className='space-y-0 divide-y divide-border'>
          {logs.map(log => (
            <div key={log.id} className='flex items-center justify-between gap-3 py-2.5'>
              <div className='min-w-0 flex-1'>
                <p className='truncate text-xs font-medium'>{log.actorName ?? '—'}</p>
                <p className='truncate text-[11px] text-muted-foreground'>{fmtAction(log.action)}</p>
              </div>
              <div className='flex flex-col items-end gap-0.5 shrink-0'>
                {roleBadge(log.actorRole)}
                <span className='text-[10px] text-muted-foreground'>{fmtTime(log.createdAt)}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// ── Companies table ───────────────────────────────────────────────────────────

function CompaniesPanel({ companies, loading }: { companies: Company[]; loading: boolean }) {
  const active   = companies.filter(c => c.active)
  const inactive = companies.filter(c => !c.active)

  return (
    <div className='rounded-lg border bg-card p-5 h-full'>
      <div className='flex items-center justify-between mb-4'>
        <span className='text-sm font-semibold'>Entreprises</span>
        <div className='flex items-center gap-2'>
          <span className='text-[11px] text-muted-foreground'>{active.length} actives</span>
          {inactive.length > 0 && (
            <span className='text-[11px] text-muted-foreground'>· {inactive.length} inactives</span>
          )}
        </div>
      </div>

      {loading ? (
        <div className='space-y-2'>
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className='h-10 animate-pulse rounded bg-muted' />
          ))}
        </div>
      ) : companies.length === 0 ? (
        <div className='flex flex-col items-center justify-center py-10 gap-2'>
          <Building2 className='h-8 w-8 text-muted-foreground/30' />
          <p className='text-xs text-muted-foreground'>Aucune entreprise</p>
        </div>
      ) : (
        <div className='divide-y divide-border'>
          {companies.slice(0, 8).map(c => (
            <div key={c.id} className='flex items-center gap-3 py-2.5'>
              {c.logoUrl ? (
                <img src={c.logoUrl} alt={c.name}
                  className='h-7 w-7 rounded object-cover border shrink-0' />
              ) : (
                <div
                  className='flex h-7 w-7 items-center justify-center rounded text-white text-[11px] font-bold shrink-0'
                  style={{ background: c.primaryColor ?? '#6366f1' }}>
                  {c.name.charAt(0).toUpperCase()}
                </div>
              )}
              <span className='flex-1 truncate text-sm font-medium'>{c.name}</span>
              <div
                className={`h-1.5 w-1.5 rounded-full shrink-0 ${c.active ? 'bg-emerald-500' : 'bg-zinc-400'}`}
              />
            </div>
          ))}
          {companies.length > 8 && (
            <p className='pt-2.5 text-center text-[11px] text-muted-foreground'>
              +{companies.length - 8} autres entreprises
            </p>
          )}
        </div>
      )}
    </div>
  )
}

// ── Main dashboard ────────────────────────────────────────────────────────────

export function Dashboard() {
  const REFRESH = 30_000

  const { data: companies = [], isLoading: loadingCompanies, refetch: refetchCompanies } =
    useQuery<Company[]>({
      queryKey: ['companies'],
      queryFn: () => api.get('/api/admin/companies').then(r => r.data),
      refetchInterval: REFRESH,
    })

  const { data: drivers = [], isLoading: loadingDrivers } =
    useQuery<Driver[]>({
      queryKey: ['all-drivers'],
      queryFn: () => api.get('/api/admin/drivers').then(r => r.data),
      refetchInterval: REFRESH,
    })

  const { data: vehicles = [], isLoading: loadingVehicles } =
    useQuery<Vehicle[]>({
      queryKey: ['all-vehicles'],
      queryFn: () => api.get('/api/admin/vehicles').then(r => r.data),
      refetchInterval: REFRESH,
    })

  const { data: kpi, isLoading: loadingKpi } =
    useQuery<DashboardKpi>({
      queryKey: ['dashboard-kpi'],
      queryFn: () => api.get('/api/admin/reports/dashboard').then(r => r.data),
      refetchInterval: REFRESH,
    })

  const { data: auditPage, isLoading: loadingAudit } =
    useQuery<{ content: AuditEntry[] }>({
      queryKey: ['recent-audit'],
      queryFn: () => api.get('/api/admin/audit?size=10').then(r => r.data),
      refetchInterval: REFRESH,
    })

  const activeCompanies  = companies.filter(c => c.active).length
  const activeDrivers    = drivers.filter(d => d.active).length
  const weeklyOrders     = kpi?.weeklyTrend?.reduce((s, d) => s + d.count, 0) ?? 0
  const avgDelay         = kpi?.avgDelayMinutes ?? 0
  const recentLogs       = auditPage?.content ?? []

  const chartData = kpi?.weeklyTrend?.map(d => ({
    name: new Date(d.date + 'T00:00:00').toLocaleDateString('fr-FR', { weekday: 'short' }),
    Commandes: d.count,
  })) ?? []

  function handleRefresh() {
    refetchCompanies()
  }

  const loadingAll = loadingCompanies || loadingDrivers || loadingVehicles || loadingKpi

  return (
    <>
      <Header fixed>
        <div className='flex items-center gap-2'>
          <span className='text-sm font-semibold'>Centre de contrôle</span>
          <Separator orientation='vertical' className='h-4' />
          <span className='text-xs text-muted-foreground hidden sm:block'>ASM Track — Supervision opérationnelle</span>
        </div>
        <div className='ms-auto flex items-center gap-2'>
          <Button
            variant='ghost' size='icon'
            onClick={handleRefresh}
            title='Actualiser'
            className='h-8 w-8'>
            <RefreshCw className={`h-3.5 w-3.5 ${loadingAll ? 'animate-spin' : ''}`} />
          </Button>
          <ThemeSwitch />
          <ProfileDropdown />
        </div>
      </Header>

      <Main>
        {/* ── Page header ────────────────────────────────────────────────── */}
        <div className='mb-6 flex items-center justify-between'>
          <div>
            <h1 className='text-xl font-semibold tracking-tight'>Vue d'ensemble</h1>
            <p className='text-xs text-muted-foreground mt-0.5'>
              Données actualisées toutes les 30 secondes · Portée globale toutes entreprises
            </p>
          </div>
        </div>

        {/* ── KPI strip ──────────────────────────────────────────────────── */}
        <div className='grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5 mb-5'>
          <StatCard
            label='Entreprises actives'
            value={activeCompanies}
            sub={`${companies.length} au total`}
            icon={Building2}
            loading={loadingCompanies}
            accent='#6366f1'
          />
          <StatCard
            label='Chauffeurs actifs'
            value={activeDrivers}
            sub={`${drivers.length} enregistrés`}
            icon={Users}
            loading={loadingDrivers}
            accent='#0ea5e9'
          />
          <StatCard
            label='Flotte véhicules'
            value={vehicles.filter(v => v.active).length}
            sub={`${vehicles.length} au total`}
            icon={Truck}
            loading={loadingVehicles}
            accent='#f59e0b'
          />
          <StatCard
            label='Commandes — 7 jours'
            value={weeklyOrders.toLocaleString('fr-FR')}
            sub={`${kpi?.totalOrdersToday ?? '—'} aujourd'hui`}
            icon={TrendingUp}
            loading={loadingKpi}
            accent='#10b981'
          />
          <StatCard
            label='Retard moyen'
            value={avgDelay > 0 ? `${avgDelay.toFixed(1)} min` : '—'}
            sub={kpi?.totalReassigned ? `${kpi.totalReassigned} réaffectations` : 'Aucune réaffectation'}
            icon={Clock}
            loading={loadingKpi}
            accent='#ef4444'
          />
        </div>

        {/* ── Middle row: chart + fleet status ───────────────────────────── */}
        <div className='grid gap-4 lg:grid-cols-5 mb-4'>
          {/* Volume chart */}
          <div className='lg:col-span-3 rounded-lg border bg-card p-5'>
            <div className='flex items-center justify-between mb-4'>
              <span className='text-sm font-semibold'>Volume hebdomadaire</span>
              <span className='text-xs text-muted-foreground'>Commandes livrées / jour</span>
            </div>
            {loadingKpi ? (
              <div className='h-48 animate-pulse rounded bg-muted' />
            ) : chartData.length === 0 ? (
              <div className='flex h-48 items-center justify-center'>
                <p className='text-xs text-muted-foreground'>Aucune donnée disponible</p>
              </div>
            ) : (
              <ResponsiveContainer width='100%' height={200}>
                <BarChart data={chartData} barSize={28}>
                  <CartesianGrid strokeDasharray='3 3' stroke='hsl(var(--border))' vertical={false} />
                  <XAxis
                    dataKey='name'
                    tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }}
                    axisLine={false}
                    tickLine={false}
                  />
                  <YAxis
                    allowDecimals={false}
                    tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }}
                    axisLine={false}
                    tickLine={false}
                    width={28}
                  />
                  <Tooltip
                    contentStyle={{
                      background: 'hsl(var(--card))',
                      border: '1px solid hsl(var(--border))',
                      borderRadius: 6,
                      fontSize: 11,
                    }}
                    cursor={{ fill: 'hsl(var(--muted))' }}
                  />
                  <Bar dataKey='Commandes' fill='hsl(var(--primary))' radius={[3, 3, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>

          {/* Fleet status */}
          <div className='lg:col-span-2'>
            <FleetStatusPanel vehicles={vehicles} loading={loadingVehicles} />
          </div>
        </div>

        {/* ── Bottom row: companies + activity ───────────────────────────── */}
        <div className='grid gap-4 lg:grid-cols-5'>
          <div className='lg:col-span-3'>
            <CompaniesPanel companies={companies} loading={loadingCompanies} />
          </div>
          <div className='lg:col-span-2'>
            <ActivityFeed logs={recentLogs} loading={loadingAudit} />
          </div>
        </div>
      </Main>
    </>
  )
}
