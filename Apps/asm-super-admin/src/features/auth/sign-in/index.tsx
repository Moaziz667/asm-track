import { useSearch } from '@tanstack/react-router'
import { AuthLayout } from '../auth-layout'
import { UserAuthForm } from './components/user-auth-form'

export function SignIn() {
  const { redirect } = useSearch({ from: '/(auth)/sign-in' })

  return (
    <AuthLayout>
      <div className='space-y-6'>
        <div className='space-y-1'>
          <h1 className='text-2xl font-semibold tracking-tight'>Connexion</h1>
          <p className='text-sm text-muted-foreground'>
            Accès réservé aux administrateurs ASM Track.
          </p>
        </div>

        <UserAuthForm redirectTo={redirect} />

        <p className='text-center text-xs text-muted-foreground'>
          Accès restreint — Super Admin uniquement
        </p>
      </div>
    </AuthLayout>
  )
}
