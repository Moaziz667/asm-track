import { createFileRoute } from '@tanstack/react-router'
import { Companies } from '@/features/companies'

export const Route = createFileRoute('/_authenticated/companies/')({
  component: Companies,
})
