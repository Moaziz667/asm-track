import { api } from '@/lib/api'
import type {
  OpsAlertsResponse,
  OpsLanesResponse,
  OpsOverviewResponse,
  RouteSummary,
  SlaSummaryResponse,
} from '../types/ops'

export async function getOpsOverview(period = 'day'): Promise<OpsOverviewResponse> {
  const { data } = await api.get<OpsOverviewResponse>('/admin/ops/overview', {
    params: { period },
  })
  return data
}

export async function getOpsAlerts(limit = 30): Promise<OpsAlertsResponse> {
  const { data } = await api.get<OpsAlertsResponse>('/admin/ops/alerts', {
    params: { period: 'day', limit },
  })
  return data
}

export async function getOpsLanes(topItems = 5): Promise<OpsLanesResponse> {
  const { data } = await api.get<OpsLanesResponse>('/admin/ops/lanes', {
    params: { period: 'day', topItems },
  })
  return data
}

export async function getSlaSummary(): Promise<SlaSummaryResponse> {
  const { data } = await api.get<SlaSummaryResponse>('/admin/routes/sla-summary')
  return data
}

export async function getRoutes(): Promise<RouteSummary[]> {
  const { data } = await api.get<RouteSummary[]>('/admin/routes')
  return data
}
