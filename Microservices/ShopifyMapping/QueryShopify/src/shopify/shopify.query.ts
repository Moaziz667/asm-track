/**
 * SHOPIFY QUERY LAYER
 *
 * Responsibility: ONLY fetch raw data from Shopify REST API.
 *
 * This layer:
 *   - Authenticates via API access token (X-Shopify-Access-Token)
 *   - Fetches orders from GET /admin/api/2024-01/orders.json
 *   - Performs delta sync via updated_at_min parameter
 *   - Returns RAW Shopify data — no transformation, no mapping
 *
 * This layer MUST NOT:
 *   - Contain mapping logic
 *   - Transform or reshape data
 *   - Apply any business rules
 *
 * Environment variables required:
 *   SHOPIFY_STORE_URL      e.g. https://your-store.myshopify.com
 *   SHOPIFY_ACCESS_TOKEN   private app access token
 *
 * @version 1.0.0
 */

import { Injectable, Logger } from '@nestjs/common';
import axios, { AxiosInstance } from 'axios';
import { ShopifyOrder, ShopifyOrdersResponse } from './shopify.types';

const API_VERSION = '2024-01';
const PAGE_LIMIT  = 250; // max allowed by Shopify

@Injectable()
export class ShopifyQueryService {
  private readonly logger = new Logger(ShopifyQueryService.name);
  private readonly http:   AxiosInstance;

  constructor() {
    const storeUrl    = process.env.SHOPIFY_STORE_URL    ?? '';
    const accessToken = process.env.SHOPIFY_ACCESS_TOKEN ?? '';

    this.http = axios.create({
      baseURL: `${storeUrl}/admin/api/${API_VERSION}`,
      headers: {
        'X-Shopify-Access-Token': accessToken,
        'Content-Type': 'application/json',
      },
      timeout: 30_000,
    });
  }

  /**
   * Fetch orders from Shopify.
   * @param since - ISO 8601 string — if provided, only fetch orders updated after this date
   * @returns array of raw Shopify orders
   */
  async queryOrders(since?: Date): Promise<ShopifyOrder[]> {
    const params: Record<string, string | number> = {
      limit:  PAGE_LIMIT,
      status: 'any',
    };

    if (since) {
      params['updated_at_min'] = since.toISOString();
      this.logger.log(`Delta sync — updated_at > ${since.toISOString()}`);
    } else {
      this.logger.log('Full sync — fetching all orders');
    }

    const all: ShopifyOrder[] = [];
    let pageInfo: string | undefined;

    // ── Cursor-based pagination (link header) ─────────────────────────────────
    do {
      const requestParams = pageInfo
        ? { limit: PAGE_LIMIT, page_info: pageInfo }
        : params;

      const response = await this.http.get<ShopifyOrdersResponse>('/orders.json', {
        params: requestParams,
      });

      const orders = response.data.orders ?? [];
      all.push(...orders);

      // Parse Link header for next page cursor
      const linkHeader = response.headers['link'] as string | undefined;
      pageInfo = this.extractNextPageInfo(linkHeader);

    } while (pageInfo);

    this.logger.log(`Fetched ${all.length} orders total`);
    return all;
  }

  // ── Link header parsing ────────────────────────────────────────────────────

  private extractNextPageInfo(linkHeader?: string): string | undefined {
    if (!linkHeader) return undefined;
    // Link header format: <url?page_info=xxx>; rel="next"
    const match = linkHeader.match(/<[^>]*[?&]page_info=([^&>]+)[^>]*>;\s*rel="next"/);
    return match ? match[1] : undefined;
  }
}
