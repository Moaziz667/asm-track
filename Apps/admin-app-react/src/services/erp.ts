import { api } from '@/lib/api'
import type { ErpPendingOrderSummaryDTO, ImportedOrderResponse } from '../types/erp'

export async function getPendingOrders(limit = 100): Promise<ErpPendingOrderSummaryDTO[]> {
  const { data } = await api.get<ErpPendingOrderSummaryDTO[]>('/admin/erp/pending-orders', {
    params: { limit },
  })
  return data
}

export async function importPendingOrder(erpOrderId: string): Promise<ImportedOrderResponse> {
  const { data } = await api.post<ImportedOrderResponse>('/admin/erp/import-order', null, { params: { erpOrderId } })
  return data
}
