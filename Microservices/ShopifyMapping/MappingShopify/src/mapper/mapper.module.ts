import { Module } from '@nestjs/common';
import { MapperController } from './mapper.controller';
import { MapperService } from './mapper.service';
import { ShopifyClient } from '../shopify/shopify.client';
import { MappingService } from '../mapping/mapping.service';
import { SyncService } from '../sync/sync.service';
import { DatabaseModule } from '../database/database.module';
import { RabbitMQModule } from '../rabbitmq/rabbitmq.module';

@Module({
  imports: [DatabaseModule, RabbitMQModule],
  controllers: [MapperController],
  providers: [
    ShopifyClient,
    MappingService,
    SyncService,
    MapperService,
  ],
})
export class MapperModule {}
