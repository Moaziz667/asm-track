import { Injectable, Logger } from '@nestjs/common';
import { MappingConfig } from '../mapping/mapping.entity';
import {
  CanonicalDelivery,
  CANONICAL_MODEL_VERSION,
} from '../canonical/canonical-delivery.model';
import { CanonicalValidatorService, ValidationResult } from '../canonical/canonical.validator';

// ─── Result types ─────────────────────────────────────────────────────────────

export interface TransformResult {
  success: boolean;
  canonical: Partial<CanonicalDelivery>;
  validation: ValidationResult;
  appliedRules: number;
  warnings: string[];
}

// ─── Engine ───────────────────────────────────────────────────────────────────

/**
 * TransformEngine
 *
 * Core mapping runtime.  Given a raw source JSON payload and an ordered
 * list of MappingConfig rules, it:
 *
 *  1. Extracts each source field value using dot-notation.
 *  2. Applies optional enum translation (e.g. "assigned" → "READY").
 *  3. Applies optional value transformation (e.g. UPPERCASE, TO_NUMBER).
 *  4. Writes the result into the canonical object at the target dot-path.
 *  5. Stamps schema metadata.
 *  6. Validates the assembled canonical object.
 */
@Injectable()
export class TransformEngine {
  private readonly logger = new Logger(TransformEngine.name);

  constructor(private readonly validator: CanonicalValidatorService) {}

  // ─── Public ─────────────────────────────────────────────────────────────────

  transformToCanonical(
    sourcePayload: Record<string, any>,
    rules: MappingConfig[],
  ): TransformResult {
    const canonical: Record<string, any> = {};
    const warnings: string[] = [];
    let appliedRules = 0;

    for (const rule of rules) {
      let value: any;

      // ── 0. Static value — skip extraction entirely ──
      if (rule.staticValue !== undefined && rule.staticValue !== null && rule.staticValue !== '') {
        value = rule.staticValue;
      } else {
        if (!rule.sourceField) {
          warnings.push(`Rule "${rule.name}": no sourceField and no staticValue defined — skipped`);
          continue;
        }
        const rawValue = this.getByDotPath(sourcePayload, rule.sourceField);
        if (rawValue === undefined || rawValue === null) {
          warnings.push(`Rule "${rule.name}": source field "${rule.sourceField}" not found in payload`);
          continue;
        }
        value = rawValue;
      }

      // ── 1. Enum mapping ──
      if (rule.enumMapping && typeof value === 'string') {
        const mapped = rule.enumMapping[value];
        if (mapped !== undefined) {
          value = mapped;
        } else {
          warnings.push(
            `Rule "${rule.name}": no enum mapping defined for value "${value}" — using raw value`,
          );
        }
      }

      // ── 2. Transformation ──
      if (rule.transformationType) {
        value = this.applyTransformation(value, rule.transformationType, rule.name, warnings);
      }

      // ── 3. Write to canonical path ──
      this.setByDotPath(canonical, rule.canonicalField, value);
      appliedRules++;
    }

    // ── 4. Stamp schema metadata ──
    this.setByDotPath(canonical, 'identity.schemaVersion', CANONICAL_MODEL_VERSION);
    if (!this.getByDotPath(canonical, 'metadata.lastSyncedAt')) {
      this.setByDotPath(canonical, 'metadata.lastSyncedAt', new Date().toISOString());
    }

    // ── 5. Validate ──
    const validation = this.validator.validate(canonical as Partial<CanonicalDelivery>);

    this.logger.log(
      `Transform complete — rules applied: ${appliedRules}/${rules.length}, valid: ${validation.valid}`,
    );

    return {
      success: validation.valid,
      canonical: canonical as Partial<CanonicalDelivery>,
      validation,
      appliedRules,
      warnings,
    };
  }

  /**
   * Parse a JSON payload and extract every leaf-level dot-notation path.
   * Used by the admin UI to build the source field tree.
   */
  extractFieldPaths(payload: Record<string, any>): string[] {
    const paths: string[] = [];
    this.walkObject(payload, '', paths);
    return paths.sort();
  }

  // ─── Private helpers ────────────────────────────────────────────────────────

  private getByDotPath(obj: Record<string, any>, path: string): any {
    if (!path) return undefined;
    return path.split('.').reduce((cur, key) => {
      if (cur === null || cur === undefined) return undefined;
      // Support array index notation: items[0] → items.0
      const arrayMatch = key.match(/^(\w+)\[(\d+)\]$/);
      if (arrayMatch) {
        return cur[arrayMatch[1]]?.[parseInt(arrayMatch[2], 10)];
      }
      return cur[key];
    }, obj as any);
  }

  private setByDotPath(obj: Record<string, any>, path: string, value: any): void {
    if (!path) return;
    const keys = path.split('.');
    const lastKey = keys.pop()!;
    const target = keys.reduce((cur, key) => {
      if (cur[key] === undefined || cur[key] === null || typeof cur[key] !== 'object') {
        cur[key] = {};
      }
      return cur[key];
    }, obj);
    target[lastKey] = value;
  }

  private applyTransformation(
    value: any,
    type: string,
    ruleName: string,
    warnings: string[],
  ): any {
    try {
      switch (type) {
        case 'TO_STRING':  return String(value);
        case 'TO_NUMBER':  return Number(value);
        case 'UPPERCASE':  return String(value).toUpperCase();
        case 'LOWERCASE':  return String(value).toLowerCase();
        case 'TRIM':       return String(value).trim();
        case 'DATE_ISO': {
          const d = new Date(value);
          if (isNaN(d.getTime())) {
            warnings.push(`Rule "${ruleName}": cannot parse "${value}" as a date`);
            return value;
          }
          return d.toISOString();
        }
        default:
          warnings.push(`Rule "${ruleName}": unknown transformationType "${type}" — skipped`);
          return value;
      }
    } catch {
      warnings.push(`Rule "${ruleName}": transformation "${type}" threw an error — using raw value`);
      return value;
    }
  }

  private walkObject(obj: any, prefix: string, paths: string[]): void {
    if (obj === null || obj === undefined) return;

    if (Array.isArray(obj)) {
      obj.forEach((item, i) => {
        this.walkObject(item, prefix ? `${prefix}[${i}]` : `[${i}]`, paths);
      });
      return;
    }

    if (typeof obj === 'object') {
      for (const key of Object.keys(obj)) {
        const fullPath = prefix ? `${prefix}.${key}` : key;
        const child = obj[key];
        if (child !== null && typeof child === 'object' && !Array.isArray(child) && Object.keys(child).length > 0) {
          this.walkObject(child, fullPath, paths);
        } else {
          paths.push(fullPath);
          if (Array.isArray(child) && child.length > 0) {
            this.walkObject(child, fullPath, paths);
          }
        }
      }
      return;
    }

    // Primitive leaf
    if (prefix) paths.push(prefix);
  }
}
