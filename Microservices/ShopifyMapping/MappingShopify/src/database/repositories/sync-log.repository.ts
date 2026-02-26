import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { SyncLogEntity } from '../entities/sync-log.entity';

@Injectable()
export class SyncLogRepository {
  constructor(
    @InjectRepository(SyncLogEntity)
    private readonly repo: Repository<SyncLogEntity>,
  ) {}

  async start(): Promise<SyncLogEntity> {
    return this.repo.save(this.repo.create({ status: 'running' }));
  }

  async complete(id: number, stats: { fetched: number; created: number; updated: number; skipped: number }): Promise<void> {
    await this.repo.update(id, { ...stats, status: 'success', completedAt: new Date() });
  }

  async fail(id: number, error: string): Promise<void> {
    await this.repo.update(id, { status: 'failed', completedAt: new Date(), error });
  }
}
