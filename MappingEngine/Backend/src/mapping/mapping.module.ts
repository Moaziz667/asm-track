import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { MappingConfig } from './mapping.entity';
import { MappingService } from './mapping.service';
import { MappingController } from './mapping.controller';
import { CanonicalValidatorService } from '../canonical/canonical.validator';
import { TransformEngine } from '../transform/transform.engine';

@Module({
  imports: [TypeOrmModule.forFeature([MappingConfig])],
  providers: [MappingService, CanonicalValidatorService, TransformEngine],
  controllers: [MappingController],
  exports: [MappingService, TransformEngine],
})
export class MappingModule {}
