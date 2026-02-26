import { Module } from '@nestjs/common';
import { ShopifyQueryService } from './shopify.query';
import { ShopifyController } from './shopify.controller';

@Module({
  controllers: [ShopifyController],
  providers:   [ShopifyQueryService],
})
export class ShopifyModule {}
