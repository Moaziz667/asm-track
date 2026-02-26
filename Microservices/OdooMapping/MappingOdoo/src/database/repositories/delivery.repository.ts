import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, EntityManager } from 'typeorm';
import { DeliveryEntity } from '../entities/delivery.entity';
import { CanonicalDelivery } from '@asm/canonical-model';

@Injectable()
export class DeliveryRepository {
  constructor(
    @InjectRepository(DeliveryEntity)
    private readonly repo: Repository<DeliveryEntity>,
  ) {}

  /**
   * UPSERT a canonical delivery.
   * Uses externalReference as the unique conflict key.
   * Returns 'created' | 'updated' | 'skipped'.
   */
  async upsert(
    canonical: CanonicalDelivery,
    sourceWriteDate: Date,
    em?: EntityManager,
  ): Promise<'created' | 'updated' | 'skipped'> {
    const r = em
      ? em.getRepository(DeliveryEntity)
      : this.repo;

    const existing = await r.findOne({
      where: {
        externalReference: canonical.identity.externalReference,
        sourceSystem:      canonical.identity.sourceSystem,
      },
    });

    // Skip if writeDate hasn't changed
    if (existing) {
      const existingWrite = existing.sourceWriteDate?.getTime();
      const incomingWrite = sourceWriteDate?.getTime();
      if (existingWrite === incomingWrite) return 'skipped';
    }

    await r.upsert(
      {
        externalReference: canonical.identity.externalReference,
        externalId:        canonical.metadata.externalId,
        status:            canonical.status,
        sourceSystem:      canonical.identity.sourceSystem,
        identity:          canonical.identity    as any,
        planning:          canonical.planning    as any,
        origin:            canonical.origin      as any,
        destination:       canonical.destination as any,
        load:              canonical.load        as any,
        financial:         canonical.financial   as any,
        metadata:          canonical.metadata    as any,
        sourceWriteDate,
      },
      ['externalReference', 'sourceSystem'],
    );

    return existing ? 'updated' : 'created';
  }

  findAll(em?: EntityManager): Promise<DeliveryEntity[]> {
    const r = em ? em.getRepository(DeliveryEntity) : this.repo;
    return r.find({ where: { sourceSystem: 'ODOO' }, order: { updatedAt: 'DESC' } });
  }

  findByExternalId(externalId: string, em?: EntityManager): Promise<DeliveryEntity | null> {
    const r = em ? em.getRepository(DeliveryEntity) : this.repo;
    return r.findOne({ where: { externalId, sourceSystem: 'ODOO' } });
  }
}
