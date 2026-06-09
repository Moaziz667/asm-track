export type DeliveryStatus =
  | 'DRAFT'
  | 'PENDING'
  | 'WAITING'
  | 'SCHEDULED'
  | 'IN_TRANSIT'
  | 'DELIVERED'
  | 'FAILED'
  | 'PARTIAL'
  | 'CANCELLED'
  | string

export type OpsExceptionItem = {
  deliveryId: string
  orderId?: string
  status: DeliveryStatus
  clientName: string
  city?: string
  driverName?: string
  severity?: 'DANGER' | 'WARNING' | 'INFO' | string
  message: string
  createdAt: string
}

export type SlaSnapshot = {
  waitingThresholdMinutes: number
  transitThresholdMinutes: number
  waitingBreaches: number
  transitBreaches: number
  totalBreaches: number
  /** Unified SLA (source of truth) — live counts from SlaState. */
  slaAtRisk?: number
  slaBreached?: number
}

export type OpsOverviewResponse = {
  generatedAt: string
  period: string
  periodStart: string
  periodEnd: string
  sla: SlaSnapshot
  lanes: RouteLane[]
  exceptions: OpsExceptionItem[]
}

export type OpsAlertsResponse = {
  generatedAt: string
  alerts: OpsExceptionItem[]
  sla: SlaSnapshot
}

export type RouteLaneItem = {
  deliveryId: string
  orderId?: string
  clientName: string
  city?: string
  driverName?: string
  createdAt: string
}

export type RouteLane = {
  status: DeliveryStatus
  label: string
  count: number
  items: RouteLaneItem[]
}

export type OpsLanesResponse = {
  generatedAt: string
  lanes: RouteLane[]
}

export type SlaSummaryResponse = {
  onTime: number
  late: number
  total: number
  lateStops?: { stopId: string; deliveryId: string; routeId: string; clientName?: string; etaAt?: string; slaDeadline?: string }[]
}

export type RouteSummary = {
  id: string
  name: string
  status: string
  depotId?: string
  driverId?: string
  driverName?: string
  plannedDate?: string
  totalStops?: number
  completedStops?: number
  totalDistanceKm?: number
  estimatedDurationMin?: number
  slaBreachedCount?: number
  slaLateCount?: number
}
