/**
 * CANONICAL DELIVERY MODEL
 *
 * The single agreed-upon shape of a delivery record inside this platform.
 * All source systems (Odoo, WMS, client CSV, …) must map their data to this
 * structure before it can be processed downstream.
 *
 * @version 1.0.0
 */

// ─── Sub-types ────────────────────────────────────────────────────────────────

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
  priority: string;
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

// ─── Root ─────────────────────────────────────────────────────────────────────

export interface CanonicalDelivery {
  identity: DeliveryIdentity;
  /** Allowed values: DRAFT | READY | IN_TRANSIT | DELIVERED | FAILED | CANCELLED */
  status: string;
  planning: DeliveryPlanning;
  origin: Origin;
  destination: Destination;
  load: DeliveryLoad;
  financial: DeliveryFinancial;
  metadata: DeliveryMetadata;
}

export const CANONICAL_MODEL_VERSION = '1.0.0';

/** All valid canonical status values */
export const CANONICAL_STATUS_VALUES = [
  'DRAFT',
  'READY',
  'IN_TRANSIT',
  'DELIVERED',
  'FAILED',
  'CANCELLED',
] as const;

export type CanonicalStatus = (typeof CANONICAL_STATUS_VALUES)[number];
