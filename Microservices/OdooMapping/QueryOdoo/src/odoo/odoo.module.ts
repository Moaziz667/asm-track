import { Module } from '@nestjs/common';
import { OdooQueryService } from './odoo.query';
import { OdooController } from './odoo.controller';

@Module({
  controllers: [OdooController],
  providers:   [OdooQueryService],
  exports:     [OdooQueryService],
})
export class OdooModule {}
