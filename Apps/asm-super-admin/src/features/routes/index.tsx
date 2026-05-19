'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronRight, MapPin, User, Truck } from 'lucide-react'
import { api } from '@/lib/api'
import { Header } from '@/components/layout/header'
import { Main } from '@/components/layout/main'
import { ProfileDropdown } from '@/components/profile-dropdown'
import { ThemeSwitch } from '@/components/theme-switch'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table'

interface Stop {
  id: string; stopOrder: number; status: string; deliveryStatus?: string
  clientName?: string; deliveryAddress?: string; deliveryCity?: string
  etaAt?: string; routeEtaAt?: string
}
interface Route {
  id: string; name: string; date: string; status: string
  driverId?: string; vehicleId?: string; companyId?: string
  totalStops?: number; completedStops?: number; failedStops?: number; progressPercent?: number
  stops?: Stop[]
}
interface Company { id: string; name: string }
interface Driver  { id: string; name: string }
interface Vehicle { id: string; name: string; plate: string }

const STATUS_LABEL: Record<string, { label: string; variant: 'default'|'secondary'|'destructive'|'outline' }> = {
  DRAFT:       { label: 'Brouillon',  variant: 'secondary'  },
  VALIDATED:   { label: 'Planifié',   variant: 'outline'    },
  IN_PROGRESS: { label: 'En route',   variant: 'default'    },
  CLOSED:      { label: 'Terminé',    variant: 'secondary'  },
  CANCELLED:   { label: 'Annulé',     variant: 'destructive'},
}
const STOP_STATUS: Record<string, string> = {
  PENDING: 'En attente', DELIVERED: 'Livré', FAILED: 'Échec',
  CANCELLED: 'Annulé', PARTIAL: 'Partiel', IN_TRANSIT: 'En route',
  PICKED_UP: 'Ramassé', SCHEDULED: 'Planifié', UNSCHEDULED: 'Non planifié',
  COMPLETED: 'Livré', FAILED_ATTEMPT: 'Tentative échouée',
}

export function Routes() {
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [search,   setSearch]   = useState('')
  const [statusF,  setStatusF]  = useState('ALL')
  const [companyF, setCompanyF] = useState('ALL')

  const { data: routes = [], isLoading } = useQuery<Route[]>({
    queryKey: ['all-routes'],
    queryFn: () => api.get('/api/admin/routes').then(r => r.data),
  })
  const { data: companies = [] } = useQuery<Company[]>({
    queryKey: ['companies'],
    queryFn: () => api.get('/api/admin/companies').then(r => r.data),
  })
  const { data: drivers = [] } = useQuery<Driver[]>({
    queryKey: ['all-drivers'],
    queryFn: () => api.get('/api/admin/drivers').then(r => r.data),
  })
  const { data: vehicles = [] } = useQuery<Vehicle[]>({
    queryKey: ['all-vehicles'],
    queryFn: () => api.get('/api/admin/vehicles').then(r => r.data),
  })

  const companyName = (id?: string) => companies.find(c => c.id === id)?.name ?? '—'
  const driverName  = (id?: string) => drivers.find(d => d.id === id)?.name ?? '—'
  const vehicleName = (id?: string) => {
    const v = vehicles.find(v => v.id === id)
    return v ? `${v.name} · ${v.plate}` : '—'
  }

  const toggle = (id: string) => setExpanded(prev => {
    const n = new Set(prev)
    n.has(id) ? n.delete(id) : n.add(id)
    return n
  })

  const filtered = routes.filter(r => {
    if (statusF  !== 'ALL' && r.status   !== statusF)  return false
    if (companyF !== 'ALL' && r.companyId !== companyF) return false
    if (search) {
      const q = search.toLowerCase()
      return r.name?.toLowerCase().includes(q) ||
             companyName(r.companyId).toLowerCase().includes(q) ||
             driverName(r.driverId).toLowerCase().includes(q)
    }
    return true
  })

  return (
    <>
      <Header fixed>
        <span className='font-semibold'>Routes</span>
        <div className='ms-auto flex items-center gap-2'>
          <ThemeSwitch />
          <ProfileDropdown />
        </div>
      </Header>

      <Main>
        <div className='mb-4'>
          <h2 className='text-2xl font-bold tracking-tight'>All Routes</h2>
          <p className='text-muted-foreground'>Live view across all companies.</p>
        </div>

        {/* Filters */}
        <div className='mb-4 flex flex-wrap gap-2'>
          <Input placeholder='Search name, company, driver…' value={search}
            onChange={e => setSearch(e.target.value)} className='h-8 w-64' />
          <Select value={statusF} onValueChange={setStatusF}>
            <SelectTrigger className='h-8 w-36'><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value='ALL'>All statuses</SelectItem>
              {Object.entries(STATUS_LABEL).map(([k, v]) => (
                <SelectItem key={k} value={k}>{v.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select value={companyF} onValueChange={setCompanyF}>
            <SelectTrigger className='h-8 w-44'><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value='ALL'>All companies</SelectItem>
              {companies.map(c => <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>

        <div className='rounded-md border'>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className='w-8' />
                <TableHead>Date</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>Company</TableHead>
                <TableHead>Driver</TableHead>
                <TableHead>Vehicle</TableHead>
                <TableHead>Progress</TableHead>
                <TableHead>Status</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading && (
                <TableRow><TableCell colSpan={8} className='text-center py-8'>Loading…</TableCell></TableRow>
              )}
              {filtered.length === 0 && !isLoading && (
                <TableRow><TableCell colSpan={8} className='text-center py-8 text-muted-foreground'>No routes found.</TableCell></TableRow>
              )}
              {filtered.map(r => {
                const open = expanded.has(r.id)
                const cfg  = STATUS_LABEL[r.status] ?? { label: r.status, variant: 'secondary' as const }
                const pct  = r.progressPercent ?? (r.totalStops && r.completedStops != null
                  ? Math.round((r.completedStops / r.totalStops) * 100) : 0)
                return (
                  <>
                    <TableRow key={r.id} className='cursor-pointer hover:bg-muted/50'
                      onClick={() => toggle(r.id)}>
                      <TableCell>
                        {open ? <ChevronDown className='h-4 w-4' /> : <ChevronRight className='h-4 w-4' />}
                      </TableCell>
                      <TableCell className='text-sm'>{r.date ?? '—'}</TableCell>
                      <TableCell className='font-medium'>{r.name}</TableCell>
                      <TableCell>
                        <span className='text-xs font-medium text-muted-foreground'>{companyName(r.companyId)}</span>
                      </TableCell>
                      <TableCell>
                        <span className='flex items-center gap-1 text-sm'>
                          <User className='h-3 w-3 text-muted-foreground' />{driverName(r.driverId)}
                        </span>
                      </TableCell>
                      <TableCell>
                        <span className='flex items-center gap-1 text-sm'>
                          <Truck className='h-3 w-3 text-muted-foreground' />{vehicleName(r.vehicleId)}
                        </span>
                      </TableCell>
                      <TableCell>
                        <div className='flex items-center gap-2'>
                          <div className='h-1.5 w-24 rounded-full bg-muted overflow-hidden'>
                            <div className='h-full rounded-full bg-primary transition-all'
                              style={{ width: `${pct}%` }} />
                          </div>
                          <span className='text-xs text-muted-foreground'>{r.completedStops ?? 0}/{r.totalStops ?? 0}</span>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant={cfg.variant}>{cfg.label}</Badge>
                      </TableCell>
                    </TableRow>

                    {/* Expanded stops */}
                    {open && (
                      <TableRow key={`${r.id}-stops`}>
                        <TableCell colSpan={8} className='p-0 bg-muted/30'>
                          {!r.stops?.length ? (
                            <p className='py-4 text-center text-xs text-muted-foreground'>No stops data.</p>
                          ) : (
                            <table className='w-full text-xs'>
                              <thead>
                                <tr className='border-b text-muted-foreground'>
                                  <th className='py-2 pl-12 text-left font-medium w-8'>#</th>
                                  <th className='py-2 text-left font-medium'>Client</th>
                                  <th className='py-2 text-left font-medium'>Address</th>
                                  <th className='py-2 text-left font-medium'>Status</th>
                                  <th className='py-2 pr-4 text-left font-medium'>ETA</th>
                                </tr>
                              </thead>
                              <tbody>
                                {r.stops.map(s => (
                                  <tr key={s.id} className='border-b last:border-0 hover:bg-muted/40'>
                                    <td className='py-2 pl-12 text-muted-foreground'>{s.stopOrder}</td>
                                    <td className='py-2 font-medium'>{s.clientName ?? '—'}</td>
                                    <td className='py-2 text-muted-foreground'>
                                      <span className='flex items-center gap-1'>
                                        <MapPin className='h-3 w-3' />
                                        {s.deliveryAddress ?? '—'}{s.deliveryCity ? `, ${s.deliveryCity}` : ''}
                                      </span>
                                    </td>
                                    <td className='py-2'>
                                      <Badge variant='outline' className='text-[10px]'>
                                        {STOP_STATUS[s.deliveryStatus ?? s.status] ?? (s.deliveryStatus ?? s.status)}
                                      </Badge>
                                    </td>
                                    <td className='py-2 pr-4 text-muted-foreground'>
                                      {(s.etaAt ?? s.routeEtaAt)
                                        ? new Date(s.etaAt ?? s.routeEtaAt!).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                                        : '—'}
                                    </td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          )}
                        </TableCell>
                      </TableRow>
                    )}
                  </>
                )
              })}
            </TableBody>
          </Table>
        </div>
      </Main>
    </>
  )
}
