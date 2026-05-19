'use client'

import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { PlusIcon, PencilIcon, TrashIcon, Truck, Wrench, Ban, CheckCircle2 } from 'lucide-react'
import { api } from '@/lib/api'
import { Header } from '@/components/layout/header'
import { Main } from '@/components/layout/main'
import { ProfileDropdown } from '@/components/profile-dropdown'
import { ThemeSwitch } from '@/components/theme-switch'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog'
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel,
  AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

type VehicleStatus = 'AVAILABLE' | 'IN_MAINTENANCE' | 'OUT_OF_SERVICE'

interface Vehicle {
  id: string
  name: string
  plate: string
  make: string
  model: string
  type: string
  payloadKg?: number
  active: boolean
  assigned: boolean
  vehicleStatus: VehicleStatus
}

const STATUS_CONFIG: Record<VehicleStatus, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline'; icon: React.ElementType }> = {
  AVAILABLE:       { label: 'Disponible',       variant: 'default',     icon: CheckCircle2 },
  IN_MAINTENANCE:  { label: 'En maintenance',   variant: 'secondary',   icon: Wrench },
  OUT_OF_SERVICE:  { label: 'Hors service',     variant: 'destructive', icon: Ban },
}

function StatusBadge({ status, assigned }: { status?: VehicleStatus; assigned: boolean }) {
  if (assigned) return <Badge variant='default'>En tournée</Badge>
  if (!status) return <Badge variant='secondary'>Disponible</Badge>
  const cfg = STATUS_CONFIG[status]
  const Icon = cfg.icon
  return (
    <Badge variant={cfg.variant} className='flex items-center gap-1 w-fit'>
      <Icon className='h-3 w-3' />
      {cfg.label}
    </Badge>
  )
}

const emptyForm = { make: '', model: '', manufactureYear: '', plate: '', type: 'VAN', payloadKg: '', fuelType: '' }

export function Vehicles() {
  const qc = useQueryClient()
  const [open, setOpen]         = useState(false)
  const [deleteId, setDeleteId] = useState<string | null>(null)
  const [editing, setEditing]   = useState<Vehicle | null>(null)
  const [form, setForm]         = useState({ ...emptyForm })

  const { data: vehicles = [], isLoading } = useQuery<Vehicle[]>({
    queryKey: ['admin-vehicles'],
    queryFn: () => api.get('/api/admin/vehicles').then(r => r.data),
  })

  const save = useMutation({
    mutationFn: (payload: typeof emptyForm) =>
      editing
        ? api.put(`/api/admin/vehicles/${editing.id}`, payload)
        : api.post('/api/admin/vehicles', payload),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-vehicles'] })
      toast.success(editing ? 'Véhicule mis à jour' : 'Véhicule créé')
      setOpen(false)
    },
    onError: (e: { response?: { data?: { message?: string } } }) =>
      toast.error(e.response?.data?.message ?? 'Échec de la sauvegarde'),
  })

  const remove = useMutation({
    mutationFn: (id: string) => api.delete(`/api/admin/vehicles/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-vehicles'] })
      toast.success('Véhicule supprimé')
      setDeleteId(null)
    },
    onError: () => toast.error('Échec de la suppression'),
  })

  const changeStatus = useMutation({
    mutationFn: ({ id, status }: { id: string; status: VehicleStatus }) =>
      api.patch(`/api/admin/vehicles/${id}/status`, { status }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-vehicles'] })
      toast.success('Statut mis à jour')
    },
    onError: (e: { response?: { data?: { message?: string } } }) =>
      toast.error(e.response?.data?.message ?? 'Échec du changement de statut'),
  })

  function openCreate() {
    setEditing(null)
    setForm({ ...emptyForm })
    setOpen(true)
  }

  function openEdit(v: Vehicle) {
    setEditing(v)
    setForm({ make: v.make, model: v.model, manufactureYear: '', plate: v.plate, type: v.type, payloadKg: String(v.payloadKg ?? ''), fuelType: '' })
    setOpen(true)
  }

  return (
    <>
      <Header fixed>
        <span className='font-semibold'>Véhicules</span>
        <div className='ms-auto flex items-center gap-2'>
          <ThemeSwitch />
          <ProfileDropdown />
        </div>
      </Header>

      <Main>
        <div className='mb-4 flex items-center justify-between'>
          <div>
            <h2 className='text-2xl font-bold tracking-tight'>Flotte</h2>
            <p className='text-muted-foreground'>Gérer la flotte de véhicules ASM.</p>
          </div>
          <Button onClick={openCreate}><PlusIcon className='mr-2 h-4 w-4' />Ajouter</Button>
        </div>

        <div className='rounded-md border'>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Véhicule</TableHead>
                <TableHead>Plaque</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Charge max</TableHead>
                <TableHead>Disponibilité</TableHead>
                <TableHead className='text-right'>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading && <TableRow><TableCell colSpan={6} className='text-center py-8 text-muted-foreground'>Chargement…</TableCell></TableRow>}
              {vehicles.map((v) => (
                <TableRow key={v.id}>
                  <TableCell>
                    <div className='flex items-center gap-2'>
                      <Truck className='h-4 w-4 text-muted-foreground' />
                      <span className='font-medium'>{v.name}</span>
                    </div>
                  </TableCell>
                  <TableCell className='font-mono text-sm'>{v.plate}</TableCell>
                  <TableCell>{v.type}</TableCell>
                  <TableCell>{v.payloadKg ? `${v.payloadKg} kg` : '—'}</TableCell>
                  <TableCell>
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild disabled={v.assigned}>
                        <button className='focus:outline-none'>
                          <StatusBadge status={v.vehicleStatus} assigned={v.assigned} />
                        </button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align='start'>
                        {(Object.keys(STATUS_CONFIG) as VehicleStatus[]).map(s => {
                          const cfg = STATUS_CONFIG[s]
                          const Icon = cfg.icon
                          return (
                            <DropdownMenuItem key={s}
                              className={v.vehicleStatus === s ? 'font-semibold' : ''}
                              onClick={() => changeStatus.mutate({ id: v.id, status: s })}>
                              <Icon className='mr-2 h-3.5 w-3.5' />{cfg.label}
                            </DropdownMenuItem>
                          )
                        })}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </TableCell>
                  <TableCell className='text-right'>
                    <div className='flex justify-end gap-1'>
                      <Button size='icon' variant='ghost' onClick={() => openEdit(v)}>
                        <PencilIcon className='h-4 w-4' />
                      </Button>
                      <Button size='icon' variant='ghost' className='text-destructive'
                        onClick={() => setDeleteId(v.id)}>
                        <TrashIcon className='h-4 w-4' />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </Main>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{editing ? 'Modifier le véhicule' : 'Nouveau véhicule'}</DialogTitle></DialogHeader>
          <div className='grid gap-4 py-2'>
            <div className='grid grid-cols-2 gap-4'>
              <div className='grid gap-2'><Label>Marque *</Label>
                <Input value={form.make} onChange={e => setForm(p => ({ ...p, make: e.target.value }))} placeholder='Renault' /></div>
              <div className='grid gap-2'><Label>Modèle *</Label>
                <Input value={form.model} onChange={e => setForm(p => ({ ...p, model: e.target.value }))} placeholder='Master' /></div>
            </div>
            <div className='grid grid-cols-2 gap-4'>
              <div className='grid gap-2'><Label>Plaque *</Label>
                <Input value={form.plate} onChange={e => setForm(p => ({ ...p, plate: e.target.value }))} placeholder='123 TN 456' /></div>
              <div className='grid gap-2'><Label>Année</Label>
                <Input type='number' value={form.manufactureYear} onChange={e => setForm(p => ({ ...p, manufactureYear: e.target.value }))} /></div>
            </div>
            <div className='grid grid-cols-2 gap-4'>
              <div className='grid gap-2'>
                <Label>Type</Label>
                <Select value={form.type} onValueChange={v => setForm(p => ({ ...p, type: v }))}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value='VAN'>Van</SelectItem>
                    <SelectItem value='TRUCK'>Truck</SelectItem>
                    <SelectItem value='MOTO'>Moto</SelectItem>
                    <SelectItem value='CAR'>Voiture</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className='grid gap-2'><Label>Charge max (kg)</Label>
                <Input type='number' value={form.payloadKg} onChange={e => setForm(p => ({ ...p, payloadKg: e.target.value }))} /></div>
            </div>
          </div>
          <DialogFooter>
            <Button variant='outline' onClick={() => setOpen(false)}>Annuler</Button>
            <Button disabled={!form.make || !form.model || !form.plate || save.isPending}
              onClick={() => save.mutate(form)}>
              {save.isPending ? 'Sauvegarde…' : 'Sauvegarder'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog open={!!deleteId} onOpenChange={() => setDeleteId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Supprimer ce véhicule ?</AlertDialogTitle>
            <AlertDialogDescription>Cette action est irréversible.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Annuler</AlertDialogCancel>
            <AlertDialogAction className='bg-destructive text-destructive-foreground hover:bg-destructive/90'
              onClick={() => deleteId && remove.mutate(deleteId)}>Supprimer</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
