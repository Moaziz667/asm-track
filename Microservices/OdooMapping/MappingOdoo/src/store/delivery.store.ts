import { Injectable } from '@nestjs/common';
import { CanonicalDelivery } from '../canonical/canonical-delivery.model';

@Injectable()
export class DeliveryStore {
  /** Key = identity.externalReference (e.g. "WH/OUT/00001") */
  private readonly store = new Map<string, CanonicalDelivery>();

  /** Upsert a delivery. Returns true if created, false if updated. */
  upsert(delivery: CanonicalDelivery): 'created' | 'updated' {
    const key = delivery.identity.externalReference;
    const isNew = !this.store.has(key);
    this.store.set(key, delivery);
    return isNew ? 'created' : 'updated';
  }

  findAll(): CanonicalDelivery[] {
    return Array.from(this.store.values());
  }

  findById(externalId: string): CanonicalDelivery | undefined {
    return Array.from(this.store.values()).find(
      d => d.metadata.externalId === externalId,
    );
  }

  size(): number {
    return this.store.size;
  }
}
