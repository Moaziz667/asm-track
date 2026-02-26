import { Injectable, BadRequestException } from '@nestjs/common';
import {
  CanonicalDelivery,
  CANONICAL_STATUS_VALUES,
} from './canonical-delivery.model';

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

/**
 * CanonicalValidatorService
 *
 * Validates that a candidate CanonicalDelivery object satisfies all
 * platform business rules before it can be accepted downstream.
 */
@Injectable()
export class CanonicalValidatorService {
  // ─── Public API ─────────────────────────────────────────────────────────────

  /**
   * Validate and return a result object (does NOT throw).
   */
  validate(delivery: Partial<CanonicalDelivery>): ValidationResult {
    const errors: string[] = [];

    this.checkRequiredFields(delivery, errors);
    this.checkStatusEnum(delivery, errors);

    return { valid: errors.length === 0, errors };
  }

  /**
   * Validate and throw `BadRequestException` on failure.
   */
  validateOrThrow(delivery: Partial<CanonicalDelivery>): void {
    const result = this.validate(delivery);
    if (!result.valid) {
      throw new BadRequestException({
        message: 'Canonical validation failed',
        errors: result.errors,
      });
    }
  }

  // ─── Checks ──────────────────────────────────────────────────────────────────

  private checkRequiredFields(delivery: Partial<CanonicalDelivery>, errors: string[]): void {
    // Top-level required
    const required: Array<keyof CanonicalDelivery> = [
      'identity', 'status', 'planning', 'origin', 'destination', 'load', 'financial', 'metadata',
    ];

    for (const field of required) {
      if (delivery[field] === undefined || delivery[field] === null) {
        errors.push(`Missing required field: ${field}`);
      }
    }

    // Identity sub-fields
    if (delivery.identity) {
      for (const f of ['externalReference', 'sourceSystem'] as const) {
        if (!delivery.identity[f]) {
          errors.push(`Missing required field: identity.${f}`);
        }
      }
    }

    // Destination contact
    if (delivery.destination?.contact) {
      if (!delivery.destination.contact.name) {
        errors.push('Missing required field: destination.contact.name');
      }
    } else if (delivery.destination !== undefined) {
      errors.push('Missing required field: destination.contact');
    }
  }

  private checkStatusEnum(delivery: Partial<CanonicalDelivery>, errors: string[]): void {
    if (!delivery.status) return; // already caught by required-fields check

    const allowed = CANONICAL_STATUS_VALUES as readonly string[];
    if (!allowed.includes(delivery.status)) {
      errors.push(
        `Invalid status "${delivery.status}". Allowed values: ${allowed.join(', ')}`,
      );
    }
  }
}
