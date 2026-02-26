import { Controller, Get } from '@nestjs/common';
import { Module } from '@nestjs/common';
import { ShopifyModule } from './shopify/shopify.module';

@Controller()
class HealthController {
  @Get()
  health() { return { status: 'ok', service: 'query-shopify' }; }
}

@Module({
  imports: [ShopifyModule],
  controllers: [HealthController],
})
export class AppModule {}
