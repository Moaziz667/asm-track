import {
  Injectable,
  NotFoundException,
  Logger,
} from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, In } from 'typeorm';
import { MappingConfig } from './mapping.entity';
import {
  CreateMappingConfigDto,
  UpdateMappingConfigDto,
  TransformRequestDto,
} from './mapping.dto';
import { TransformEngine, TransformResult } from '../transform/transform.engine';

@Injectable()
export class MappingService {
  private readonly logger = new Logger(MappingService.name);

  constructor(
    @InjectRepository(MappingConfig)
    private readonly repo: Repository<MappingConfig>,
    private readonly transformEngine: TransformEngine,
  ) {}

  // ─── CRUD ─────────────────────────────────────────────────────────────────

  async create(dto: CreateMappingConfigDto): Promise<MappingConfig> {
    const config = this.repo.create({
      name:               dto.name,
      sourceField:        dto.sourceField        ?? null,
      canonicalField:     dto.canonicalField,
      enumMapping:        dto.enumMapping        ?? null,
      transformationType: dto.transformationType ?? null,
      staticValue:        dto.staticValue        ?? null,
    });
    const saved = await this.repo.save(config);
    this.logger.log(`Created MappingConfig id=${saved.id} name="${saved.name}"`);
    return saved;
  }

  async findAll(): Promise<MappingConfig[]> {
    return this.repo.find({ order: { createdAt: 'ASC' } });
  }

  async findOne(id: string): Promise<MappingConfig> {
    const config = await this.repo.findOneBy({ id });
    if (!config) throw new NotFoundException(`MappingConfig "${id}" not found`);
    return config;
  }

  async update(id: string, dto: UpdateMappingConfigDto): Promise<MappingConfig> {
    const config = await this.findOne(id);
    Object.assign(config, dto);
    const updated = await this.repo.save(config);
    this.logger.log(`Updated MappingConfig id=${id}`);
    return updated;
  }

  async remove(id: string): Promise<void> {
    const config = await this.findOne(id);
    await this.repo.remove(config);
    this.logger.log(`Deleted MappingConfig id=${id}`);
  }

  // ─── Transform ────────────────────────────────────────────────────────────

  /**
   * Apply stored mapping rules to a raw source payload and return a
   * (partially) populated CanonicalDelivery.
   *
   * If `dto.mappingIds` is provided, only those rules are applied.
   * Otherwise ALL stored rules are applied in creation-date order.
   */
  async transform(dto: TransformRequestDto): Promise<TransformResult> {
    let rules: MappingConfig[];

    if (dto.mappingIds && dto.mappingIds.length > 0) {
      rules = await this.repo.findBy({ id: In(dto.mappingIds) });
    } else {
      rules = await this.findAll();
    }

    if (rules.length === 0) {
      this.logger.warn('Transform called but no mapping rules are configured');
    }

    return this.transformEngine.transformToCanonical(dto.sourcePayload, rules);
  }

  // ─── Field parser ─────────────────────────────────────────────────────────

  /** Extract all dot-notation leaf paths from an arbitrary JSON payload. */
  parseFieldPaths(payload: Record<string, any>): string[] {
    return this.transformEngine.extractFieldPaths(payload);
  }
}
