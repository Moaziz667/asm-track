import { createFileRoute } from '@tanstack/react-router'
import { Vehicles } from '@/features/vehicles'

export const Route = createFileRoute('/_authenticated/vehicles/')({
  component: Vehicles,
})
