/**
 * Raw shapes returned by the odoo-query microservice (GET /deliveries).
 */

export interface OdooCountry {
  0: number;
  1: string;
}

export interface OdooPartner {
  id: number;
  name: string;
  type?: string;
  street?: string;
  street2?: string;
  city?: string;
  zip?: string;
  country?: [number, string];
  phone?: string;
  mobile?: string;
  email?: string;
  lat?: number;
  lon?: number;
}

export interface OdooWarehouse {
  id: number;
  name: string;
  code: string;
}

export interface OdooPickingType {
  id: number;
  name: string;
  code: string;
}

export interface OdooProduct {
  id: number;
  name: string;
  code: string | false;
  barcode?: string | false;
  weight: number;
  volume?: number;
  tracking?: string;
}

export interface OdooMove {
  id: number;
  state: string;
  qtyDemand: number;
  qtyDone: number;
  priceUnit: number;
  product: OdooProduct | null;
}

export interface OdooSaleOrder {
  id: number;
  name: string;
  state: string;
  amountTotal: number;
  currency: [number, string];
}

export interface OdooDelivery {
  id: number;
  name: string;
  state: 'draft' | 'assigned' | 'done' | 'cancel' | string;
  scheduledDate: string;
  deadline: string | false;
  origin: string;
  priority: '0' | '1' | string;
  note: string | false;
  writeDate: string;
  partner: OdooPartner | null;
  warehouse: OdooWarehouse | null;
  pickingType?: OdooPickingType | null;
  saleOrder: OdooSaleOrder | null;
  moves: OdooMove[];
}

export interface OdooDeliveriesResponse {
  count: number;
  syncedAt: string;
  data: OdooDelivery[];
}
