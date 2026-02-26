import { Controller, Get, Query, Logger } from '@nestjs/common';
import { OdooQueryService } from './odoo.query';

@Controller('deliveries')
export class OdooController {
  private readonly logger = new Logger(OdooController.name);

  constructor(private readonly odoo: OdooQueryService) {}

  /**
   * GET /deliveries
   * Returns raw delivery bundles from Odoo.
   * Optional query param: ?since=2026-01-01T00:00:00Z for delta sync
   */
  @Get()
  async getDeliveries(@Query('since') since?: string) {
    this.logger.log(`GET /deliveries  since=${since ?? 'full sync'}`);

    const lastSync = since ? new Date(since) : undefined;

    const pickings = await this.odoo.queryPickings(lastSync);
    const bundles  = await this.odoo.queryDeliveryBundles(pickings);

    return {
      count: bundles.length,
      syncedAt: new Date().toISOString(),
      data: bundles.map(b => ({
        id:             b.picking.id,
        name:           b.picking.name,
        state:          b.picking.state,
        scheduledDate:  b.picking.scheduled_date,
        deadline:       b.picking.date_deadline,
        origin:         b.picking.origin,
        priority:       b.picking.priority,
        note:           b.picking.note,
        writeDate:      b.picking.write_date,
        partner:        b.deliveryPartner
          ? {
              id:        b.deliveryPartner.id,
              name:      b.deliveryPartner.name,
              type:      b.deliveryPartner.type,
              street:    b.deliveryPartner.street,
              street2:   b.deliveryPartner.street2,
              city:      b.deliveryPartner.city,
              zip:       b.deliveryPartner.zip,
              country:   b.deliveryPartner.country_id,
              phone:     b.deliveryPartner.phone,
              mobile:    b.deliveryPartner.mobile,
              email:     b.deliveryPartner.email,
              lat:       b.deliveryPartner.partner_latitude,
              lon:       b.deliveryPartner.partner_longitude,
            }
          : null,
        commercialPartner: b.commercialPartner
          ? { id: b.commercialPartner.id, name: b.commercialPartner.name }
          : null,
        warehouse:  b.warehouse
          ? { id: b.warehouse.id, name: b.warehouse.name, code: b.warehouse.code }
          : null,
        pickingType: b.pickingType
          ? { id: b.pickingType.id, name: b.pickingType.name, code: b.pickingType.code }
          : null,
        saleOrder:  b.saleOrder
          ? {
              id:          b.saleOrder.id,
              name:        b.saleOrder.name,
              state:       b.saleOrder.state,
              amountTotal: b.saleOrder.amount_total,
              currency:    b.saleOrder.currency_id,
            }
          : null,
        moves: b.moves.map(m => {
          const product = b.products.get(
            Array.isArray(m.product_id) ? m.product_id[0] : 0
          );
          return {
            id:           m.id,
            state:        m.state,
            qtyDemand:    m.product_uom_qty,
            qtyDone:      m.quantity_done,
            priceUnit:    m.price_unit,
            product: product
              ? {
                  id:          product.id,
                  name:        product.name,
                  code:        product.default_code,
                  barcode:     product.barcode,
                  weight:      product.weight,
                  volume:      product.volume,
                  tracking:    product.tracking,
                }
              : null,
          };
        }),
      })),
    };
  }

  /**
   * GET /deliveries/raw
   * Returns unparsed stock.picking records (no bundle assembly).
   */
  @Get('raw')
  async getRawPickings(@Query('since') since?: string) {
    this.logger.log(`GET /deliveries/raw  since=${since ?? 'full sync'}`);
    const lastSync = since ? new Date(since) : undefined;
    const pickings = await this.odoo.queryPickings(lastSync);
    return { count: pickings.length, data: pickings };
  }
}
