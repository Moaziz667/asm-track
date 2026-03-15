import { Module } from '@nestjs/common';
import { OdooQueryService } from './odoo.query';
import { OdooController } from './odoo.controller';
import { ProductsController } from './products.controller';

@Module({
  controllers: [OdooController, ProductsController],
  providers:   [OdooQueryService],
  exports:     [OdooQueryService],
})
export class OdooModule {}
