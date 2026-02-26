import { Controller, Get, Post, Query, Body, Logger } from '@nestjs/common';
import { ShopifyQueryService } from './shopify.query';
import { ShopifyOrder } from './shopify.types';

@Controller('orders')
export class ShopifyController {
  private readonly logger = new Logger(ShopifyController.name);
  /** In-memory store — used when SHOPIFY_STORE_URL is not configured (test mode) */
  private readonly testOrders: ShopifyOrder[] = [];

  constructor(private readonly shopify: ShopifyQueryService) {}

  /**
   * GET /orders
   * - Test mode  (no SHOPIFY_STORE_URL): returns in-memory test orders.
   * - Production (SHOPIFY_STORE_URL set): fetches from real Shopify API.
   */
  @Get()
  async getOrders(@Query('since') since?: string) {
    const isTestMode = !process.env.SHOPIFY_STORE_URL?.trim();

    if (isTestMode) {
      this.logger.log(`GET /orders [TEST MODE] — returning ${this.testOrders.length} stored order(s)`);
      return {
        count:    this.testOrders.length,
        syncedAt: new Date().toISOString(),
        data:     this.testOrders,
      };
    }

    this.logger.log(`GET /orders  since=${since ?? 'full sync'}`);
    const sinceDate = since ? new Date(since) : undefined;
    const orders    = await this.shopify.queryOrders(sinceDate);
    return {
      count:    orders.length,
      syncedAt: new Date().toISOString(),
      data:     orders,
    };
  }

  /**
   * POST /orders/test
   * Accepts a raw Shopify order JSON body, stores it in memory, and returns
   * it in the standard envelope — so MappingShopify's sync picks it up on
   * the next poll of GET /orders.
   *
   * Replaces an existing order with the same id (upsert by id).
   */
  @Post('test')
  testOrder(@Body() order: ShopifyOrder) {
    this.logger.log(`POST /orders/test  id=${order?.id ?? 'unknown'}`);
    const idx = this.testOrders.findIndex(o => o.id === order.id);
    if (idx >= 0) {
      this.testOrders[idx] = order;
    } else {
      this.testOrders.push(order);
    }
    return {
      count:    1,
      syncedAt: new Date().toISOString(),
      data:     [order],
    };
  }
}
