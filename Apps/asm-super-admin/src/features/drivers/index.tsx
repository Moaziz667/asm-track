'use client'

import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  PlusIcon, PencilIcon, PowerIcon, KeyRoundIcon,
  SearchIcon, Users, ChevronLeftIcon, ChevronRightIcon,
  BarChart2, FileDown, TrendingUp, Package, CheckCircle2, XCircle,
} from 'lucide-react'
import { api } from '@/lib/api'
import { Header } from '@/components/layout/header'
import { Main } from '@/components/layout/main'
import { ProfileDropdown } from '@/components/profile-dropdown'
import { ThemeSwitch } from '@/components/theme-switch'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Separator } from '@/components/ui/separator'
import {
  Sheet, SheetContent, SheetHeader, SheetTitle,
} from '@/components/ui/sheet'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog'
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

// ── Types ─────────────────────────────────────────────────────────────────────

interface Driver {
  id: string
  name: string
  phone: string
  active: boolean
  totalDeliveries: number
  delivered: number
  failed: number
}

const PAGE_SIZE = 15

// ── Component ─────────────────────────────────────────────────────────────────

export function Drivers() {
  const qc = useQueryClient()

  const [createOpen, setCreateOpen]   = useState(false)
  const [editOpen, setEditOpen]       = useState(false)
  const [pwdOpen, setPwdOpen]         = useState(false)
  const [selected, setSelected]       = useState<Driver | null>(null)
  const [createForm, setCreateForm]   = useState({ name: '', phone: '', password: '' })
  const [editForm, setEditForm]       = useState({ name: '', phone: '' })
  const [newPassword, setNewPassword] = useState('')

  const [perfDriver, setPerfDriver]       = useState<Driver | null>(null)
  const [perfPeriod, setPerfPeriod]       = useState<'week' | 'month' | 'all'>('month')
  const [pdfLoading, setPdfLoading]       = useState(false)

  const [search, setSearch]               = useState('')
  const [statusFilter, setStatusFilter]   = useState<'all' | 'active' | 'inactive'>('all')
  const [page, setPage]                   = useState(0)

  const { data: drivers = [], isLoading } = useQuery<Driver[]>({
    queryKey: ['admin-drivers'],
    queryFn: () => api.get('/api/admin/drivers').then(r => r.data),
  })

  const create = useMutation({
    mutationFn: (b: typeof createForm) => api.post('/api/admin/drivers', b),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-drivers'] })
      toast.success('Chauffeur créé')
      setCreateOpen(false)
    },
    onError: (e: { response?: { data?: { message?: string } } }) =>
      toast.error(e.response?.data?.message ?? 'Échec de la création'),
  })

  const update = useMutation({
    mutationFn: (b: typeof editForm) => api.put(`/api/admin/drivers/${selected?.id}`, b),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-drivers'] })
      toast.success('Chauffeur mis à jour')
      setEditOpen(false)
    },
    onError: () => toast.error('Échec de la mise à jour'),
  })

  const toggleStatus = useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) =>
      api.patch(`/api/admin/drivers/${id}/status`, { active }),
    onSuccess: (_, { active }) => {
      qc.invalidateQueries({ queryKey: ['admin-drivers'] })
      toast.success(active ? 'Chauffeur activé' : 'Chauffeur désactivé')
    },
    onError: () => toast.error('Échec du changement de statut'),
  })

  const resetPwd = useMutation({
    mutationFn: () =>
      api.post(`/api/admin/drivers/${selected?.id}/reset-password`, { password: newPassword }),
    onSuccess: () => {
      toast.success('Mot de passe réinitialisé')
      setPwdOpen(false)
      setNewPassword('')
    },
    onError: () => toast.error('Échec de la réinitialisation'),
  })

  async function downloadPdf(driver: Driver, period: string) {
    setPdfLoading(true)
    try {
      const res = await api.get(
        `/api/admin/reports/drivers/${driver.id}/performance/pdf?period=${period}`,
        { responseType: 'blob' }
      )
      const url = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }))
      const a   = document.createElement('a')
      a.href    = url
      a.download = `performance-${driver.name.replace(/\s+/g, '-').toLowerCase()}-${period}.pdf`
      a.click()
      URL.revokeObjectURL(url)
    } catch {
      toast.error('Échec du téléchargement du rapport')
    } finally {
      setPdfLoading(false)
    }
  }

  const filtered = useMemo(() => {
    const q = search.toLowerCase().trim()
    return drivers.filter(d => {
      if (q && !d.name.toLowerCase().includes(q) && !d.phone.includes(q)) return false
      if (statusFilter === 'active'   && !d.active) return false
      if (statusFilter === 'inactive' &&  d.active) return false
      return true
    })
  }, [drivers, search, statusFilter])

  const totalPages = Math.ceil(filtered.length / PAGE_SIZE)
  const paged      = filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
  const activeCount = drivers.filter(d => d.active).length

  return (
    <>
      <Header fixed>
        <div className='flex items-center gap-2'>
          <span className='text-sm font-semibold'>Chauffeurs</span>
          <Separator orientation='vertical' className='h-4' />
          <span className='text-xs text-muted-foreground'>Gestion du pool de chauffeurs ASM</span>
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
            <h1 className='text-xl font-semibold tracking-tight'>Chauffeurs</h1>
            <p className='text-xs text-muted-foreground mt-0.5'>
              {activeCount} actifs · {drivers.length - activeCount} inactifs · {drivers.length} total
            </p>
          </div>
          <Button size='sm' onClick={() => { setCreateForm({ name: '', phone: '', password: '' }); setCreateOpen(true) }}>
            <PlusIcon className='mr-1.5 h-3.5 w-3.5' />
            Nouveau chauffeur
          </Button>
        </div>

        {/* ── Toolbar ──────────────────────────────────────────────────── */}
        <div className='mb-4 flex flex-wrap items-center gap-2'>
          <div className='relative flex-1 min-w-48 max-w-xs'>
            <SearchIcon className='absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground' />
            <Input
              placeholder='Nom ou téléphone…'
              value={search}
              onChange={e => { setSearch(e.target.value); setPage(0) }}
              className='pl-8 h-8 text-sm'
            />
          </div>

          <Select value={statusFilter} onValueChange={v => { setStatusFilter(v as any); setPage(0) }}>
            <SelectTrigger className='h-8 w-36 text-xs'>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value='all'>Tous les statuts</SelectItem>
              <SelectItem value='active'>Actifs</SelectItem>
              <SelectItem value='inactive'>Inactifs</SelectItem>
            </SelectContent>
          </Select>

          {(search || statusFilter !== 'all') && (
            <Button variant='ghost' size='sm' className='h-8 text-xs text-muted-foreground'
              onClick={() => { setSearch(''); setStatusFilter('all'); setPage(0) }}>
              Réinitialiser
            </Button>
          )}

          <span className='ml-auto text-xs text-muted-foreground'>
            {filtered.length} résultat{filtered.length !== 1 ? 's' : ''}
          </span>
        </div>

        {/* ── Table ────────────────────────────────────────────────────── */}
        <div className='rounded-lg border overflow-hidden'>
          <Table>
            <TableHeader>
              <TableRow className='bg-muted/40'>
                <TableHead className='font-medium'>Chauffeur</TableHead>
                <TableHead className='font-medium'>Téléphone</TableHead>
                <TableHead className='font-medium'>Statut</TableHead>
                <TableHead className='font-medium text-right'>Livraisons</TableHead>
                <TableHead className='font-medium text-right'>Réussies</TableHead>
                <TableHead className='font-medium text-right'>Taux succès</TableHead>
                <TableHead className='text-right font-medium'>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                Array.from({ length: 6 }).map((_, i) => (
                  <TableRow key={i}>
                    {Array.from({ length: 7 }).map((_, j) => (
                      <TableCell key={j}><div className='h-4 animate-pulse rounded bg-muted' /></TableCell>
                    ))}
                  </TableRow>
                ))
              ) : paged.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} className='py-16 text-center'>
                    <div className='flex flex-col items-center gap-2'>
                      <Users className='h-8 w-8 text-muted-foreground/30' />
                      <p className='text-sm text-muted-foreground'>Aucun chauffeur trouvé</p>
                    </div>
                  </TableCell>
                </TableRow>
              ) : (
                paged.map(d => {
                  const rate = d.totalDeliveries > 0
                    ? Math.round((d.delivered / d.totalDeliveries) * 100)
                    : null
                  return (
                    <TableRow key={d.id} className='group'>
                      <TableCell>
                        <div className='flex items-center gap-2.5'>
                          <div className='flex h-7 w-7 items-center justify-center rounded-full bg-muted text-xs font-semibold'>
                            {d.name.charAt(0).toUpperCase()}
                          </div>
                          <span className='font-medium text-sm'>{d.name}</span>
                        </div>
                      </TableCell>
                      <TableCell className='text-sm font-mono text-muted-foreground'>{d.phone}</TableCell>
                      <TableCell>
                        <Badge variant={d.active ? 'default' : 'secondary'} className='text-[10px]'>
                          {d.active ? 'Actif' : 'Inactif'}
                        </Badge>
                      </TableCell>
                      <TableCell className='text-right text-sm tabular-nums'>{d.totalDeliveries}</TableCell>
                      <TableCell className='text-right text-sm tabular-nums'>{d.delivered}</TableCell>
                      <TableCell className='text-right'>
                        {rate !== null ? (
                          <span className={`text-sm font-semibold tabular-nums ${
                            rate >= 80 ? 'text-emerald-600' :
                            rate >= 60 ? 'text-amber-600' : 'text-red-600'
                          }`}>
                            {rate}%
                          </span>
                        ) : (
                          <span className='text-sm text-muted-foreground'>—</span>
                        )}
                      </TableCell>
                      <TableCell className='text-right'>
                        <div className='flex justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity'>
                          <Button size='icon' variant='ghost' className='h-7 w-7'
                            title='Performance'
                            onClick={() => { setPerfDriver(d); setPerfPeriod('month') }}>
                            <BarChart2 className='h-3.5 w-3.5' />
                          </Button>
                          <Button size='icon' variant='ghost' className='h-7 w-7'
                            onClick={() => { setSelected(d); setEditForm({ name: d.name, phone: d.phone }); setEditOpen(true) }}>
                            <PencilIcon className='h-3.5 w-3.5' />
                          </Button>
                          <Button size='icon' variant='ghost' className='h-7 w-7'
                            onClick={() => { setSelected(d); setNewPassword(''); setPwdOpen(true) }}>
                            <KeyRoundIcon className='h-3.5 w-3.5' />
                          </Button>
                          <Button size='icon' variant='ghost' className={`h-7 w-7 ${d.active ? 'text-destructive' : 'text-emerald-600'}`}
                            onClick={() => toggleStatus.mutate({ id: d.id, active: !d.active })}>
                            <PowerIcon className='h-3.5 w-3.5' />
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  )
                })
              )}
            </TableBody>
          </Table>
        </div>

        {/* ── Pagination ────────────────────────────────────────────────── */}
        {totalPages > 1 && (
          <div className='mt-3 flex items-center justify-between text-xs text-muted-foreground'>
            <span>
              {page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, filtered.length)} sur {filtered.length}
            </span>
            <div className='flex items-center gap-1'>
              <Button variant='outline' size='icon' className='h-7 w-7'
                disabled={page === 0} onClick={() => setPage(p => p - 1)}>
                <ChevronLeftIcon className='h-3.5 w-3.5' />
              </Button>
              <span className='px-2'>Page {page + 1} / {totalPages}</span>
              <Button variant='outline' size='icon' className='h-7 w-7'
                disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>
                <ChevronRightIcon className='h-3.5 w-3.5' />
              </Button>
            </div>
          </div>
        )}
      </Main>

      {/* ── Create driver ────────────────────────────────────────────────── */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent className='max-w-sm'>
          <DialogHeader>
            <DialogTitle>Nouveau chauffeur</DialogTitle>
          </DialogHeader>
          <div className='grid gap-4 py-1'>
            <div className='grid gap-2'>
              <Label>Nom complet *</Label>
              <Input
                value={createForm.name}
                onChange={e => setCreateForm(p => ({ ...p, name: e.target.value }))}
                placeholder='Ahmed Ben Ali'
              />
            </div>
            <div className='grid gap-2'>
              <Label>Téléphone *</Label>
              <Input
                value={createForm.phone}
                onChange={e => setCreateForm(p => ({ ...p, phone: e.target.value }))}
                placeholder='+216 XX XXX XXX'
              />
            </div>
            <div className='grid gap-2'>
              <Label>Mot de passe initial *</Label>
              <Input
                type='password'
                value={createForm.password}
                onChange={e => setCreateForm(p => ({ ...p, password: e.target.value }))}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant='outline' onClick={() => setCreateOpen(false)}>Annuler</Button>
            <Button
              disabled={!createForm.name || !createForm.phone || !createForm.password || create.isPending}
              onClick={() => create.mutate(createForm)}>
              {create.isPending ? 'Création…' : 'Créer'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ── Edit driver ──────────────────────────────────────────────────── */}
      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent className='max-w-sm'>
          <DialogHeader>
            <DialogTitle>Modifier · {selected?.name}</DialogTitle>
          </DialogHeader>
          <div className='grid gap-4 py-1'>
            <div className='grid gap-2'>
              <Label>Nom complet</Label>
              <Input
                value={editForm.name}
                onChange={e => setEditForm(p => ({ ...p, name: e.target.value }))}
              />
            </div>
            <div className='grid gap-2'>
              <Label>Téléphone</Label>
              <Input
                value={editForm.phone}
                onChange={e => setEditForm(p => ({ ...p, phone: e.target.value }))}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant='outline' onClick={() => setEditOpen(false)}>Annuler</Button>
            <Button disabled={update.isPending} onClick={() => update.mutate(editForm)}>
              {update.isPending ? 'Sauvegarde…' : 'Sauvegarder'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ── Performance drawer ──────────────────────────────────────────── */}
      <Sheet open={!!perfDriver} onOpenChange={o => { if (!o) setPerfDriver(null) }}>
        <SheetContent className='w-full sm:max-w-md overflow-y-auto'>
          {perfDriver && (
            <>
              <SheetHeader className='pb-4'>
                <SheetTitle className='flex items-center gap-2'>
                  <div className='flex h-8 w-8 items-center justify-center rounded-full bg-muted text-sm font-semibold'>
                    {perfDriver.name.charAt(0).toUpperCase()}
                  </div>
                  {perfDriver.name}
                </SheetTitle>
                <p className='text-xs text-muted-foreground'>{perfDriver.phone}</p>
              </SheetHeader>

              <Separator />

              {/* Period selector */}
              <div className='py-4 flex items-center justify-between'>
                <span className='text-sm font-medium'>Période d'analyse</span>
                <Select value={perfPeriod} onValueChange={v => setPerfPeriod(v as any)}>
                  <SelectTrigger className='h-8 w-36 text-xs'>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value='week'>Cette semaine</SelectItem>
                    <SelectItem value='month'>Ce mois</SelectItem>
                    <SelectItem value='all'>Toute la période</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              {/* KPI cards */}
              <div className='grid grid-cols-2 gap-3 mb-4'>
                {/* Total */}
                <div className='rounded-lg border p-3'>
                  <div className='flex items-center gap-1.5 mb-1.5'>
                    <Package className='h-3.5 w-3.5 text-muted-foreground' />
                    <span className='text-xs text-muted-foreground'>Total livraisons</span>
                  </div>
                  <p className='text-2xl font-bold tabular-nums'>{perfDriver.totalDeliveries}</p>
                </div>

                {/* Success rate */}
                <div className='rounded-lg border p-3'>
                  <div className='flex items-center gap-1.5 mb-1.5'>
                    <TrendingUp className='h-3.5 w-3.5 text-muted-foreground' />
                    <span className='text-xs text-muted-foreground'>Taux de succès</span>
                  </div>
                  <p className={`text-2xl font-bold tabular-nums ${
                    perfDriver.totalDeliveries === 0 ? 'text-muted-foreground' :
                    Math.round(perfDriver.delivered / perfDriver.totalDeliveries * 100) >= 80 ? 'text-emerald-600' :
                    Math.round(perfDriver.delivered / perfDriver.totalDeliveries * 100) >= 60 ? 'text-amber-600' :
                    'text-red-600'
                  }`}>
                    {perfDriver.totalDeliveries > 0
                      ? `${Math.round(perfDriver.delivered / perfDriver.totalDeliveries * 100)}%`
                      : '—'}
                  </p>
                </div>

                {/* Réussies */}
                <div className='rounded-lg border p-3'>
                  <div className='flex items-center gap-1.5 mb-1.5'>
                    <CheckCircle2 className='h-3.5 w-3.5 text-emerald-500' />
                    <span className='text-xs text-muted-foreground'>Réussies</span>
                  </div>
                  <p className='text-2xl font-bold tabular-nums text-emerald-600'>{perfDriver.delivered}</p>
                </div>

                {/* Échouées */}
                <div className='rounded-lg border p-3'>
                  <div className='flex items-center gap-1.5 mb-1.5'>
                    <XCircle className='h-3.5 w-3.5 text-red-500' />
                    <span className='text-xs text-muted-foreground'>Échouées</span>
                  </div>
                  <p className='text-2xl font-bold tabular-nums text-red-600'>{perfDriver.failed}</p>
                </div>
              </div>

              {/* Success bar */}
              {perfDriver.totalDeliveries > 0 && (
                <div className='mb-6'>
                  <div className='flex justify-between text-xs text-muted-foreground mb-1.5'>
                    <span>Répartition</span>
                    <span>{perfDriver.delivered} / {perfDriver.totalDeliveries}</span>
                  </div>
                  <div className='h-2 w-full rounded-full bg-muted overflow-hidden flex'>
                    <div
                      className='h-full bg-emerald-500'
                      style={{ width: `${(perfDriver.delivered / perfDriver.totalDeliveries) * 100}%` }}
                    />
                    <div
                      className='h-full bg-red-400'
                      style={{ width: `${(perfDriver.failed / perfDriver.totalDeliveries) * 100}%` }}
                    />
                  </div>
                  <div className='flex gap-4 mt-1.5 text-[10px] text-muted-foreground'>
                    <span className='flex items-center gap-1'><span className='h-1.5 w-1.5 rounded-full bg-emerald-500 inline-block' />Réussies</span>
                    <span className='flex items-center gap-1'><span className='h-1.5 w-1.5 rounded-full bg-red-400 inline-block' />Échouées</span>
                    <span className='flex items-center gap-1'><span className='h-1.5 w-1.5 rounded-full bg-muted-foreground/30 inline-block' />Autres</span>
                  </div>
                </div>
              )}

              <Separator />

              {/* PDF download */}
              <div className='pt-4 space-y-2'>
                <p className='text-xs text-muted-foreground'>Rapport PDF complet avec historique et tendances</p>
                <Button
                  className='w-full'
                  disabled={pdfLoading}
                  onClick={() => downloadPdf(perfDriver, perfPeriod)}>
                  {pdfLoading ? (
                    <span className='flex items-center gap-2'>
                      <span className='h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent' />
                      Génération…
                    </span>
                  ) : (
                    <span className='flex items-center gap-2'>
                      <FileDown className='h-4 w-4' />
                      Télécharger le rapport PDF
                    </span>
                  )}
                </Button>
              </div>
            </>
          )}
        </SheetContent>
      </Sheet>

      {/* ── Reset password ───────────────────────────────────────────────── */}
      <Dialog open={pwdOpen} onOpenChange={setPwdOpen}>
        <DialogContent className='max-w-sm'>
          <DialogHeader>
            <DialogTitle>Réinitialiser le mot de passe</DialogTitle>
          </DialogHeader>
          <p className='text-sm text-muted-foreground'>Chauffeur : <strong>{selected?.name}</strong></p>
          <div className='grid gap-2'>
            <Label>Nouveau mot de passe</Label>
            <Input
              type='password'
              value={newPassword}
              onChange={e => setNewPassword(e.target.value)}
              placeholder='Minimum 6 caractères'
            />
          </div>
          <DialogFooter>
            <Button variant='outline' onClick={() => setPwdOpen(false)}>Annuler</Button>
            <Button
              disabled={newPassword.length < 6 || resetPwd.isPending}
              onClick={() => resetPwd.mutate()}>
              {resetPwd.isPending ? 'Réinitialisation…' : 'Réinitialiser'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
