import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { SchedulerRegistry } from '@nestjs/schedule';
import { DataSource } from 'typeorm';
import { OdooClient } from '../odoo/odoo.client';
import { MappingService } from '../mapping/mapping.service';
import { DeliveryRepository } from '../database/repositories/delivery.repository';
import { SyncMetadataRepository } from '../database/repositories/sync-metadata.repository';
import { SyncLogRepository } from '../database/repositories/sync-log.repository';
import { RabbitMQPublisher } from '../rabbitmq/rabbitmq.publisher';

const MAX_RETRIES    = 3;
const RETRY_DELAY_MS = 5000;

@Injectable()
export class SyncService implements OnModuleInit {
  private readonly logger = new Logger(SyncService.name);
  private isRunning = false;

  constructor(
    private readonly odooClient:        OdooClient,
    private readonly mappingService:    MappingService,
    private readonly deliveryRepo:      DeliveryRepository,
    private readonly syncMetaRepo:      SyncMetadataRepository,
    private readonly syncLogRepo:       SyncLogRepository,
    private readonly dataSource:        DataSource,
    private readonly schedulerRegistry: SchedulerRegistry,
    private readonly publisher:         RabbitMQPublisher,
  ) {}

  onModuleInit(): void {
    const ms = parseInt(process.env.SYNC_INTERVAL_MS ?? '30000', 10);

    this.runSync().catch(err =>
      this.logger.error('Initial sync failed', err?.message),
    );

    const interval = setInterval(() => {
      this.runSync().catch(err =>
        this.logger.error('Scheduled sync failed', err?.message),
      );
    }, ms);

    this.schedulerRegistry.addInterval('odoo-sync', interval);
    this.logger.log(`Sync scheduler started — interval=${ms}ms`);
  }

  // ── Main entry ─────────────────────────────────────────────────────────────

  async runSync(): Promise<void> {
    if (this.isRunning) {
      this.logger.warn('Sync already in progress — skipping this tick');
      return;
    }

    this.isRunning = true;
    const startTime = Date.now();
    const log       = await this.syncLogRepo.start();

    this.logger.log('Sync started');

    try {
      // ── Fetch with retry ───────────────────────────────────────────────────
      const lastSync  = await this.syncMetaRepo.getLastSyncTimestamp();
      const since     = lastSync?.toISOString();
      const rawRecords = await this.fetchWithRetry(since);

      this.logger.log(
        `Fetched ${rawRecords.length} records from Odoo` +
        (since ? ` (since ${since})` : ' (full sync)'),
      );

      // ── Transaction ────────────────────────────────────────────────────────
      const { stats, events } = await this.dataSource.transaction(async em => {
        let created = 0, updated = 0, skipped = 0;
        const syncAt = new Date();
        const pendingEvents: Array<{ key: 'delivery.created' | 'delivery.updated'; payload: object }> = [];

        for (const raw of rawRecords) {
          const canonical = this.mappingService.map(raw);
          if (canonical === null) { skipped++; continue; }

          const writeDate = raw.writeDate ? new Date(raw.writeDate) : syncAt;
          const result    = await this.deliveryRepo.upsert(canonical, writeDate, em);

          if (result === 'created') {
            created++;
            pendingEvents.push({ key: 'delivery.created', payload: canonical });
          } else if (result === 'updated') {
            updated++;
            pendingEvents.push({ key: 'delivery.updated', payload: canonical });
          } else {
            skipped++;
          }
        }

        // Update lastSyncTimestamp only after successful processing
        await this.syncMetaRepo.setLastSyncTimestamp(syncAt, em);

        return { stats: { fetched: rawRecords.length, created, updated, skipped }, events: pendingEvents };
      });

      // Publish AFTER transaction committed — prevents orphan events on rollback
      for (const { key, payload } of events) {
        this.publisher.publish(key, payload);
      }

      const elapsed = Date.now() - startTime;
      await this.syncLogRepo.complete(log.id, stats);

      this.logger.log(
        `Sync complete in ${elapsed}ms — ` +
        `fetched=${stats.fetched}  created=${stats.created}  ` +
        `updated=${stats.updated}  skipped=${stats.skipped}`,
      );
    } catch (err: any) {
      const msg = err?.message ?? 'unknown error';
      this.logger.error(`Sync failed after ${Date.now() - startTime}ms: ${msg}`);
      await this.syncLogRepo.fail(log.id, msg);
      // lastSyncTimestamp NOT updated — next sync will retry from same point
    } finally {
      this.isRunning = false;
    }
  }

  // ── Retry wrapper ───────────────────────────────────────────────────────────

  private async fetchWithRetry(since?: string, attempt = 1): Promise<any[]> {
    try {
      return await this.odooClient.fetchDeliveries(since);
    } catch (err: any) {
      if (attempt >= MAX_RETRIES) throw err;
      this.logger.warn(
        `Odoo fetch failed (attempt ${attempt}/${MAX_RETRIES}): ${err?.message} — retrying in ${RETRY_DELAY_MS}ms`,
      );
      await new Promise(r => setTimeout(r, RETRY_DELAY_MS));
      return this.fetchWithRetry(since, attempt + 1);
    }
  }
}

