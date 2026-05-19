import {
  LayoutDashboard,
  Building2,
  Users,
  UserPlus,
  Truck,
  Route,
  ScrollText,
  ShieldCheck,
} from 'lucide-react'
import { type SidebarData } from '../types'

export const sidebarData: SidebarData = {
  user: {
    name: 'ASM Super Admin',
    email: 'satnaingdev@gmail.com',
    avatar: '',
  },
  teams: [
    {
      name: 'ASM Track',
      logo: ShieldCheck,
      plan: 'Panneau Super Admin',
    },
  ],
  navGroups: [
    {
      title: 'Vue d\'ensemble',
      items: [
        {
          title: 'Centre de contrôle',
          url: '/',
          icon: LayoutDashboard,
        },
      ],
    },
    {
      title: 'Flotte',
      items: [
        {
          title: 'Chauffeurs',
          url: '/drivers',
          icon: Users,
        },
        {
          title: 'Véhicules',
          url: '/vehicles',
          icon: Truck,
        },
        {
          title: 'Tournées',
          url: '/routes',
          icon: Route,
        },
      ],
    },
    {
      title: 'Administration',
      items: [
        {
          title: 'Entreprises',
          url: '/companies',
          icon: Building2,
        },
        {
          title: 'Comptes Admin',
          url: '/users',
          icon: UserPlus,
        },
      ],
    },
    {
      title: 'Conformité',
      items: [
        {
          title: 'Journal d\'audit',
          url: '/audit',
          icon: ScrollText,
        },
      ],
    },
  ],
}
