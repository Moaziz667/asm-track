import {
  Controller,
  Get,
  Post,
  Put,
  Delete,
  Param,
  Body,
  HttpCode,
  HttpStatus,
  UsePipes,
  ValidationPipe,
  ParseUUIDPipe,
} from '@nestjs/common';
import { MappingService } from './mapping.service';
import { MappingConfig } from './mapping.entity';
import {
  CreateMappingConfigDto,
  UpdateMappingConfigDto,
  TransformRequestDto,
} from './mapping.dto';
import { TransformResult } from '../transform/transform.engine';

@Controller('api/mappings')
@UsePipes(new ValidationPipe({ whitelist: true, transform: true }))
export class MappingController {
  constructor(private readonly service: MappingService) {}

  // ─── CRUD ──────────────────────────────────────────────────────────────────

  /**
   * POST /api/mappings
   * Create a new mapping rule.
   */
  @Post()
  @HttpCode(HttpStatus.CREATED)
  create(@Body() dto: CreateMappingConfigDto): Promise<MappingConfig> {
    return this.service.create(dto);
  }

  /**
   * GET /api/mappings
   * List all mapping rules.
   */
  @Get()
  findAll(): Promise<MappingConfig[]> {
    return this.service.findAll();
  }

  /**
   * GET /api/mappings/:id
   * Get a single mapping rule by UUID.
   */
  @Get(':id')
  findOne(@Param('id', ParseUUIDPipe) id: string): Promise<MappingConfig> {
    return this.service.findOne(id);
  }

  /**
   * PUT /api/mappings/:id
   * Update fields on an existing mapping rule.
   */
  @Put(':id')
  update(
    @Param('id', ParseUUIDPipe) id: string,
    @Body() dto: UpdateMappingConfigDto,
  ): Promise<MappingConfig> {
    return this.service.update(id, dto);
  }

  /**
   * DELETE /api/mappings/:id
   * Permanently delete a mapping rule.
   */
  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  remove(@Param('id', ParseUUIDPipe) id: string): Promise<void> {
    return this.service.remove(id);
  }

  // ─── Utilities ────────────────────────────────────────────────────────────

  /**
   * POST /api/mappings/parse-json
   * Parse an uploaded JSON sample and return all leaf field paths.
   *
   * Body: { "payload": { ...any JSON... } }
   */
  @Post('parse-json')
  @HttpCode(HttpStatus.OK)
  parseJson(@Body() body: { payload: Record<string, any> }): { paths: string[] } {
    const paths = this.service.parseFieldPaths(body.payload ?? {});
    return { paths };
  }

  /**
   * POST /api/mappings/transform
   * Apply stored mapping rules to a source JSON payload.
   *
   * Body: TransformRequestDto
   */
  @Post('transform')
  @HttpCode(HttpStatus.OK)
  transform(@Body() dto: TransformRequestDto): Promise<TransformResult> {
    return this.service.transform(dto);
  }
}
