import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  UpdateDateColumn,
} from 'typeorm';

/**
 * MappingConfig
 *
 * Persists a single field-level mapping rule:
 *   sourceField  →  canonicalField
 *
 * Optionally stores:
 *  - enumMapping:        raw value → canonical enum value  (e.g. "assigned" → "READY")
 *  - transformationType: post-extraction value transform    (e.g. "UPPERCASE", "TO_NUMBER")
 */
@Entity('mapping_configs')
export class MappingConfig {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  /**
   * Human-readable name for this mapping rule set.
   * Example: "Odoo Delivery v1"
   */
  @Column({ length: 120 })
  name: string;

  /**
   * Dot-notation path into the SOURCE payload.
   * Null when staticValue is used instead.
   * Example: "order.delivery.ref"
   */
  @Column({ length: 255, nullable: true })
  sourceField: string | null;

  /**
   * Dot-notation path in the CANONICAL model.
   * Example: "identity.externalReference"
   */
  @Column({ length: 255 })
  canonicalField: string;

  /**
   * Optional enum translation table (JSON object).
   * Example: { "assigned": "READY", "done": "DELIVERED", "cancel": "CANCELLED" }
   */
  @Column({ type: 'simple-json', nullable: true })
  enumMapping: Record<string, string> | null;

  /**
   * Optional post-extraction transformation.
   * Supported values: TO_STRING | TO_NUMBER | UPPERCASE | LOWERCASE | TRIM | DATE_ISO
   */
  @Column({ length: 50, nullable: true })
  transformationType: string | null;

  /**
   * Optional hard-coded static value.
   * When set, sourceField is ignored and this value is always injected.
   * Example: "EXPRESS", "DZD", "1.0.0"
   */
  @Column({ length: 500, nullable: true })
  staticValue: string | null;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
