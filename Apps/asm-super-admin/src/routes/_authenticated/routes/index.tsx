import { createFileRoute } from '@tanstack/react-router'
import { Routes } from '@/features/routes'

export const Route = createFileRoute('/_authenticated/routes/')({
  component: Routes,
})
