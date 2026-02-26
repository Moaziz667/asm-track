export interface DeliveryIdentity {
  id: string;
  externalReference: string;
  sourceSystem: string;
  createdAt: string;
  updatedAt: string;
  schemaVersion: string;
}

export interface DeliveryPlanning {
  scheduledAt: string;
  priority: 'NORMAL' | 'HIGH';
}

export interface Address {
  fullAddress: string;
  city: string;
  postalCode: string;
  countryCode: string;
}

export interface Contact {
  name: string;
  phone: string;
  email: string;
}

export interface Origin {
  name: string;
  address: Address;
  contact: Contact;
}

export interface Destination {
  name: string;
  address: Address;
  contact: Contact;
  deliveryInstructions: string;
}

export interface LoadItem {
  id: string;
  sku: string;
  name: string;
  quantity: number;
  quantityDone: number;
  unitWeightKg: number;
}

export interface DeliveryLoad {
  totalQuantity: number;
  totalWeightKg: number;
  items: LoadItem[];
}

export interface DeliveryFinancial {
  totalAmount: number;
  currency: string;
  paymentType: string;
  amountToCollect: number;
}

export interface DeliveryMetadata {
  externalId: string;
  lastSyncedAt: string;
}

export interface CanonicalDelivery {
  identity:    DeliveryIdentity;
  status:      'DRAFT' | 'READY' | string;
  planning:    DeliveryPlanning;
  origin:      Origin;
  destination: Destination;
  load:        DeliveryLoad;
  financial:   DeliveryFinancial;
  metadata:    DeliveryMetadata;
}

export const SCHEMA_VERSION = '1.0.0';
export const SOURCE_SYSTEM  = 'ODOO';
