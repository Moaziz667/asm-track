import { DeliveryItem } from "./index";

export type ErpPendingOrderSummaryDTO = {
  erpOrderId: string
  externalRef?: string
  customerName: string
  customerPhone?: string
  deliveryAddress?: string
  deliveryCity?: string
  totalAmount?: number
  currency?: string
  state?: string
  invoiceStatus?: string
  dateOrder?: string
  scheduledAt?: string
  alreadyImported: boolean
  existingDeliveryId?: string
  existingBackorderId?: number
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
