import { Injectable } from '@nestjs/common';
import { OdooDelivery, OdooMove } from '../odoo/odoo.types';
import {
  CanonicalDelivery,
  CanonicalStatus,
  DeliveryLoad,
  LoadItem,
  SCHEMA_VERSION,
} from '@asm/canonical-model';
import { DEFAULT_WAREHOUSE_ADDRESS } from '../config/warehouse.config';

const SOURCE_SYSTEM = 'ODOO';

// ─── Status mapping ───────────────────────────────────────────────────────────

const STATUS_MAP: Record<string, CanonicalStatus> = {
  draft:    'DRAFT',
  assigned: 'READY',
};

/** Returns null for states that are handled by another microservice. */
function mapStatus(state: string): CanonicalStatus | null {
  return STATUS_MAP[state] ?? null;
}

// ─── Priority mapping ─────────────────────────────────────────────────────────

function mapPriority(priority: string): 'NORMAL' | 'HIGH' {
  return priority === '1' ? 'HIGH' : 'NORMAL';
}

// ─── Date helper ──────────────────────────────────────────────────────────────

function toISO(value: string | false | null | undefined): string {
  if (!value) return '';
  try {
    return new Date(value).toISOString();
  } catch {
    return String(value);
  }
}

// ─── Country name → ISO 3166-1 alpha-2 ───────────────────────────────────────

const COUNTRY_ISO: Record<string, string> = {
  'Algeria':              'DZ',
  'France':               'FR',
  'United States':        'US',
  'Morocco':              'MA',
  'Tunisia':              'TN',
  'Egypt':                'EG',
  'Saudi Arabia':         'SA',
  'United Arab Emirates': 'AE',
  'Germany':              'DE',
  'Spain':                'ES',
  'Italy':                'IT',
  'United Kingdom':       'GB',
  'Belgium':              'BE',
  'Netherlands':          'NL',
  'Switzerland':          'CH',
  'Canada':               'CA',
  'Australia':            'AU',
  'Japan':                'JP',
  'China':                'CN',
  'India':                'IN',
  'Brazil':               'BR',
  'Senegal':              'SN',
  'Mali':                 'ML',
  'Ivory Coast':          'CI',
};

function toISOCountry(countryName: string): string {
  return COUNTRY_ISO[countryName] ?? countryName.slice(0, 2).toUpperCase();
}

// ─── Load mapping ─────────────────────────────────────────────────────────────

function mapLoad(moves: OdooMove[]): DeliveryLoad {
  const items: LoadItem[] = moves
    .filter(m => m.product !== null)
    .map(m => ({
      id:           String(m.id),
      sku:          m.product?.code ? String(m.product.code) : '',
      name:         m.product?.name ?? '',
      quantity:     m.qtyDemand,
      quantityDone: m.qtyDone,
      unitWeightKg: m.product?.weight ?? 0,
    }));

  const totalQuantity  = items.reduce((sum, i) => sum + i.quantity, 0);
  const totalWeightKg  = items.reduce((sum, i) => sum + i.quantity * i.unitWeightKg, 0);

  return { totalQuantity, totalWeightKg, items };
}

// ─── Service ──────────────────────────────────────────────────────────────────

@Injectable()
export class MappingService {
  /**
   * Maps a raw Odoo delivery to a CanonicalDelivery.
   * Returns null if the delivery state is not handled by this microservice.
   */
  map(odoo: OdooDelivery): CanonicalDelivery | null {
    const status = mapStatus(odoo.state);
    if (status === null) return null;

    const now = new Date().toISOString();

    // ── Financial ─────────────────────────────────────────────────────────────
    const totalAmount = odoo.saleOrder?.amountTotal ?? 0;
    const currency    = odoo.saleOrder?.currency?.[1] ?? '';
    const paymentType = 'COD';
    const amountToCollect = paymentType === 'COD' ? totalAmount : 0;

    // ── Load ──────────────────────────────────────────────────────────────────
    const load = mapLoad(odoo.moves ?? []);

    // ── Partner (destination) ─────────────────────────────────────────────────
    const partner = odoo.partner;
    const countryName = partner?.country?.[1] ?? '';

    return {
      identity: {
        id:                 String(odoo.id),
        externalReference:  odoo.name,
        sourceSystem:       SOURCE_SYSTEM,
        createdAt:          toISO(odoo.writeDate),
        updatedAt:          toISO(odoo.writeDate),
        schemaVersion:      SCHEMA_VERSION,
      },
      status,
      planning: {
        scheduledAt: toISO(odoo.scheduledDate),
        priority:    mapPriority(odoo.priority),
      },
      origin: {
        name: DEFAULT_WAREHOUSE_ADDRESS.warehouseName,
        address: {
          fullAddress: DEFAULT_WAREHOUSE_ADDRESS.fullAddress,
          city:        DEFAULT_WAREHOUSE_ADDRESS.city,
          postalCode:  DEFAULT_WAREHOUSE_ADDRESS.postalCode,
          countryCode: DEFAULT_WAREHOUSE_ADDRESS.countryCode,
        },
        contact: {
          name:  '',
          phone: '',
          email: '',
        },
      },
      destination: {
        name: partner?.name ?? '',
        address: {
          fullAddress: partner?.street ?? '',
          city:        partner?.city ?? '',
          postalCode:  partner?.zip ?? '',
          countryCode: countryName ? toISOCountry(countryName) : '',
        },
        contact: {
          name:  partner?.name ?? '',
          phone: partner?.phone ?? '',
          email: partner?.email ?? '',
        },
        deliveryInstructions: odoo.note ? String(odoo.note) : '',
      },
      load,
      financial: {
        totalAmount,
        currency,
        paymentType,
        amountToCollect,
      },
      metadata: {
        externalId:   String(odoo.id),
        lastSyncedAt: now,
      },
    };
  }
}
