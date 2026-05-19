import { useState } from 'react'
import { z } from 'zod'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useNavigate } from '@tanstack/react-router'
import { Loader2, LogIn } from 'lucide-react'
import { toast } from 'sonner'
import { api } from '@/lib/api'
import { useAuthStore } from '@/stores/auth-store'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { PasswordInput } from '@/components/password-input'

const formSchema = z.object({
  email:    z.email({ error: (i) => (i.input === '' ? 'Veuillez saisir votre email.' : undefined) }),
  password: z.string().min(1, 'Veuillez saisir votre mot de passe.'),
})

interface UserAuthFormProps extends React.HTMLAttributes<HTMLFormElement> {
  redirectTo?: string
}

export function UserAuthForm({ className, redirectTo, ...props }: UserAuthFormProps) {
  const [isLoading, setIsLoading] = useState(false)
  const navigate = useNavigate()
  const { auth } = useAuthStore()

  const form = useForm<z.infer<typeof formSchema>>({
    resolver: zodResolver(formSchema),
    defaultValues: { email: '', password: '' },
  })

  async function onSubmit(data: z.infer<typeof formSchema>) {
    setIsLoading(true)
    try {
      const res = await api.post('/api/auth/admin/login', {
        email:    data.email,
        password: data.password,
      })

      const user = res.data.user ?? res.data
      if (user.companyId) {
        toast.error('Ce panneau est réservé aux super-admins ASM.')
        return
      }

      auth.setUser({
        accountNo: user.id ?? '',
        email:     user.email ?? data.email,
        role:      [user.role ?? 'ADMIN'],
        exp:       Date.now() + 24 * 60 * 60 * 1000,
      })
      auth.setAccessToken(res.data.token ?? 'authenticated')

      navigate({ to: redirectTo || '/', replace: true })
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message
      toast.error(msg ?? 'Identifiants incorrects')
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <Form {...form}>
      <form
        onSubmit={form.handleSubmit(onSubmit)}
        className={cn('grid gap-4', className)}
        {...props}
      >
        <FormField
          control={form.control}
          name='email'
          render={({ field }) => (
            <FormItem>
              <FormLabel>Adresse email</FormLabel>
              <FormControl>
                <Input placeholder='admin@asm-delivery.com' autoComplete='email' {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={form.control}
          name='password'
          render={({ field }) => (
            <FormItem>
              <FormLabel>Mot de passe</FormLabel>
              <FormControl>
                <PasswordInput placeholder='••••••••' autoComplete='current-password' {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <Button className='mt-1 w-full' disabled={isLoading}>
          {isLoading ? (
            <Loader2 className='mr-2 h-4 w-4 animate-spin' />
          ) : (
            <LogIn className='mr-2 h-4 w-4' />
          )}
          {isLoading ? 'Connexion…' : 'Se connecter'}
        </Button>
      </form>
    </Form>
  )
}
