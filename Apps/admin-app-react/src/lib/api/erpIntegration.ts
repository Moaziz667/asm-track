import { api } from './api';

/**
 * The ERP integration surface: connection, certification, business-field mapping, preview.
 *
 * Paths here are resource-relative (`/settings/erp`, not `/api/v1/settings/erp`): the `/api/v1`
 * prefix lives once in the client's `baseURL`, so a future v2 is a second axios instance rather
 * than a rewrite of every call site.
 */

// ── Connection ────────────────────────────────────────────────────────────────────────────────────

export type ErpProvider = 'NONE' | 'ODOO' | 'DUX' | 'ERPNEXT';
export type ConnStatus = 'NOT_CONFIGURED' | 'CONFIGURED' | 'CONNECTED' | 'ERROR';

export interface ErpConfig {
  url?: string;
  db?: string;
  login?: string;
  apiKey?: string;
  apiSecret?: string;
  company?: string;
  reportId?: string;
}

export interface ErpSettings {
  activeErpProvider: ErpProvider;
  erpConfiguration: ErpConfig | null;
  connectionStatus?: ConnStatus;
  lastTestedAt?: string | null;
  lastConnectedAt?: string | null;
  lastError?: string | null;
  lastTestUid?: string | null;
}

export const getErpSettings = () =>
  api.get<ErpSettings>('/settings/erp').then((r) => r.data);

export const saveErpSettings = (settings: ErpSettings) =>
  api.put('/settings/erp', settings).then((r) => r.data);

/** Verifies the values on screen. Also certifies the contract — a NO_GO is refused. */
export const testErpSettings = (settings: ErpSettings) =>
  api.post<{ status?: string; uid?: string; error?: string }>('/settings/erp/test', settings)
    .then((r) => r.data);

/** Verifies what is stored, so a masked secret can't let stale credentials pass. */
export const testStoredErpSettings = () =>
  api.post<{ status?: string; uid?: string; error?: string }>('/settings/erp/test-stored')
    .then((r) => r.data);

// ── Certification ─────────────────────────────────────────────────────────────────────────────────

export type Verdict = 'GO' | 'DEGRADED' | 'NO_GO';
export type CheckStatus = 'OK' | 'MISSING' | 'UNKNOWN';
export type Severity = 'REQUIRED' | 'RECOMMENDED';
export type CheckKind = 'MODEL' | 'FIELD' | 'METHOD' | 'ACCESS';

export interface CapabilityCheck {
  capability: string;
  kind: CheckKind;
  severity: Severity;
  status: CheckStatus;
  /** English prose from the probe. Rendered only where `reasonKey` has no translation. */
  detail: string;
  /**
   * Why the status is what it is — `expectedAbsent`, `fieldAbsent`, `accessDenied`…
   *
   * Absent on rows whose status needs no explaining, and absent from any provider whose probe has not
   * been keyed yet (ERPNext today), where `detail` remains the only text available.
   */
  reasonKey?: string | null;
  /** Values the translated reason interpolates, e.g. `{ version: 19 }`. */
  reasonParams?: Record<string, string | number> | null;
}

export interface ConformanceReport {
  provider: string;
  detectedVersion: string;
  verdict: Verdict;
  checks: CapabilityCheck[];
  checkedAt: string;
}

/** 204 when no ERP is configured — the caller renders the "nothing to certify yet" state. */
export const getConformance = (forceRefresh = false) =>
  api.get<ConformanceReport | ''>('/settings/erp/conformance', { params: { forceRefresh } })
    .then((r) => (r.status === 204 || !r.data ? null : (r.data as ConformanceReport)));

// ── Business-field mapping ────────────────────────────────────────────────────────────────────────

export type FieldScope = 'HEADER' | 'LINE';
export type SourceKind = 'AUTO' | 'LABEL' | 'ID' | 'RAW';

export interface CanonicalFieldInfo {
  field: string;
  scope: FieldScope;
  /**
   * Where the value comes from when nothing is mapped, e.g. `res.partner.phone`.
   *
   * `'—'` when several ERP fields combine into one, and absent while no ERP is configured — an
   * empty hint reads as "not known yet", a wrong one sends someone to the wrong field.
   */
  defaultSource?: string;
}

export interface FieldMapping {
  id: number;
  tenantId: string;
  provider: string;
  /** Null for a customer-defined extra, which is identified by customKey instead. */
  canonicalField: string | null;
  customKey: string | null;
  sourcePath: string;
  readAs: SourceKind;
  updatedBy?: string | null;
  updatedAt?: string | null;
}

/** One selectable ERP field. `custom` marks the customer's own `x_*` fields — the interesting ones. */
export interface ErpField {
  name: string;
  label: string;
  type: string;
  relation?: string | null;
  custom: boolean;
}

/** No provider: the backend answers for whichever ERP this company has configured. */
export const getFieldMappings = (provider?: string) =>
  api.get<FieldMapping[]>('/settings/erp/field-mappings',
    { params: provider ? { provider } : undefined })
    .then((r) => r.data);

export const getCanonicalFields = () =>
  api.get<CanonicalFieldInfo[]>('/settings/erp/field-mappings/canonical-fields')
    .then((r) => r.data);

/**
 * Which documents each scope may be mapped from — served rather than hardcoded, so the picker and
 * the resolver cannot drift, and so a non-Odoo tenant is not offered Odoo's models.
 */
export const getMappingScopes = () =>
  api.get<Partial<Record<FieldScope, { primary: string; addressable: string[] }>>>(
    '/settings/erp/field-mappings/scopes').then((r) => r.data);

export const getAvailableFields = (model?: string) =>
  api.get<Record<string, ErpField[]>>('/settings/erp/field-mappings/available-fields',
    { params: model ? { model } : undefined })
    .then((r) => r.data);

export interface UpsertMappingInput {
  provider?: string;
  canonicalField?: string | null;
  customKey?: string | null;
  sourcePath: string;
  readAs?: SourceKind;
  updatedBy?: string | null;
}

export const upsertFieldMapping = (input: UpsertMappingInput) =>
  api.post<FieldMapping>('/settings/erp/field-mappings', input).then((r) => r.data);

/** Removing a mapping restores the shipped default; it does not blank the field. */
export const deleteFieldMapping = (canonicalField: string, provider?: string) =>
  api.delete(`/settings/erp/field-mappings/${encodeURIComponent(canonicalField)}`,
    { params: provider ? { provider } : undefined });

export const deleteFieldMappingById = (id: number) =>
  api.delete(`/settings/erp/field-mappings/by-id/${id}`);

// ── Preview on a real order ───────────────────────────────────────────────────────────────────────

export interface PendingOrderSummary {
  erpOrderId: string;
  blNumber?: string | null;
  saleOrderRef?: string | null;
  customerName?: string | null;
  alreadyImported: boolean;
  ready: boolean;
}

export interface OrderPreview {
  erpOrderId?: string | null;
  blNumber?: string | null;
  saleOrderRef?: string | null;
  customerRef?: string | null;
  customerName?: string | null;
  customerPhone?: string | null;
  deliveryAddress?: string | null;
  deliveryCity?: string | null;
  deliveryInstructions?: string | null;
  totalAmount?: number | null;
  currency?: string | null;
  priority?: string | null;
  scheduledAt?: string | null;
  dateOrder?: string | null;
  warehouseCode?: string | null;
  warehouseName?: string | null;
  totalQuantity?: number | null;
  customFields?: Record<string, unknown> | null;
  items?: Array<{ sku?: string | null; name?: string | null; quantity?: number | null }> | null;
}

export const getPendingOrders = (limit = 40, forceRefresh = false) =>
  api.get<PendingOrderSummary[]>('/admin/erp/pending-orders',
    { params: { limit, forceRefresh } }).then((r) => r.data);

export const getOrderPreview = (erpOrderId: string) =>
  api.get<OrderPreview>('/admin/erp/pending-orders/preview',
    { params: { erpOrderId } }).then((r) => r.data);

// ── Derived helpers ───────────────────────────────────────────────────────────────────────────────

/**
 * Which canonical field each preview key comes from, so the preview can show the mapping that
 * produced a value next to the value itself.
 */
export const PREVIEW_FIELD_MAP: Array<{ canonical: string; key: keyof OrderPreview }> = [
  { canonical: 'CUSTOMER_NAME', key: 'customerName' },
  { canonical: 'CUSTOMER_PHONE', key: 'customerPhone' },
  { canonical: 'DELIVERY_ADDRESS', key: 'deliveryAddress' },
  { canonical: 'DELIVERY_CITY', key: 'deliveryCity' },
  { canonical: 'DELIVERY_INSTRUCTIONS', key: 'deliveryInstructions' },
  { canonical: 'SALE_ORDER_REF', key: 'saleOrderRef' },
  { canonical: 'CUSTOMER_REF', key: 'customerRef' },
  { canonical: 'TOTAL_AMOUNT', key: 'totalAmount' },
  { canonical: 'CURRENCY', key: 'currency' },
  { canonical: 'PRIORITY', key: 'priority' },
  { canonical: 'SCHEDULED_AT', key: 'scheduledAt' },
  { canonical: 'DATE_ORDER', key: 'dateOrder' },
  { canonical: 'WAREHOUSE_CODE', key: 'warehouseCode' },
  { canonical: 'WAREHOUSE_NAME', key: 'warehouseName' },
];

/** A REQUIRED capability that is genuinely absent is what blocks activation. */
export const blockingChecks = (report: ConformanceReport | null): CapabilityCheck[] =>
  (report?.checks ?? []).filter((c) => c.severity === 'REQUIRED' && c.status === 'MISSING');
