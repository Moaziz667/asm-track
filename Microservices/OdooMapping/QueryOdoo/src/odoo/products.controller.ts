import { Controller, Get, Logger, Query } from '@nestjs/common';
import { OdooQueryService } from './odoo.query';

@Controller('api/products')
export class ProductsController {
  private readonly logger = new Logger(ProductsController.name);

  constructor(private readonly odoo: OdooQueryService) {}

  @Get()
  async getProducts(
    @Query('page') page?: string,
    @Query('size') size?: string,
    @Query('search') search?: string,
  ) {
    const pageNumber = Math.max(parseInt(page ?? '0', 10) || 0, 0);
    const pageSize = Math.min(Math.max(parseInt(size ?? '20', 10) || 20, 1), 100);

    this.logger.log(`GET /api/products page=${pageNumber} size=${pageSize} search=${search ?? ''}`);
    return this.odoo.queryCatalog(pageNumber, pageSize, search?.trim());
  }
}
