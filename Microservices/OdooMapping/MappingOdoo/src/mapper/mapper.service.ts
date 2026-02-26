import { Injectable } from '@nestjs/common';
import { DeliveryRepository } from '../database/repositories/delivery.repository';
import { DeliveryEntity } from '../database/entities/delivery.entity';

@Injectable()
export class MapperService {
  constructor(private readonly deliveryRepo: DeliveryRepository) {}

  getAll(): Promise<DeliveryEntity[]> {
    return this.deliveryRepo.findAll();
  }

  getOne(id: number): Promise<DeliveryEntity | null> {
    return this.deliveryRepo.findByExternalId(String(id));
  }
}


