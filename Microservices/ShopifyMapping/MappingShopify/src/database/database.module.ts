import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { DeliveryEntity } from './entities/delivery.entity';
import { SyncMetadataEntity } from './entities/sync-metadata.entity';
import { SyncLogEntity } from './entities/sync-log.entity';
import { DeliveryRepository } from './repositories/delivery.repository';
import { SyncMetadataRepository } from './repositories/sync-metadata.repository';
import { SyncLogRepository } from './repositories/sync-log.repository';

@Module({
  imports: [
    TypeOrmModule.forFeature([DeliveryEntity, SyncMetadataEntity, SyncLogEntity]),
  ],
  providers: [DeliveryRepository, SyncMetadataRepository, SyncLogRepository],
  exports:   [DeliveryRepository, SyncMetadataRepository, SyncLogRepository],
})
export class DatabaseModule {}
