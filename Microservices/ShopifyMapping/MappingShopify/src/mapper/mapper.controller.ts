import { Controller, Get, Param, NotFoundException, Logger } from '@nestjs/common';
import { MapperService } from './mapper.service';

@Controller()
export class MapperController {
  private readonly logger = new Logger(MapperController.name);

  constructor(private readonly mapper: MapperService) {}

  /**
   * GET /
   * Health check + available routes.
   */
  @Get()
  health() {
    return {
      service: 'shopify-mapper',
      status:  'ok',
      routes: [
        'GET /canonical       → all deliveries mapped to canonical JSON',
        'GET /canonical/:id   → single delivery by Shopify order id',
      ],
    };
  }

  /**
   * GET /canonical
   * Returns all Shopify deliveries mapped to canonical JSON.
   */
  @Get('canonical')
  async getAll() {
    this.logger.log('GET /canonical — returning all mapped deliveries');
    const results = await this.mapper.getAll();
    const data = results.map(({ sourceWriteDate, ...canon }) => canon);
    return {
      count:    data.length,
      mappedAt: new Date().toISOString(),
      data,
    };
  }

  /**
   * GET /canonical/:id
   * Returns a single delivery by Shopify order numeric id.
   */
  @Get('canonical/:id')
  async getOne(@Param('id') id: string) {
    this.logger.log(`GET /canonical/${id}`);
    const result = await this.mapper.getOne(id);
    if (!result) throw new NotFoundException(`Delivery id=${id} not found`);
    const { sourceWriteDate, ...canon } = result;
    return canon;
  }
}
