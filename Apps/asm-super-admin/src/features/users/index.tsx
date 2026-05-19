'use client'

import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { PlusIcon, PowerIcon } from 'lucide-react'
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
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

interface Company { id: string; name: string }
interface AdminUser {
  id: string; name: string; email: string; role: string
  companyId: string | null; active: boolean
}

const emptyForm = { name: '', email: '', password: '', role: 'ADMIN', companyId: '' }

export function CompanyUsers() {
  const qc = useQueryClient()
  const [open, setOpen]         = useState(false)
  const [form, setForm]         = useState({ ...emptyForm })

  const { data: companies = [] } = useQuery<Company[]>({
    queryKey: ['companies'],
    queryFn: () => api.get('/api/admin/companies').then(r => r.data),
  })

  const { data: users = [], isLoading } = useQuery<AdminUser[]>({
    queryKey: ['admin-users'],
    queryFn: () => api.get('/api/admin/users').then(r => r.data),
  })

  const create = useMutation({
    mutationFn: (b: typeof emptyForm) => api.post('/api/admin/users', {
      ...b, companyId: b.companyId || null,
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-users'] })
      toast.success('User created')
      setOpen(false)
      setForm({ ...emptyForm })
    },
    onError: (e: { response?: { data?: { message?: string } } }) =>
      toast.error(e.response?.data?.message ?? 'Failed to create user'),
  })

  const toggleStatus = useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) =>
      api.patch(`/api/admin/users/${id}/status`, { active }),
    onSuccess: (_, { active }) => {
      qc.invalidateQueries({ queryKey: ['admin-users'] })
      toast.success(active ? 'User activated' : 'User deactivated')
    },
    onError: () => toast.error('Failed to update status'),
  })

  const companyName = (id: string | null) =>
    companies.find(c => c.id === id)?.name ?? '—'

  return (
    <>
      <Header fixed>
        <span className='font-semibold'>Company Users</span>
        <div className='ms-auto flex items-center gap-2'>
          <ThemeSwitch />
          <ProfileDropdown />
        </div>
      </Header>

      <Main>
        <div className='mb-4 flex items-center justify-between'>
          <div>
            <h2 className='text-2xl font-bold tracking-tight'>Admin Users</h2>
            <p className='text-muted-foreground'>Manage company admin accounts.</p>
          </div>
          <Button onClick={() => setOpen(true)}>
            <PlusIcon className='mr-2 h-4 w-4' />Add User
          </Button>
        </div>

        <div className='rounded-md border'>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Email</TableHead>
                <TableHead>Role</TableHead>
                <TableHead>Company</TableHead>
                <TableHead>Status</TableHead>
              <TableHead className='text-right'>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading && <TableRow><TableCell colSpan={5} className='text-center'>Loading…</TableCell></TableRow>}
              {users.map((u) => (
                <TableRow key={u.id}>
                  <TableCell className='font-medium'>{u.name}</TableCell>
                  <TableCell>{u.email}</TableCell>
                  <TableCell>
                    <Badge variant={u.role === 'SUPER_ADMIN' ? 'default' : 'secondary'}>{u.role}</Badge>
                  </TableCell>
                  <TableCell>{companyName(u.companyId)}</TableCell>
                  <TableCell>
                    <Badge variant={u.active ? 'default' : 'destructive'}>
                      {u.active ? 'Active' : 'Inactive'}
                    </Badge>
                  </TableCell>
                  <TableCell className='text-right'>
                    {u.role !== 'SUPER_ADMIN' && (
                      <Button size='icon' variant='ghost'
                        className={u.active ? 'text-destructive' : 'text-green-600'}
                        title={u.active ? 'Deactivate' : 'Activate'}
                        onClick={() => toggleStatus.mutate({ id: u.id, active: !u.active })}>
                        <PowerIcon className='h-4 w-4' />
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </Main>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>New Admin User</DialogTitle></DialogHeader>
          <div className='grid gap-4 py-2'>
            <div className='grid gap-2'><Label>Name *</Label>
              <Input value={form.name} onChange={e => setForm(p => ({ ...p, name: e.target.value }))} /></div>
            <div className='grid gap-2'><Label>Email *</Label>
              <Input type='email' value={form.email} onChange={e => setForm(p => ({ ...p, email: e.target.value }))} /></div>
            <div className='grid gap-2'><Label>Password *</Label>
              <Input type='password' value={form.password} onChange={e => setForm(p => ({ ...p, password: e.target.value }))} /></div>
            <div className='grid grid-cols-2 gap-4'>
              <div className='grid gap-2'>
                <Label>Role</Label>
                <Select value={form.role} onValueChange={v => setForm(p => ({ ...p, role: v }))}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value='ADMIN'>Admin</SelectItem>
                    <SelectItem value='DISPATCHER'>Dispatcher</SelectItem>
                    <SelectItem value='MANAGER'>Manager</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className='grid gap-2'>
                <Label>Company</Label>
                <Select value={form.companyId} onValueChange={v => setForm(p => ({ ...p, companyId: v }))}>
                  <SelectTrigger><SelectValue placeholder='Select…' /></SelectTrigger>
                  <SelectContent>
                    {companies.map(c => (
                      <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant='outline' onClick={() => setOpen(false)}>Cancel</Button>
            <Button
              disabled={!form.name || !form.email || !form.password || !form.companyId || create.isPending}
              onClick={() => create.mutate(form)}>
              {create.isPending ? 'Creating…' : 'Create'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
