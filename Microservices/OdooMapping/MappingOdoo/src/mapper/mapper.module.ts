import { Module } from '@nestjs/common';
import { MapperController } from './mapper.controller';
import { MapperService } from './mapper.service';
import { OdooClient } from '../odoo/odoo.client';
import { MappingService } from '../mapping/mapping.service';
import { SyncService } from '../sync/sync.service';
import { DatabaseModule } from '../database/database.module';
import { RabbitMQModule } from '../rabbitmq/rabbitmq.module';

@Module({
  imports: [DatabaseModule, RabbitMQModule],
  controllers: [MapperController],
  providers: [
    OdooClient,
    MappingService,
    SyncService,
    MapperService,
  ],
})
export class MapperModule {}
