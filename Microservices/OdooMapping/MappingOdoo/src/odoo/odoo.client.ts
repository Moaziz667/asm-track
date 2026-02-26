import { Injectable, Logger } from '@nestjs/common';
import axios from 'axios';
import { OdooDelivery, OdooDeliveriesResponse } from './odoo.types';

@Injectable()
export class OdooClient {
  private readonly logger = new Logger(OdooClient.name);
  private readonly baseUrl = process.env.ODOO_QUERY_URL ?? 'http://odoo-query:3100';

  async fetchDeliveries(since?: string): Promise<OdooDelivery[]> {
    const url  = `${this.baseUrl}/deliveries`;
    const params = since ? { since } : {};

    this.logger.debug(`Fetching deliveries from ${url} since=${since ?? 'full'}`);

    const { data } = await axios.get<OdooDeliveriesResponse>(url, { params });
    return data.data ?? [];
  }
}
