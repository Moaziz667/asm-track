/**
 * ODOO QUERY LAYER
 *
 * Responsibility: ONLY fetch raw data from Odoo via JSON-RPC
 *
 * This layer:
 *   - Authenticates to Odoo
 *   - Executes JSON-RPC (execute_kw) queries
 *   - Performs delta sync (write_date > last_sync_time)
 *   - Returns RAW Odoo data — no transformation, no mapping
 *
 * This layer MUST NOT:
 *   - Contain mapping logic
 *   - Transform or reshape data
 *   - Apply any business rules
 *
 * @version 2.0.0
 */

import { Injectable, Logger } from '@nestjs/common';
import axios, { AxiosInstance } from 'axios';

// ─── RPC Contracts ───────────────────────────────────────────────────────────

export interface OdooRpcRequest {
  jsonrpc: '2.0';
  method: 'call';
  id: number;
  params: {
    service: string;
    method: string;
    args: any[];
  };
}

export interface OdooRpcResponse<T = any> {
  jsonrpc: string;
  id: number;
  result?: T;
  error?: {
    code: number;
    message: string;
    data: any;
  };
}

// ─── Raw Odoo Data Types ──────────────────────────────────────────────────────

export interface RawOdooDelivery {
  id: number;
  name: string;
  state: string;
  scheduled_date: string | false;
  date_deadline: string | false;
  origin: string | false;
  priority: string;
  note: string | false;
  write_date: string;
  create_date: string;
  partner_id: [number, string] | false;
  picking_type_id: [number, string];
  company_id: [number, string];
  carrier_id?: [number, string] | false;
  weight?: number;
  weight_bulk?: number;
  volume?: number;
  location_id: [number, string];
  location_dest_id: [number, string];
  move_ids_without_package: number[];
}

export interface RawOdooPartner {
  id: number;
  name: string;
  company_type: string;
  commercial_partner_id: [number, string] | false;
  parent_id: [number, string] | false;
  type: string;
  street: string | false;
  street2: string | false;
  city: string | false;
  zip: string | false;
  country_id: [number, string] | false;
  phone: string | false;
  mobile: string | false;
  email: string | false;
  lang: string | false;
  partner_latitude: number | false;
  partner_longitude: number | false;
  vat?: string | false;
  write_date: string;
}

export interface RawOdooMove {
  id: number;
  picking_id: [number, string];
  product_id: [number, string];
  product_uom_qty: number;
  quantity_done: number;
  product_uom: [number, string];
  state: string;
  location_id: [number, string];
  location_dest_id: [number, string];
  price_unit: number;
  sale_line_id?: [number, string] | false;
  write_date: string;
}

export interface RawOdooProduct {
  id: number;
  name: string;
  default_code: string | false;
  list_price?: number;
  qty_available?: number;
  active?: boolean;
  sale_ok?: boolean;
  description_sale?: string | false;
  categ_id?: [number, string] | false;
  image_1920?: string | false;
  weight: number;
  volume: number;
  barcode: string | false;
  tracking: string;
  write_date: string;
}

export interface RawOdooPickingType {
  id: number;
  name: string;
  code: string;
  warehouse_id: [number, string];
  write_date: string;
}

export interface RawOdooWarehouse {
  id: number;
  name: string;
  code: string;
  partner_id: [number, string];
  write_date: string;
}

export interface RawOdooCarrier {
  id: number;
  name: string;
  delivery_type: string;
  write_date: string;
}

export interface RawOdooSaleOrder {
  id: number;
  name: string;
  amount_total: number;
  amount_untaxed: number;
  amount_tax: number;
  currency_id: [number, string];
  state: string;
  partner_id: [number, string];
  date_order: string;
  write_date: string;
}

export interface RawOdooSaleOrderLine {
  id: number;
  order_id: [number, string];
  product_id: [number, string];
  product_uom_qty: number;
  price_unit: number;
  price_subtotal: number;
  price_total: number;
  price_tax: number;
  name: string;
  write_date: string;
}

/** Assembled bundle of all Odoo raw records for a single delivery */
export interface RawOdooDeliveryBundle {
  picking: RawOdooDelivery;
  deliveryPartner: RawOdooPartner | null;
  commercialPartner: RawOdooPartner | null;
  moves: RawOdooMove[];
  products: Map<number, RawOdooProduct>;
  pickingType: RawOdooPickingType | null;
  warehouse: RawOdooWarehouse | null;
  warehousePartner: RawOdooPartner | null;
  carrier: RawOdooCarrier | null;
  saleOrder: RawOdooSaleOrder | null;
  saleOrderLines: Map<number, RawOdooSaleOrderLine>;
}

// ─── Service ──────────────────────────────────────────────────────────────────

@Injectable()
export class OdooQueryService {
  private readonly logger = new Logger(OdooQueryService.name);
  private readonly http: AxiosInstance;
  private readonly db: string;
  private readonly uid: number;
  private readonly password: string;
  private requestId = 0;

  constructor() {
    const url = process.env.ODOO_URL ?? '';
    this.db       = process.env.ODOO_DB       ?? '';
    this.uid      = parseInt(process.env.ODOO_UID      ?? '0', 10);
    this.password = process.env.ODOO_PASSWORD ?? '';

    this.http = axios.create({
      baseURL: url,
      headers: { 'Content-Type': 'application/json' },
      timeout: 30_000,
    });

    this.logger.log(`OdooQueryService ready — url=${url} db=${this.db} uid=${this.uid}`);
  }

  // ─── Public API ────────────────────────────────────────────────────────────

  /**
   * Fetch outgoing stock pickings from Odoo.
   * Pass `lastSyncTime` for incremental (delta) sync.
   */
  async queryPickings(lastSyncTime?: Date): Promise<RawOdooDelivery[]> {
    const domain: any[] = [
      '|',
      ['state', '=', 'assigned'],
      ['state', '=', 'cancel'],
      ['picking_type_code', '=', 'outgoing'],
    ];

    if (lastSyncTime) {
      const ts = lastSyncTime.toISOString().replace('T', ' ').slice(0, 19);
      domain.push(['write_date', '>', ts]);
      this.logger.log(`Delta sync — write_date > ${ts}`);
    } else {
      this.logger.log('Full sync — all pickings');
    }

    const fields = [
      'id', 'name', 'state', 'scheduled_date', 'date_deadline',
      'origin', 'priority', 'note', 'write_date', 'create_date',
      'partner_id', 'picking_type_id', 'company_id',
      'location_id', 'location_dest_id', 'move_ids_without_package',
    ];

    const records: RawOdooDelivery[] = await this.executeKw('stock.picking', 'search_read', [domain], { fields });
    this.logger.log(`Fetched ${records.length} pickings`);
    return records;
  }

  /**
   * Resolve all related records for a list of pickings and return a
   * fully-assembled bundle per picking (ready for external mapping).
   */
  async queryDeliveryBundles(pickings: RawOdooDelivery[]): Promise<RawOdooDeliveryBundle[]> {
    if (pickings.length === 0) return [];

    this.logger.log(`Assembling bundles for ${pickings.length} pickings…`);

    const partnerIds     = this.uniqueIds(pickings.map(p => Array.isArray(p.partner_id)      ? p.partner_id[0]      : 0));
    const pickingTypeIds = this.uniqueIds(pickings.map(p => Array.isArray(p.picking_type_id) ? p.picking_type_id[0] : 0));
    const pickingIds     = pickings.map(p => p.id);

    const [partners, moves, pickingTypes] = await Promise.all([
      this.queryPartners(partnerIds),
      this.queryMoves(pickingIds),
      this.queryPickingTypes(pickingTypeIds),
    ]);

    const warehouseIds      = this.uniqueIds(pickingTypes.map(pt => Array.isArray(pt.warehouse_id) ? pt.warehouse_id[0] : 0));
    const warehouses        = await this.queryWarehouses(warehouseIds);
    const whPartnerIds      = this.uniqueIds(warehouses.map(w => Array.isArray(w.partner_id) ? w.partner_id[0] : 0));
    const warehousePartners = await this.queryPartners(whPartnerIds);

    const productIds        = this.uniqueIds(moves.map(m => Array.isArray(m.product_id) ? m.product_id[0] : 0));
    const products          = await this.queryProducts(productIds);

    const origins           = [...new Set(pickings.map(p => p.origin).filter((o): o is string => typeof o === 'string' && o.length > 0))];
    const saleOrders        = origins.length ? await this.querySaleOrders(origins) : [];

    const saleLineIds       = this.uniqueIds(moves.map(m => Array.isArray(m.sale_line_id) ? m.sale_line_id[0] : 0));
    const saleOrderLines    = saleLineIds.length ? await this.querySaleOrderLines(saleLineIds) : [];

    // Build lookup maps
    const partnerMap       = new Map(partners.map(p  => [p.id,    p]));
    const pickingTypeMap   = new Map(pickingTypes.map(pt => [pt.id,  pt]));
    const warehouseMap     = new Map(warehouses.map(w  => [w.id,   w]));
    const whPartnerMap     = new Map(warehousePartners.map(p => [p.id, p]));
    const productMap       = new Map(products.map(p   => [p.id,   p]));
    const saleOrderMap     = new Map(saleOrders.map(so => [so.name, so]));
    const saleLineMap      = new Map(saleOrderLines.map(sol => [sol.id, sol]));

    const movesPerPicking  = new Map<number, RawOdooMove[]>();
    moves.forEach(m => {
      const pid = Array.isArray(m.picking_id) ? m.picking_id[0] : (m.picking_id as unknown as number);
      if (!movesPerPicking.has(pid)) movesPerPicking.set(pid, []);
      movesPerPicking.get(pid)!.push(m);
    });

    return pickings.map(picking => {
      const partnerId      = Array.isArray(picking.partner_id) ? picking.partner_id[0] : 0;
      const deliveryPartner = partnerMap.get(partnerId) ?? null;
      let commercialPartner: RawOdooPartner | null = null;
      if (deliveryPartner && Array.isArray(deliveryPartner.commercial_partner_id)) {
        const cid = deliveryPartner.commercial_partner_id[0];
        if (cid !== deliveryPartner.id) commercialPartner = partnerMap.get(cid) ?? null;
      }

      const ptId     = Array.isArray(picking.picking_type_id) ? picking.picking_type_id[0] : 0;
      const pt       = pickingTypeMap.get(ptId) ?? null;
      let warehouse: RawOdooWarehouse | null = null;
      let warehousePartner: RawOdooPartner | null = null;
      if (pt && Array.isArray(pt.warehouse_id)) {
        warehouse = warehouseMap.get(pt.warehouse_id[0]) ?? null;
        if (warehouse && Array.isArray(warehouse.partner_id)) {
          warehousePartner = whPartnerMap.get(warehouse.partner_id[0]) ?? null;
        }
      }

      return {
        picking,
        deliveryPartner,
        commercialPartner,
        moves:          movesPerPicking.get(picking.id) ?? [],
        products:       productMap,
        pickingType:    pt,
        warehouse,
        warehousePartner,
        carrier:        null,
        saleOrder:      picking.origin ? (saleOrderMap.get(picking.origin) ?? null) : null,
        saleOrderLines: saleLineMap,
      };
    });
  }

  async queryCatalog(page: number, size: number, search?: string) {
    const domain: any[] = [
      ['sale_ok', '=', true],
      ['active', '=', true],
    ];

    if (search && search.length > 0) {
      domain.push('|', '|',
        ['name', 'ilike', search],
        ['default_code', 'ilike', search],
        ['barcode', 'ilike', search],
      );
    }

    const totalElements = await this.executeKw('product.product', 'search_count', [domain]);
    const products: RawOdooProduct[] = await this.executeKw('product.product', 'search_read', [domain], {
      fields: [
        'id', 'name', 'default_code', 'list_price', 'qty_available', 'active',
        'sale_ok', 'description_sale', 'categ_id', 'image_1920', 'weight',
        'volume', 'barcode', 'tracking', 'write_date',
      ],
      offset: page * size,
      limit: size,
      order: 'name asc',
    });

    const totalPages = totalElements === 0 ? 0 : Math.ceil(totalElements / size);

    return {
      content: products.map(product => ({
        id: product.id,
        name: product.name,
        sku: product.default_code || null,
        price: product.list_price ?? 0,
        stock: Math.max(Math.floor(product.qty_available ?? 0), 0),
        available: Boolean(product.active && product.sale_ok && (product.qty_available ?? 0) > 0),
        imageUrl: product.image_1920 || null,
        description: product.description_sale || null,
        category: Array.isArray(product.categ_id) ? product.categ_id[1] : null,
        unitWeightKg: product.weight ?? 0,
      })),
      pageNumber: page,
      pageSize: size,
      totalElements,
      totalPages,
      last: page + 1 >= totalPages,
    };
  }

  // ─── Private Query Helpers ─────────────────────────────────────────────────

  private async queryPartners(ids: number[]): Promise<RawOdooPartner[]> {
    if (!ids.length) return [];
    return this.executeKw('res.partner', 'read', [ids], {
      fields: ['id','name','company_type','commercial_partner_id','parent_id','type',
               'street','street2','city','zip','country_id','phone','mobile','email',
               'lang','partner_latitude','partner_longitude','vat','write_date'],
    });
  }

  private async queryMoves(pickingIds: number[]): Promise<RawOdooMove[]> {
    if (!pickingIds.length) return [];
    return this.executeKw('stock.move', 'search_read', [[['picking_id', 'in', pickingIds]]], {
      fields: ['id','picking_id','product_id','product_uom_qty','quantity_done',
               'product_uom','state','location_id','location_dest_id','price_unit',
               'sale_line_id','write_date'],
    });
  }

  private async queryProducts(ids: number[]): Promise<RawOdooProduct[]> {
    if (!ids.length) return [];
    return this.executeKw('product.product', 'read', [ids], {
      fields: ['id','name','default_code','list_price','qty_available','active','sale_ok','description_sale','categ_id','image_1920','weight','volume','barcode','tracking','write_date'],
    });
  }

  private async queryPickingTypes(ids: number[]): Promise<RawOdooPickingType[]> {
    if (!ids.length) return [];
    return this.executeKw('stock.picking.type', 'read', [ids], {
      fields: ['id','name','code','warehouse_id','write_date'],
    });
  }

  private async queryWarehouses(ids: number[]): Promise<RawOdooWarehouse[]> {
    if (!ids.length) return [];
    return this.executeKw('stock.warehouse', 'read', [ids], {
      fields: ['id','name','code','partner_id','write_date'],
    });
  }

  private async querySaleOrders(origins: string[]): Promise<RawOdooSaleOrder[]> {
    if (!origins.length) return [];
    return this.executeKw('sale.order', 'search_read', [[['name', 'in', origins]]], {
      fields: ['id','name','amount_total','amount_untaxed','amount_tax',
               'currency_id','state','partner_id','date_order','write_date'],
    });
  }

  private async querySaleOrderLines(ids: number[]): Promise<RawOdooSaleOrderLine[]> {
    if (!ids.length) return [];
    return this.executeKw('sale.order.line', 'read', [ids], {
      fields: ['id','order_id','product_id','product_uom_qty','price_unit',
               'price_subtotal','price_total','price_tax','name','write_date'],
    });
  }

  // ─── Core RPC ──────────────────────────────────────────────────────────────

  private async executeKw(model: string, method: string, args: any[], kwargs: any = {}): Promise<any> {
    const payload: OdooRpcRequest = {
      jsonrpc: '2.0',
      method: 'call',
      id: ++this.requestId,
      params: {
        service: 'object',
        method: 'execute_kw',
        args: [this.db, this.uid, this.password, model, method, args, kwargs],
      },
    };

    try {
      const { data } = await this.http.post<OdooRpcResponse>('', payload);
      if (data.error) {
        this.logger.error(`Odoo RPC error: ${JSON.stringify(data.error)}`);
        throw new Error(`Odoo RPC error: ${data.error.message}`);
      }
      return data.result;
    } catch (err: any) {
      this.logger.error(`HTTP error calling Odoo: ${err.message}`);
      throw err;
    }
  }

  private uniqueIds(ids: number[]): number[] {
    return [...new Set(ids.filter(id => id > 0))];
  }
}
