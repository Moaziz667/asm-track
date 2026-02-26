/**
 * CANONICAL DELIVERY MODEL — Shared Contract
 *
 * This is the SINGLE SOURCE OF TRUTH for the delivery data structure
 * shared across ALL mapping microservices (Odoo, Shopify, SAP, etc.).
 *
 * RULES:
 *  - Every mapper MUST produce this exact structure.
 *  - Never add ERP-specific fields here.
 *  - All downstream services (Transport MS, etc.) consume this contract.
 *  - When you add a new mapper, copy this file into its src/canonical/ folder.
 *  - If you change this file, update ALL mappers accordingly.
 *
 * @version 1.0.0
 */

export interface DeliveryIdentity {
  /** Internal UUID assigned at creation */
  id: string;
  /** ERP-specific reference (e.g. Odoo: WH/OUT/00001, Shopify: #1001) */
  externalReference: string;
  /** Source ERP system: 'ODOO' | 'SHOPIFY' | 'SAP' | ... */
  sourceSystem: string;
  createdAt: string;
  updatedAt: string;
  schemaVersion: string;
}

export interface DeliveryPlanning {
  /** ISO 8601 — when the delivery is scheduled */
  scheduledAt: string;
  priority: 'NORMAL' | 'HIGH';
}

export interface Address {
  fullAddress: string;
  city: string;
  postalCode: string;
  /** ISO 3166-1 alpha-2 (e.g. DZ, FR, US) */
  countryCode: string;
}

export interface Contact {
  name: string;
  phone: string;
  email: string;
}

export interface Origin {
  /** Warehouse / fulfillment center name */
  name: string;
  /** Always from DEFAULT_WAREHOUSE_ADDRESS — never from ERP */
  address: Address;
  contact: Contact;
}

export interface Destination {
  /** Recipient name */
  name: string;
  /** Delivery address from ERP */
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
  /** 'COD' | 'PREPAID' */
  paymentType: string;
  /** > 0 only when paymentType === 'COD' */
  amountToCollect: number;
}

export interface DeliveryMetadata {
  /** Numeric ID from source ERP */
  externalId: string;
  lastSyncedAt: string;
}

export interface CanonicalDelivery {
  identity:    DeliveryIdentity;
  /** 'DRAFT' | 'READY' — only these states are processed here */
  status:      'DRAFT' | 'READY' | string;
  planning:    DeliveryPlanning;
  /** Warehouse origin — injected from config, NOT from ERP */
  origin:      Origin;
  destination: Destination;
  load:        DeliveryLoad;
  financial:   DeliveryFinancial;
  metadata:    DeliveryMetadata;
}

export const SCHEMA_VERSION = '1.0.0';
export const SOURCE_SYSTEM  = 'UNKNOWN'; // overridden per mapper
