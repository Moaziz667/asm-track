import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { ScheduleModule } from '@nestjs/schedule';
import { TypeOrmModule } from '@nestjs/typeorm';
import { MapperModule } from './mapper/mapper.module';
import { DeliveryEntity } from './database/entities/delivery.entity';
import { SyncMetadataEntity } from './database/entities/sync-metadata.entity';
import { SyncLogEntity } from './database/entities/sync-log.entity';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    ScheduleModule.forRoot(),
    TypeOrmModule.forRoot({
      type:        'postgres',
      host:        process.env.DB_HOST     ?? 'postgres',
      port:        parseInt(process.env.DB_PORT ?? '5432', 10),
      username:    process.env.DB_USER     ?? 'mapper',
      password:    process.env.DB_PASSWORD ?? 'mapper',
      database:    process.env.DB_NAME     ?? 'mapper',
      entities:    [DeliveryEntity, SyncMetadataEntity, SyncLogEntity],
      synchronize: true,   // auto-creates tables — use migrations in prod
      logging:     false,
    }),
    MapperModule,
  ],
})
export class AppModule {}
