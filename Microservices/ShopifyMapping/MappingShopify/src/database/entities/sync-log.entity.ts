import { Entity, PrimaryGeneratedColumn, Column, CreateDateColumn } from 'typeorm';

@Entity('sync_logs')
export class SyncLogEntity {
  @PrimaryGeneratedColumn()
  id: number;

  @CreateDateColumn({ type: 'timestamptz' })
  startedAt: Date;

  @Column({ type: 'timestamptz', nullable: true })
  completedAt: Date | null;

  @Column({ default: 'running' })
  status: string;

  @Column({ default: 0 }) fetched:  number;
  @Column({ default: 0 }) created:  number;
  @Column({ default: 0 }) updated:  number;
  @Column({ default: 0 }) skipped:  number;

  @Column({ type: 'text', nullable: true })
  error: string | null;
}
