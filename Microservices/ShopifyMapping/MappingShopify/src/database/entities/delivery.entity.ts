import { Entity, PrimaryGeneratedColumn, Column, CreateDateColumn, UpdateDateColumn, Index } from 'typeorm';

@Entity('deliveries')
export class DeliveryEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  /** Shopify order name e.g. #1001 — unique business key */
  @Column({ unique: true })
  @Index()
  externalReference: string;

  /** Shopify order numeric id as string */
  @Column()
  @Index()
  externalId: string;

  @Column()
  status: string;

  /** 'SHOPIFY' | 'ODOO' | ... — used to isolate reads per mapper */
  @Column({ default: 'SHOPIFY' })
  @Index()
  sourceSystem: string;

  @Column({ type: 'jsonb' }) identity:    object;
  @Column({ type: 'jsonb' }) planning:    object;
  @Column({ type: 'jsonb' }) origin:      object;
  @Column({ type: 'jsonb' }) destination: object;
  @Column({ type: 'jsonb' }) load:        object;
  @Column({ type: 'jsonb' }) financial:   object;
  @Column({ type: 'jsonb' }) metadata:    object;

  /** Shopify order updated_at — used for incremental sync comparison. */
  @Column({ type: 'timestamptz' })
  @Index()
  sourceWriteDate: Date;

  @CreateDateColumn({ type: 'timestamptz' }) createdAt: Date;
  @UpdateDateColumn({ type: 'timestamptz' }) @Index() updatedAt: Date;
}
