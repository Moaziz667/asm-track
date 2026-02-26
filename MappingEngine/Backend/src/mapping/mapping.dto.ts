import {
  IsString,
  IsOptional,
  IsObject,
  MaxLength,
  IsIn,
} from 'class-validator';

const TRANSFORMATION_TYPES = ['TO_STRING', 'TO_NUMBER', 'UPPERCASE', 'LOWERCASE', 'TRIM', 'DATE_ISO'] as const;

// ─── Create ───────────────────────────────────────────────────────────────────

export class CreateMappingConfigDto {
  @IsString()
  @MaxLength(120)
  name: string;

  @IsOptional()
  @IsString()
  @MaxLength(255)
  sourceField?: string;

  @IsString()
  @MaxLength(255)
  canonicalField: string;

  @IsOptional()
  @IsObject()
  enumMapping?: Record<string, string>;

  @IsOptional()
  @IsString()
  @IsIn(TRANSFORMATION_TYPES)
  transformationType?: string;

  @IsOptional()
  @IsString()
  @MaxLength(500)
  staticValue?: string;
}

// ─── Update (all fields optional) ────────────────────────────────────────────

export class UpdateMappingConfigDto {
  @IsOptional()
  @IsString()
  @MaxLength(120)
  name?: string;

  @IsOptional()
  @IsString()
  @MaxLength(255)
  sourceField?: string;

  @IsOptional()
  @IsString()
  @MaxLength(255)
  canonicalField?: string;

  @IsOptional()
  @IsObject()
  enumMapping?: Record<string, string> | null;

  @IsOptional()
  @IsString()
  @IsIn(TRANSFORMATION_TYPES)
  transformationType?: string | null;

  @IsOptional()
  @IsString()
  @MaxLength(500)
  staticValue?: string | null;
}

// ─── Transform Request ────────────────────────────────────────────────────────

export class TransformRequestDto {
  /** Raw source JSON payload to transform */
  sourcePayload: Record<string, any>;

  /**
   * Optional list of specific MappingConfig IDs to apply.
   * If omitted, ALL stored configs are applied.
   */
  @IsOptional()
  mappingIds?: string[];
}
