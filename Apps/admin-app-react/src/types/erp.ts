import { DeliveryItem } from "./index";

export type ErpPendingOrderSummaryDTO = {
  erpOrderId: string
  customerRef?: string
  /** NORMAL | HIGH — flagged in the list so an urgent order is visible before import. */
  priority?: string
  customerName: string
  customerPhone?: string
  deliveryAddress?: string
  deliveryCity?: string
  totalAmount?: number
  currency?: string
  dateOrder?: string
  scheduledAt?: string
  alreadyImported: boolean
  existingDeliveryId?: string
  blNumber?: string
  saleOrderRef?: string
  warehouseCode?: string
  warehouseName?: string
  ready?: boolean
  /** True when this picking is a backorder (reliquat of a prior partial delivery). */
  backorder?: boolean
  /** BL number of the origin picking this is a backorder of. */
  originBl?: string
}

export interface ErpPendingOrderPreviewDTO extends ErpPendingOrderSummaryDTO {
  items: DeliveryItem[];
  totalQuantity: number;
  totalWeightKg: number;
  priority: string;
}

export interface ErpClientDTO {
  erpClientId: string;
  name: string;
  phone?: string;
  email?: string;
  address?: string;
}

export interface ErpProductDTO {
  erpProductId: string;
  name: string;
  sku?: string;
  price?: number;
  weightKg?: number;
  stock?: number;
}

export type ImportedOrderResponse = {
  id: string
  erpOrderId: string
  deliveryId?: string
  status?: string
  deliveryStatus?: string
}
