/**
 * SHOPIFY → CANONICAL MAPPING SERVICE
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  INPUT:  ShopifyOrder (raw from QueryShopify)                        │
 * │  OUTPUT: CanonicalDelivery  OR  null (if order is not actionable)    │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ── STATUS MAPPING ────────────────────────────────────────────────────
 *  fulfillment_status = null   → READY   (order placed, not yet shipped)
 *  fulfillment_status = partial → READY  (partially fulfilled, rest pending)
 *  fulfillment_status = fulfilled → null (handled by transport/delivery MS)
 *  cancelled_at is not null   → null     (cancelled, ignored here)
 *
 * ── ORIGIN (WAREHOUSE) ────────────────────────────────────────────────
 *  Always uses DEFAULT_WAREHOUSE_ADDRESS from env config.
 *  NEVER reads warehouse data from Shopify.
 *
 * ── DESTINATION ───────────────────────────────────────────────────────
 *  Taken from shipping_address (preferred) or billing_address fallback.
 *  phone → shipping_address.phone
 *  email → customer.email
 *
 * ── LOAD (LINE ITEMS) ─────────────────────────────────────────────────
 *  Only includes items where requires_shipping === true.
 *  Weight: grams → kg (divide by 1000)
 *  quantityDone: fulfillable_quantity (remaining to ship)
 *
 * ── FINANCIAL ──────────────────────────────────────────────────────────
 *  totalAmount → current_total_price (float)
 *  currency    → order.currency
 *  paymentType → 'COD' if payment_gateway_names contains a COD gateway
 *                else 'PREPAID'
 *  amountToCollect → totalAmount if COD, else 0
 *
 * ── PRIORITY ───────────────────────────────────────────────────────────
 *  'HIGH' if order tags contain 'vip' (case-insensitive)
 *  'NORMAL' otherwise
 *
 * ── INCREMENT (writeDate) ──────────────────────────────────────────────
 *  Uses order.updated_at for incremental sync comparison.
 *  This is returned separately to the sync service.
 *
 * @version 1.0.0
 * @source SHOPIFY
 */

import { Injectable } from '@nestjs/common';
import { ShopifyOrder, ShopifyLineItem } from '../shopify/shopify.types';
import {
  CanonicalDelivery,
  DeliveryLoad,
  LoadItem,
  SCHEMA_VERSION,
  SOURCE_SYSTEM,
} from '../canonical/canonical-delivery.model';
import { DEFAULT_WAREHOUSE_ADDRESS } from '../config/warehouse.config';

// ── COD gateway identifiers (case-insensitive contains check) ─────────────────
const COD_GATEWAY_KEYWORDS = ['cash on delivery', 'cod', 'pay on delivery'];

@Injectable()
export class MappingService {

  /**
   * Maps a raw Shopify order to a CanonicalDelivery.
   * Returns null if the order should NOT be processed by this microservice.
   */
  map(order: ShopifyOrder): CanonicalDelivery | null {
    // ── Skip cancelled orders ────────────────────────────────────────────────
    if (order.cancelled_at) return null;

    // ── Skip fully fulfilled orders (handled downstream) ────────────────────
    if (order.fulfillment_status === 'fulfilled') return null;

    const status = 'READY'; // null and 'partial' are both READY
    const now    = new Date().toISOString();

    // ── Financial ─────────────────────────────────────────────────────────────
    const totalAmount = parseFloat(order.current_total_price ?? '0');
    const currency    = order.currency ?? '';
    const paymentType = this.detectPaymentType(order.payment_gateway_names ?? []);
    const amountToCollect = paymentType === 'COD' ? totalAmount : 0;

    // ── Priority (vip tag = HIGH) ─────────────────────────────────────────────
    const priority = order.tags?.toLowerCase().includes('vip') ? 'HIGH' : 'NORMAL';

    // ── Destination address ───────────────────────────────────────────────────
    const addr = order.shipping_address;
    const fullAddress = [addr?.address1, addr?.address2]
      .filter(Boolean).join(', ');

    // ── Load (line items) ─────────────────────────────────────────────────────
    const load = this.mapLoad(order.line_items ?? []);

    return {
      identity: {
        id:                String(order.id),
        externalReference: order.name,          // e.g. "#1001"
        sourceSystem:      SOURCE_SYSTEM,        // 'SHOPIFY'
        createdAt:         order.created_at,
        updatedAt:         order.updated_at,
        schemaVersion:     SCHEMA_VERSION,
      },
      status,
      planning: {
        scheduledAt: order.processed_at ?? order.created_at ?? now,
        priority,
      },
      // ── Origin: always from warehouse config — NOT from Shopify ──────────
      origin: {
        name: DEFAULT_WAREHOUSE_ADDRESS.warehouseName,
        address: {
          fullAddress: DEFAULT_WAREHOUSE_ADDRESS.fullAddress,
          city:        DEFAULT_WAREHOUSE_ADDRESS.city,
          postalCode:  DEFAULT_WAREHOUSE_ADDRESS.postalCode,
          countryCode: DEFAULT_WAREHOUSE_ADDRESS.countryCode,
        },
        contact: { name: '', phone: '', email: '' },
      },
      destination: {
        name: addr?.name ?? `${addr?.first_name ?? ''} ${addr?.last_name ?? ''}`.trim(),
        address: {
          fullAddress,
          city:        addr?.city         ?? '',
          postalCode:  addr?.zip          ?? '',
          countryCode: addr?.country_code ?? '',
        },
        contact: {
          name:  addr?.name  ?? '',
          phone: addr?.phone ?? order.customer?.phone ?? '',
          email: order.customer?.email ?? '',
        },
        deliveryInstructions: order.note ?? '',
      },
      load,
      financial: {
        totalAmount,
        currency,
        paymentType,
        amountToCollect,
      },
      metadata: {
        externalId:   String(order.id),
        lastSyncedAt: now,
      },
    };
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /**
   * Detects payment type from Shopify gateway names.
   * Returns 'COD' if any gateway name matches COD keywords, else 'PREPAID'.
   */
  private detectPaymentType(gateways: string[]): 'COD' | 'PREPAID' {
    const lc = gateways.map(g => g.toLowerCase());
    for (const keyword of COD_GATEWAY_KEYWORDS) {
      if (lc.some(g => g.includes(keyword))) return 'COD';
    }
    return 'PREPAID';
  }

  /**
   * Maps Shopify line items to canonical DeliveryLoad.
   * Filters out non-shippable items (digital products, services).
   */
  private mapLoad(items: ShopifyLineItem[]): DeliveryLoad {
    const loadItems: LoadItem[] = items
      .filter(i => i.requires_shipping)
      .map(i => ({
        id:           String(i.id),
        sku:          i.sku ?? '',
        name:         i.title,
        quantity:     i.quantity,
        quantityDone: i.fulfillable_quantity ?? 0,
        unitWeightKg: (i.grams ?? 0) / 1000,
      }));

    const totalQuantity = loadItems.reduce((s, i) => s + i.quantity, 0);
    const totalWeightKg = loadItems.reduce((s, i) => s + i.quantity * i.unitWeightKg, 0);

    return { totalQuantity, totalWeightKg, items: loadItems };
  }
}
