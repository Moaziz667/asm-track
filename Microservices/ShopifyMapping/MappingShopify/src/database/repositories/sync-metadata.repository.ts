import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, EntityManager } from 'typeorm';
import { SyncMetadataEntity } from '../entities/sync-metadata.entity';

// Separate key from OdooMapper to avoid conflict in shared DB
const LAST_SYNC_KEY = 'shopify_last_sync_timestamp';

@Injectable()
export class SyncMetadataRepository {
  constructor(
    @InjectRepository(SyncMetadataEntity)
    private readonly repo: Repository<SyncMetadataEntity>,
  ) {}

  async getLastSyncTimestamp(em?: EntityManager): Promise<Date | null> {
    const r = em ? em.getRepository(SyncMetadataEntity) : this.repo;
    const row = await r.findOne({ where: { key: LAST_SYNC_KEY } });
    return row?.value ?? null;
  }

  async setLastSyncTimestamp(value: Date, em?: EntityManager): Promise<void> {
    const r = em ? em.getRepository(SyncMetadataEntity) : this.repo;
    await r.upsert({ key: LAST_SYNC_KEY, value }, ['key']);
  }
}
