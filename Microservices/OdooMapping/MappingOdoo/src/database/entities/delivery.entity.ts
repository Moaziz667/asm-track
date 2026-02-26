import {
  Entity,
  PrimaryGeneratedColumn,
  Column,
  CreateDateColumn,
  UpdateDateColumn,
  Index,
} from 'typeorm';

@Entity('deliveries')
export class DeliveryEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  /** Odoo picking name e.g. WH/OUT/00001 — unique business key */
  @Column({ unique: true })
  @Index()
  externalReference: string;

  /** Odoo picking numeric id as string */
  @Column()
  @Index()
  externalId: string;

  @Column()
  status: string;

  /** 'SHOPIFY' | 'ODOO' | ... — used to isolate reads per mapper */
  @Column({ default: 'ODOO' })
  @Index()
  sourceSystem: string;

  @Column({ type: 'jsonb' })
  identity: object;

  @Column({ type: 'jsonb' })
  planning: object;

  @Column({ type: 'jsonb' })
  origin: object;

  @Column({ type: 'jsonb' })
  destination: object;

  @Column({ type: 'jsonb' })
  load: object;

  @Column({ type: 'jsonb' })
  financial: object;

  @Column({ type: 'jsonb' })
  metadata: object;

  /** Raw writeDate from source system — used for incremental sync comparison */
  @Column({ type: 'timestamptz' })
  @Index()
  sourceWriteDate: Date;

  @CreateDateColumn({ type: 'timestamptz' })
  createdAt: Date;

  @UpdateDateColumn({ type: 'timestamptz' })
  @Index()
  updatedAt: Date;
}
