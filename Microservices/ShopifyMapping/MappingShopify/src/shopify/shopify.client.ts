import { Injectable, Logger } from '@nestjs/common';
import axios from 'axios';
import { ShopifyOrder, ShopifyOrdersApiResponse } from './shopify.types';

@Injectable()
export class ShopifyClient {
  private readonly logger  = new Logger(ShopifyClient.name);
  private readonly baseUrl = process.env.SHOPIFY_QUERY_URL ?? 'http://shopify-query:3200';

  async fetchOrders(since?: string): Promise<ShopifyOrder[]> {
    const url    = `${this.baseUrl}/orders`;
    const params = since ? { since } : {};

    this.logger.debug(`Fetching orders from ${url} since=${since ?? 'full'}`);

    const { data } = await axios.get<ShopifyOrdersApiResponse>(url, { params });
    return data.data ?? [];
  }
}
