/**
 * @package @asm/canonical-model
 * @version 1.0.0
 *
 * Canonical delivery contract shared across all mapping microservices.
 * Every mapper must produce this exact structure.
 * Downstream services consume only this contract.
 * Any change to this file must be versioned and propagated to all consumers.
 */

export interface DeliveryIdentity {
  /** Unique internal identifier */
  id: string;
  /** Reference from the source system */
  externalReference: string;
  /** Identifier of the source system that produced this delivery */
  sourceSystem: string;
  createdAt: string;
  updatedAt: string;
  schemaVersion: string;
}

export interface DeliveryPlanning {
  /** Scheduled delivery date-time in ISO 8601 format */
  scheduledAt: string;
  priority: 'NORMAL' | 'HIGH';
}

export interface Address {
  fullAddress: string;
  city: string;
  postalCode: string;
  /** ISO 3166-1 alpha-2 country code */
  countryCode: string;
}

export interface Contact {
  name: string;
  phone: string;
  email: string;
}

export interface Origin {
  /** Dispatch point name */
  name: string;
  address: Address;
  contact: Contact;
}

export interface Destination {
  /** Recipient name */
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
  /** ISO 4217 currency code */
  currency: string;
  /** 'COD' | 'PREPAID' */
  paymentType: string;
  /** Amount to collect on delivery — 0 when paymentType is PREPAID */
  amountToCollect: number;
}

export interface DeliveryMetadata {
  /** Original identifier from the source system */
  externalId: string;
  lastSyncedAt: string;
}

export interface CanonicalDelivery {
  identity:    DeliveryIdentity;
  status:      CanonicalStatus;
  planning:    DeliveryPlanning;
  origin:      Origin;
  destination: Destination;
  load:        DeliveryLoad;
  financial:   DeliveryFinancial;
  metadata:    DeliveryMetadata;
}

// ─── Status ───────────────────────────────────────────────────────────────────

export const CANONICAL_STATUS_VALUES = [
  'DRAFT',
  'READY',
  'IN_TRANSIT',
  'DELIVERED',
  'FAILED',
  'CANCELLED',
] as const;

export type CanonicalStatus = (typeof CANONICAL_STATUS_VALUES)[number];

// ─── Constants ────────────────────────────────────────────────────────────────

export const SCHEMA_VERSION = '1.0.0';
