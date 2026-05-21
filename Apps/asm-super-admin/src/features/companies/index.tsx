'use client'

import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  PlusIcon, PencilIcon, TrashIcon, UploadIcon,
  Building2, RotateCcwIcon, SearchIcon, ChevronLeftIcon, ChevronRightIcon,
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
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog'
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table'
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel,
  AlertDialogContent, AlertDialogDescription,
  AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

// ── Types ─────────────────────────────────────────────────────────────────────

interface Company {
  id: string
  name: string
  logoUrl?: string
  address?: string
  primaryColor?: string
  supportEmail?: string
  erpType?: string
  erpApiUrl?: string
  erpApiKey?: string
  erpDbName?: string
  erpUsername?: string
  erpUid?: number
  active: boolean
}

const empty: Omit<Company, 'id' | 'active'> = {
  name: '', address: '', primaryColor: '#4F46E5', supportEmail: '',
  erpType: 'NONE', erpApiUrl: '', erpApiKey: '', erpDbName: '', erpUsername: '', erpUid: 2,
}

const PAGE_SIZE = 12

// ── Component ─────────────────────────────────────────────────────────────────

export function Companies() {
  const qc = useQueryClient()

  // UI state
  const [open, setOpen]         = useState(false)
  const [deleteId, setDeleteId] = useState<string | null>(null)
  const [editing, setEditing]   = useState<Company | null>(null)
  const [form, setForm]         = useState<typeof empty>({ ...empty })

  // Filters
  const [search, setSearch]         = useState('')
  const [statusFilter, setStatus]   = useState<'all' | 'active' | 'inactive'>('all')
  const [erpFilter, setErp]         = useState<string>('all')
  const [page, setPage]             = useState(0)

  // Data
  const { data: companies = [], isLoading } = useQuery<Company[]>({
    queryKey: ['companies'],
    queryFn: () => api.get('/api/admin/companies').then(r => r.data),
  })

  // Mutations
  const save = useMutation({
    mutationFn: (payload: typeof empty) =>
      editing
        ? api.put(`/api/admin/companies/${editing.id}`, payload)
        : api.post('/api/admin/companies', { ...payload, active: true }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['companies'] })
      toast.success(editing ? 'Entreprise mise à jour' : 'Entreprise créée')
      setOpen(false)
    },
    onError: () => toast.error('Échec de la sauvegarde'),
  })

  const deactivate = useMutation({
    mutationFn: (id: string) => api.delete(`/api/admin/companies/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['companies'] })
      toast.success('Entreprise désactivée')
      setDeleteId(null)
    },
    onError: () => toast.error('Échec de la désactivation'),
  })

  const reactivate = useMutation({
    mutationFn: (id: string) => api.put(`/api/admin/companies/${id}`, { active: true }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['companies'] })
      toast.success('Entreprise réactivée')
    },
    onError: () => toast.error('Échec de la réactivation'),
  })

  const uploadLogo = useMutation({
    mutationFn: ({ id, file }: { id: string; file: File }) => {
      const fd = new FormData()
      fd.append('file', file)
      return api.post(`/api/admin/companies/${id}/logo`, fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
    },
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ['companies'] })
      toast.success('Logo mis à jour')
      if (res.data) setEditing(res.data)
    },
    onError: () => toast.error('Échec du téléversement'),
  })

  // Filtering + pagination
  const erpTypes = useMemo(() => {
    const types = [...new Set(companies.map(c => c.erpType ?? 'NONE'))]
    return types.filter(Boolean)
  }, [companies])

  const filtered = useMemo(() => {
    const q = search.toLowerCase().trim()
    return companies.filter(c => {
      if (q && !c.name.toLowerCase().includes(q) && !(c.address ?? '').toLowerCase().includes(q)) return false
      if (statusFilter === 'active'   && !c.active) return false
      if (statusFilter === 'inactive' &&  c.active) return false
      if (erpFilter !== 'all' && (c.erpType ?? 'NONE') !== erpFilter) return false
      return true
    })
  }, [companies, search, statusFilter, erpFilter])

  const totalPages = Math.ceil(filtered.length / PAGE_SIZE)
  const paged      = filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)

  function resetFilters() {
    setSearch(''); setStatus('all'); setErp('all'); setPage(0)
  }

  function openCreate() {
    setEditing(null)
    setForm({ ...empty })
    setOpen(true)
  }

  function openEdit(c: Company) {
    setEditing(c)
    setForm({
      name: c.name, address: c.address ?? '',
      primaryColor: c.primaryColor ?? '#4F46E5',
      supportEmail: c.supportEmail ?? '',
      erpType: c.erpType ?? 'NONE', erpApiUrl: c.erpApiUrl ?? '',
      erpApiKey: c.erpApiKey ?? '', erpDbName: c.erpDbName ?? '',
      erpUsername: c.erpUsername ?? '', erpUid: c.erpUid ?? 2,
    })
    setOpen(true)
  }

  const hasFilters = search || statusFilter !== 'all' || erpFilter !== 'all'

  return (
    <>
      <Header fixed>
        <div className='flex items-center gap-2'>
          <span className='text-sm font-semibold'>Entreprises</span>
          <Separator orientation='vertical' className='h-4' />
          <span className='text-xs text-muted-foreground'>Gestion des entreprises clientes</span>
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
            <h1 className='text-xl font-semibold tracking-tight'>Entreprises</h1>
            <p className='text-xs text-muted-foreground mt-0.5'>
              {companies.filter(c => c.active).length} actives · {companies.filter(c => !c.active).length} inactives
            </p>
          </div>
          <Button size='sm' onClick={openCreate}>
            <PlusIcon className='mr-1.5 h-3.5 w-3.5' />
            Nouvelle entreprise
          </Button>
        </div>

        {/* ── Toolbar ──────────────────────────────────────────────────── */}
        <div className='mb-4 flex flex-wrap items-center gap-2'>
          <div className='relative flex-1 min-w-48 max-w-xs'>
            <SearchIcon className='absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground' />
            <Input
              placeholder='Rechercher une entreprise…'
              value={search}
              onChange={e => { setSearch(e.target.value); setPage(0) }}
              className='pl-8 h-8 text-sm'
            />
          </div>

          <Select value={statusFilter} onValueChange={v => { setStatus(v as any); setPage(0) }}>
            <SelectTrigger className='h-8 w-36 text-xs'>
              <SelectValue placeholder='Statut' />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value='all'>Tous les statuts</SelectItem>
              <SelectItem value='active'>Actives</SelectItem>
              <SelectItem value='inactive'>Inactives</SelectItem>
            </SelectContent>
          </Select>

          <Select value={erpFilter} onValueChange={v => { setErp(v); setPage(0) }}>
            <SelectTrigger className='h-8 w-32 text-xs'>
              <SelectValue placeholder='ERP' />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value='all'>Tous ERP</SelectItem>
              {erpTypes.map(t => <SelectItem key={t} value={t}>{t}</SelectItem>)}
            </SelectContent>
          </Select>

          {hasFilters && (
            <Button variant='ghost' size='sm' onClick={resetFilters} className='h-8 text-xs text-muted-foreground'>
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
                <TableHead className='w-10'></TableHead>
                <TableHead className='font-medium'>Entreprise</TableHead>
                <TableHead className='font-medium'>Adresse</TableHead>
                <TableHead className='font-medium'>Support</TableHead>
                <TableHead className='font-medium'>ERP</TableHead>
                <TableHead className='font-medium'>Couleur</TableHead>
                <TableHead className='font-medium'>Statut</TableHead>
                <TableHead className='text-right font-medium'>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                Array.from({ length: 5 }).map((_, i) => (
                  <TableRow key={i}>
                    {Array.from({ length: 8 }).map((_, j) => (
                      <TableCell key={j}>
                        <div className='h-4 animate-pulse rounded bg-muted' />
                      </TableCell>
                    ))}
                  </TableRow>
                ))
              ) : paged.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={8} className='py-16 text-center'>
                    <div className='flex flex-col items-center gap-2'>
                      <Building2 className='h-8 w-8 text-muted-foreground/30' />
                      <p className='text-sm text-muted-foreground'>
                        {hasFilters ? 'Aucun résultat pour ces filtres' : 'Aucune entreprise enregistrée'}
                      </p>
                      {hasFilters && (
                        <Button variant='outline' size='sm' onClick={resetFilters}>
                          Réinitialiser les filtres
                        </Button>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              ) : (
                paged.map(c => (
                  <TableRow key={c.id} className='group'>
                    <TableCell>
                      {c.logoUrl ? (
                        <img src={c.logoUrl} alt={c.name}
                          className='h-8 w-8 rounded object-cover border' />
                      ) : (
                        <div
                          className='flex h-8 w-8 items-center justify-center rounded text-white text-xs font-bold'
                          style={{ background: c.primaryColor ?? '#6366f1' }}>
                          {c.name.charAt(0).toUpperCase()}
                        </div>
                      )}
                    </TableCell>
                    <TableCell>
                      <span className='font-medium text-sm'>{c.name}</span>
                    </TableCell>
                    <TableCell className='text-sm text-muted-foreground max-w-36 truncate'>
                      {c.address || '—'}
                    </TableCell>
                    <TableCell className='text-sm text-muted-foreground'>
                      {c.supportEmail || '—'}
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant={c.erpType && c.erpType !== 'NONE' ? 'default' : 'secondary'}
                        className='text-[10px]'>
                        {c.erpType ?? 'NONE'}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <div className='flex items-center gap-2'>
                        <div
                          className='h-4 w-4 rounded border'
                          style={{ background: c.primaryColor ?? '#6366f1' }}
                        />
                        <span className='text-xs font-mono text-muted-foreground'>
                          {c.primaryColor ?? '—'}
                        </span>
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant={c.active ? 'default' : 'secondary'}
                        className='text-[10px]'>
                        {c.active ? 'Active' : 'Inactive'}
                      </Badge>
                    </TableCell>
                    <TableCell className='text-right'>
                      <div className='flex justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity'>
                        <label htmlFor={`logo-${c.id}`} className='cursor-pointer'>
                          <input id={`logo-${c.id}`} type='file' accept='image/*' className='hidden'
                            onChange={e => {
                              const file = e.target.files?.[0]
                              if (file) uploadLogo.mutate({ id: c.id, file })
                              e.target.value = ''
                            }} />
                          <Button size='icon' variant='ghost' className='h-7 w-7' title='Logo' asChild>
                            <span><UploadIcon className='h-3.5 w-3.5' /></span>
                          </Button>
                        </label>
                        <Button size='icon' variant='ghost' className='h-7 w-7'
                          onClick={() => openEdit(c)}>
                          <PencilIcon className='h-3.5 w-3.5' />
                        </Button>
                        {c.active ? (
                          <Button size='icon' variant='ghost' className='h-7 w-7 text-destructive'
                            onClick={() => setDeleteId(c.id)}>
                            <TrashIcon className='h-3.5 w-3.5' />
                          </Button>
                        ) : (
                          <Button size='icon' variant='ghost' className='h-7 w-7 text-emerald-600'
                            title='Réactiver' onClick={() => reactivate.mutate(c.id)}>
                            <RotateCcwIcon className='h-3.5 w-3.5' />
                          </Button>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                ))
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

      {/* ── Create / Edit dialog ─────────────────────────────────────────── */}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className='max-w-lg max-h-[90vh] overflow-y-auto'>
          <DialogHeader>
            <DialogTitle>
              {editing ? `Modifier · ${editing.name}` : 'Nouvelle entreprise'}
            </DialogTitle>
          </DialogHeader>

          <div className='grid gap-4 py-1'>
            <div className='grid gap-2'>
              <Label>Raison sociale *</Label>
              <Input
                value={form.name}
                onChange={e => setForm(p => ({ ...p, name: e.target.value }))}
                placeholder='Rapide Express TN'
              />
            </div>

            <div className='grid gap-2'>
              <Label>Adresse</Label>
              <Input
                value={form.address}
                onChange={e => setForm(p => ({ ...p, address: e.target.value }))}
                placeholder='Tunis, Tunisie'
              />
            </div>

            <div className='grid gap-2'>
              <Label>Email support</Label>
              <Input
                type='email'
                value={form.supportEmail}
                onChange={e => setForm(p => ({ ...p, supportEmail: e.target.value }))}
                placeholder='support@entreprise.com'
              />
            </div>

            {editing && (
              <div className='grid gap-2'>
                <Label>Logo</Label>
                <div className='flex items-center gap-3'>
                  {editing.logoUrl ? (
                    <img src={editing.logoUrl} alt='logo'
                      className='h-10 w-10 rounded object-cover border' />
                  ) : (
                    <div
                      className='flex h-10 w-10 items-center justify-center rounded text-white text-sm font-bold border'
                      style={{ background: form.primaryColor ?? '#4F46E5' }}>
                      {form.name.charAt(0).toUpperCase() || 'A'}
                    </div>
                  )}
                  <label className='cursor-pointer'>
                    <input type='file' accept='image/*' className='hidden'
                      onChange={e => {
                        const file = e.target.files?.[0]
                        if (file) uploadLogo.mutate({ id: editing.id, file })
                        e.target.value = ''
                      }} />
                    <span className='inline-flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-xs font-medium hover:bg-muted cursor-pointer'>
                      <UploadIcon className='h-3 w-3' />
                      {uploadLogo.isPending ? 'Téléversement…' : 'Téléverser un logo'}
                    </span>
                  </label>
                </div>
              </div>
            )}

            <div className='grid grid-cols-2 gap-4'>
              <div className='grid gap-2'>
                <Label>Couleur principale</Label>
                <div className='flex items-center gap-2'>
                  <input
                    type='color'
                    value={form.primaryColor}
                    onChange={e => setForm(p => ({ ...p, primaryColor: e.target.value }))}
                    className='h-8 w-10 cursor-pointer rounded border p-0.5'
                  />
                  <span className='text-xs font-mono text-muted-foreground'>{form.primaryColor}</span>
                </div>
              </div>
              <div className='grid gap-2'>
                <Label>Intégration ERP</Label>
                <Select value={form.erpType} onValueChange={v => setForm(p => ({ ...p, erpType: v }))}>
                  <SelectTrigger className='h-8 text-sm'><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value='NONE'>Aucun</SelectItem>
                    <SelectItem value='ODOO'>Odoo</SelectItem>
                    <SelectItem value='DUX'>DUX</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            {form.erpType !== 'NONE' && (
              <div className='grid gap-3 rounded-lg border p-3'>
                <p className='text-xs font-medium text-muted-foreground uppercase tracking-wide'>
                  Configuration {form.erpType}
                </p>
                <div className='grid gap-2'>
                  <Label className='text-xs'>URL API</Label>
                  <Input
                    value={form.erpApiUrl}
                    onChange={e => setForm(p => ({ ...p, erpApiUrl: e.target.value }))}
                    placeholder='http://odoo:8069/jsonrpc'
                    className='h-8 text-sm'
                  />
                </div>
                <div className='grid grid-cols-2 gap-3'>
                  <div className='grid gap-2'>
                    <Label className='text-xs'>Base de données</Label>
                    <Input value={form.erpDbName}
                      onChange={e => setForm(p => ({ ...p, erpDbName: e.target.value }))}
                      className='h-8 text-sm' />
                  </div>
                  <div className='grid gap-2'>
                    <Label className='text-xs'>Utilisateur</Label>
                    <Input value={form.erpUsername}
                      onChange={e => setForm(p => ({ ...p, erpUsername: e.target.value }))}
                      className='h-8 text-sm' />
                  </div>
                </div>
                <div className='grid grid-cols-2 gap-3'>
                  <div className='grid gap-2'>
                    <Label className='text-xs'>Clé API / Mot de passe</Label>
                    <Input type='password' value={form.erpApiKey}
                      onChange={e => setForm(p => ({ ...p, erpApiKey: e.target.value }))}
                      className='h-8 text-sm' />
                  </div>
                  <div className='grid gap-2'>
                    <Label className='text-xs'>UID</Label>
                    <Input type='number' value={form.erpUid}
                      onChange={e => setForm(p => ({ ...p, erpUid: Number(e.target.value) }))}
                      className='h-8 text-sm' />
                  </div>
                </div>
              </div>
            )}
          </div>

          <DialogFooter>
            <Button variant='outline' onClick={() => setOpen(false)}>Annuler</Button>
            <Button disabled={!form.name.trim() || save.isPending} onClick={() => save.mutate(form)}>
              {save.isPending ? 'Sauvegarde…' : 'Sauvegarder'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ── Deactivate confirm ───────────────────────────────────────────── */}
      <AlertDialog open={!!deleteId} onOpenChange={() => setDeleteId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Désactiver cette entreprise ?</AlertDialogTitle>
            <AlertDialogDescription>
              Les administrateurs de cette entreprise ne pourront plus se connecter.
              L'opération est réversible.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Annuler</AlertDialogCancel>
            <AlertDialogAction
              className='bg-destructive text-destructive-foreground hover:bg-destructive/90'
              onClick={() => deleteId && deactivate.mutate(deleteId)}>
              Désactiver
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
