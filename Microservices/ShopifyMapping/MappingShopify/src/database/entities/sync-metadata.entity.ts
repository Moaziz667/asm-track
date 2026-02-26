import { Entity, PrimaryColumn, Column } from 'typeorm';

@Entity('sync_metadata')
export class SyncMetadataEntity {
  @PrimaryColumn()
  key: string;

  @Column({ type: 'timestamptz', nullable: true })
  value: Date | null;
}
