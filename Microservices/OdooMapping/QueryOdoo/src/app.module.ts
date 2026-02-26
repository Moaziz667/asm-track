import { Module } from '@nestjs/common';
import { OdooModule } from './odoo/odoo.module';

@Module({
  imports: [OdooModule],
})
export class AppModule {}
