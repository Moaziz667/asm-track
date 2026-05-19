import { createFileRoute } from '@tanstack/react-router'
import { Drivers } from '@/features/drivers'

export const Route = createFileRoute('/_authenticated/drivers/')({
  component: Drivers,
})
