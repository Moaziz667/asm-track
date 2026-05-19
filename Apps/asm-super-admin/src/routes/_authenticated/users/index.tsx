import { createFileRoute } from '@tanstack/react-router'
import { CompanyUsers } from '@/features/users'

export const Route = createFileRoute('/_authenticated/users/')({
  component: CompanyUsers,
})
