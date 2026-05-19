import {
  SidebarMenu,
  SidebarMenuItem,
} from '@/components/ui/sidebar'

const ASM_LOGO = 'https://asm-tunisie.com/wp-content/uploads/2023/07/logo-asm-2020.png'

// Props kept for API compatibility with existing callers — logo/plan unused
type TeamSwitcherProps = {
  teams: { name: string; logo: React.ElementType; plan: string }[]
}

export function TeamSwitcher(_: TeamSwitcherProps) {
  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <div className='flex items-center gap-2.5 px-2 py-2'>
          <div className='flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-white'>
            <img
              src={ASM_LOGO}
              alt='ASM Track'
              className='h-6 w-auto object-contain'
            />
          </div>
          <div className='grid leading-tight'>
            <span className='truncate text-sm font-semibold'>ASM Track</span>
            <span className='truncate text-[10px] text-muted-foreground'>Super Admin</span>
          </div>
        </div>
      </SidebarMenuItem>
    </SidebarMenu>
  )
}
