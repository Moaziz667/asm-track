const ASM_LOGO = 'https://asm-tunisie.com/wp-content/uploads/2023/07/logo-asm-2020.png'

type AuthLayoutProps = {
  children: React.ReactNode
}

export function AuthLayout({ children }: AuthLayoutProps) {
  return (
    <div className='min-h-svh grid lg:grid-cols-2'>
      {/* Left panel — branding */}
      <div className='hidden lg:flex flex-col justify-between bg-zinc-950 p-12'>
        <div className='flex items-center gap-3'>
          <img src={ASM_LOGO} alt='ASM Track' className='h-10 w-auto object-contain brightness-0 invert' />
        </div>
        <div>
          <blockquote className='space-y-3'>
            <p className='text-lg leading-relaxed text-zinc-300'>
              Plateforme de gestion opérationnelle des tournées et de supervision de flotte en temps réel.
            </p>
            <footer className='text-sm text-zinc-500'>ASM Track — Panneau Super Admin</footer>
          </blockquote>
        </div>
        <p className='text-xs text-zinc-600'>
          © {new Date().getFullYear()} ASM Tunisie. Tous droits réservés.
        </p>
      </div>

      {/* Right panel — form */}
      <div className='flex flex-col items-center justify-center p-8'>
        <div className='w-full max-w-sm space-y-8'>
          {/* Mobile logo */}
          <div className='flex items-center justify-center lg:hidden'>
            <img src={ASM_LOGO} alt='ASM Track' className='h-10 w-auto object-contain' />
          </div>

          {children}
        </div>
      </div>
    </div>
  )
}
