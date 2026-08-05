import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { IconLock } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import { hasPerm } from '@/lib/api/auth';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { tw } from '@/lib/ui/typography';
import {
  getErpSettings, saveErpSettings, testErpSettings, testStoredErpSettings,
  getConformance, getFieldMappings, getCanonicalFields, getAvailableFields, getMappingScopes,
  upsertFieldMapping, deleteFieldMapping, deleteFieldMappingById, blockingChecks,
  type ErpSettings, type ErpConfig, type ErpProvider, type ConnStatus,
  type ConformanceReport, type FieldMapping, type CanonicalFieldInfo, type ErpField,
  type UpsertMappingInput,
} from '@/lib/api/erpIntegration';
import { StepRail, type StepDescriptor, type StepId, type StepState } from './erp/StepRail';
import type { MappingScopes } from './erp/mappingScope';
import { StepConnection, type EffStatus } from './erp/StepConnection';
import { StepCompatibility } from './erp/StepCompatibility';
import { StepMapping } from './erp/StepMapping';
import { StepPreview } from './erp/StepPreview';
import { StepActivation } from './erp/StepActivation';

const EMPTY_ODOO: ErpConfig = { url: '', db: '', login: '', apiKey: '', reportId: 'stock.report_deliveryslip' };
const EMPTY_ERPNEXT: ErpConfig = { url: '', apiKey: '', apiSecret: '', company: '' };

/**
 * The ERP integration surface, as a flow an integrator can walk.
 *
 * It is deliberately not a modal wizard. This screen is two things at once: an onboarding walked
 * once, and the place someone returns to months later to remap a single field. A wizard serves the
 * first and punishes the second — so steps unlock linearly but stay reachable afterwards.
 */
export default function ErpIntegrationPage() {
  const t = useT();
  // The dictionary is deeply typed per key; the step components take a flat string map, so each
  // section is widened once here rather than at every call site.
  const copy = t.erpSetup as unknown as Record<string, Record<string, string>>;
  const c = (section: string): Record<string, string> => copy?.[section] ?? {};

  // The route already gates on this permission; the page re-reads it to decide read-only vs editable.
  const canManage = hasPerm('perm:settings:manage');

  const [settings, setSettings] = useState<ErpSettings | null>(null);
  const [report, setReport] = useState<ConformanceReport | null>(null);
  const [mappings, setMappings] = useState<FieldMapping[]>([]);
  const [canonicalFields, setCanonicalFields] = useState<CanonicalFieldInfo[]>([]);
  const [availableFields, setAvailableFields] = useState<Record<string, ErpField[]>>({});
  /** Which documents each scope may read from — from the backend, never assumed. */
  const [scopes, setScopes] = useState<MappingScopes>({});

  const [loading, setLoading] = useState(true);
  const [loadingReport, setLoadingReport] = useState(false);
  /** Set when the configured instance changed, so the next probe ignores the adapter's cache. */
  const forceNextReport = useRef(false);
  /**
   * Whether the report has been fetched for the current connection — regardless of the outcome.
   *
   * A null report means three different things: never asked, asked and the ERP has nothing to
   * certify (204), asked and the call failed. The auto-fetch below keys on `!report`, so the last
   * two used to re-arm it on every render: the probe reran forever, each round hitting the
   * customer's ERP, and the progress panel restarted before any result could be read. This ref is
   * the missing distinction — it is cleared only when the connection changes, which is the one
   * event that makes a previous answer worth discarding.
   */
  const reportAttempted = useRef(false);
  /** Same distinction for the field catalogue, which is empty both before and after a failed load. */
  const mappingAttempted = useRef(false);
  const [loadingMapping, setLoadingMapping] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [live, setLive] = useState<'idle' | 'ok' | 'fail'>('idle');
  const [step, setStep] = useState<StepId>('connection');

  // ── Loading ────────────────────────────────────────────────────────────────────────────────────

  const loadSettings = useCallback(async () => {
    try {
      const data = await getErpSettings();
      setSettings(data);
      setDirty(false);
      setLive('idle');
      return data;
    } catch {
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  // forceRefresh only from the re-check button: everything else is happy with the adapter's
  // short-lived cache, which is what stops a page visit costing seconds against the customer's ERP.
  const loadReport = useCallback(async (forceRefresh = false) => {
    reportAttempted.current = true;
    setLoadingReport(true);
    try { setReport(await getConformance(forceRefresh)); }
    catch { setReport(null); }
    finally { setLoadingReport(false); }
  }, []);

  const loadMapping = useCallback(async () => {
    mappingAttempted.current = true;
    setLoadingMapping(true);
    try {
      const [fields, current] = await Promise.all([getCanonicalFields(), getFieldMappings()]);
      setCanonicalFields(fields);
      setMappings(current);
      // The field catalogue is the slowest call (one fields_get per model); never let it block the
      // rows from rendering, so the integrator sees their existing mapping immediately.
      getAvailableFields().then(setAvailableFields).catch(() => setAvailableFields({}));
      getMappingScopes().then(setScopes).catch(() => setScopes({}));
    } catch { /* the step renders its own empty state */ }
    finally { setLoadingMapping(false); }
  }, []);

  // Derived here rather than beside the step model, because the loader below needs it: the rail's
  // ticks come from the report and are not stored anywhere, so a reload that never fetches it
  // redraws a certified integration as an unfinished one.
  const persisted = (settings?.connectionStatus ?? 'NOT_CONFIGURED') as ConnStatus;
  const effectiveStatus: EffStatus =
    live === 'ok' ? 'CONNECTED'
    : live === 'fail' ? 'ERROR'
    : (dirty && (persisted === 'CONNECTED' || persisted === 'ERROR')) ? 'STALE'
    : persisted;

  const connected = effectiveStatus === 'CONNECTED';

  useEffect(() => { loadSettings(); }, [loadSettings]);

  // Each step fetches what it needs when first opened, so the page is fast to land on — except the
  // report, which the rail itself depends on and so is fetched as soon as a connection exists.
  useEffect(() => {
    // Only once the connection is verified. The adapter refuses to use credentials that are merely
    // CONFIGURED ("refusing to use these credentials"), so probing before then can only come back
    // empty — and an empty answer is what used to re-arm this effect. The rail locks the step until
    // then anyway, so requiring CONNECTED here removes a request that never had an answer to give.
    const needsReport = connected;
    if (needsReport && !report && !loadingReport && !reportAttempted.current) {
      loadReport(forceNextReport.current);
      forceNextReport.current = false;
    }
    if ((step === 'mapping' || step === 'preview')
        && canonicalFields.length === 0 && !loadingMapping && !mappingAttempted.current) {
      loadMapping();
    }
  }, [step, connected, report, loadingReport, loadReport, canonicalFields.length, loadingMapping, loadMapping]);


  // ── Connection ─────────────────────────────────────────────────────────────────────────────────

  const patchConfig = (patch: Partial<ErpConfig>) => {
    setDirty(true); setLive('idle');
    setSettings((p) => p ? { ...p, erpConfiguration: { ...(p.erpConfiguration ?? {}), ...patch } } : p);
  };

  const setProvider = (provider: ErpProvider) => {
    setDirty(true); setLive('idle');
    setSettings((p) => p ? {
      ...p,
      activeErpProvider: provider,
      erpConfiguration: provider === 'NONE' ? null
        : (p.erpConfiguration ?? { ...(provider === 'ERPNEXT' ? EMPTY_ERPNEXT : EMPTY_ODOO) }),
    } : p);
  };

  /** A passing test invalidates the report: the instance it certified may not be the one now configured. */
  const afterConnectionChange = async () => {
    setReport(null);
    // The previous answer certified an instance we may no longer be pointing at, so the auto-fetch
    // is re-armed here — the only place it is. The field catalogue too: it is read from the ERP now
    // being pointed at, so a different instance means a different set of fields.
    reportAttempted.current = false;
    mappingAttempted.current = false;
    setCanonicalFields([]);
    // Dropping the local copy is no longer enough now that the adapter caches per tenant: the
    // refetch below would be handed the report for the instance we just stopped pointing at. A ref
    // rather than a second fetch here, so the reload stays a single request with no race over which
    // answer lands last.
    forceNextReport.current = true;
    await loadSettings();
  };

  const runTest = async (stored: boolean) => {
    try {
      if (stored) await testStoredErpSettings();
      else if (settings) await testErpSettings(settings);
      setLive('ok'); setDirty(false);
      return true;
    } catch {
      setLive('fail');
      return false;
    }
  };

  const handleTest = async () => {
    setTesting(true);
    try {
      const ok = await runTest(false);
      if (ok) showSuccessToast(c('connection').testSuccess);
      else showErrorToast(new Error(c('connection').testFailed));
      await afterConnectionChange();
    } finally { setTesting(false); }
  };

  const handleSave = async () => {
    if (!settings) return;
    setSaving(true);
    try {
      await saveErpSettings(settings);
      if (settings.activeErpProvider === 'NONE') {
        showSuccessToast(c('connection').saved);
        await afterConnectionChange();
        return;
      }
      setTesting(true);
      const ok = await runTest(true);
      if (ok) showSuccessToast(c('connection').savedAndTested);
      else showErrorToast(new Error(c('connection').savedTestFailed));
      await afterConnectionChange();
    } catch {
      showErrorToast(new Error(c('connection').saveFailed));
    } finally { setTesting(false); setSaving(false); }
  };

  // ── Mapping actions ────────────────────────────────────────────────────────────────────────────

  const refreshMappings = async () => { setMappings(await getFieldMappings()); };

  const handleUpsert = async (input: UpsertMappingInput) => {
    try {
      await upsertFieldMapping(input);
      await refreshMappings();
      showSuccessToast(c('mapping').saved);
    } catch (e) {
      // The backend writes these messages for the integrator ("Chemin trop profond…"); showing our
      // own generic text instead would throw away the only thing that tells them how to fix it.
      const msg = (e as { response?: { data?: { error?: string } } })?.response?.data?.error;
      showErrorToast(new Error(msg || c('mapping').saveFailed));
      throw e;
    }
  };

  const handleReset = async (canonicalField: string) => {
    try {
      await deleteFieldMapping(canonicalField);
      await refreshMappings();
      showSuccessToast(c('mapping').reset);
    } catch { showErrorToast(new Error(c('mapping').resetFailed)); }
  };

  const handleDeleteExtra = async (id: number) => {
    try {
      await deleteFieldMappingById(id);
      await refreshMappings();
    } catch { showErrorToast(new Error(c('mapping').resetFailed)); }
  };

  /** A capability override is a different table; it lands next to the failing check, then re-certifies. */
  const handleCapabilityOverride = async (capability: string, odooName: string) => {
    const name = capability.includes('.') ? capability.slice(capability.lastIndexOf('.') + 1) : capability;
    try {
      await upsertFieldMapping({ canonicalField: name.toUpperCase(), sourcePath: odooName });
      showSuccessToast(c('compat').overrideSaved);
      // Forced: the override is precisely what the cached report does not know about, and showing
      // the stale one here would read as the override having done nothing.
      await loadReport(true);
    } catch (e) {
      const msg = (e as { response?: { data?: { error?: string } } })?.response?.data?.error;
      showErrorToast(new Error(msg || c('compat').overrideFailed));
    }
  };

  // ── Step model ─────────────────────────────────────────────────────────────────────────────────

  const blocking = blockingChecks(report);

  const steps: StepDescriptor[] = useMemo(() => {
    const s = c('steps');
    const state = (id: StepId): StepState => {
      if (step === id) return 'current';
      switch (id) {
        case 'connection':    return connected ? 'done' : 'available';
        case 'compatibility':
          if (!connected) return 'locked';
          return report ? (blocking.length > 0 ? 'attention' : 'done') : 'available';
        case 'mapping':       return connected ? 'available' : 'locked';
        case 'preview':       return connected ? 'available' : 'locked';
        // "Not certified yet" is not the same as "something is wrong". Until the report is loaded we
        // know nothing, and an alarm on a healthy long-running integration is worse than silence.
        case 'activation':
          if (!connected) return 'locked';
          return report ? (blocking.length > 0 ? 'attention' : 'done') : 'available';
      }
    };
    const lockReason = !connected ? s.lockedNeedsConnection : undefined;
    return [
      { id: 'connection',    label: s.connection,    hint: s.connectionHint,    state: state('connection') },
      { id: 'compatibility', label: s.compatibility, hint: s.compatibilityHint, state: state('compatibility'), lockReason },
      { id: 'mapping',       label: s.mapping,       hint: s.mappingHint,       state: state('mapping'), lockReason },
      { id: 'preview',       label: s.preview,       hint: s.previewHint,       state: state('preview'), lockReason },
      { id: 'activation',    label: s.activation,    hint: s.activationHint,    state: state('activation'), lockReason },
    ];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step, connected, report, blocking.length, t]);

  const activeStep = steps.find((s) => s.id === step);

  // ── Render ─────────────────────────────────────────────────────────────────────────────────────

  return (
    <div className="h-[calc(100vh-64px)] overflow-y-auto bg-[var(--app-bg)]">
      <div className="mx-auto max-w-[1080px] p-6">
        <header className="flex items-start justify-between gap-4 border-b border-[var(--border)] pb-5">
          <div className="min-w-0">
            <h1 className={tw.pageTitle}>{c('page').title}</h1>
            <p className={cn(tw.subtitle, 'mt-0.5 max-w-[68ch]')}>{c('page').subtitle}</p>
          </div>
          {!canManage && (
            <Badge variant="outline" className="shrink-0 gap-1 border-[var(--warning)]/30 text-[var(--warning)]">
              <IconLock size={12} /> {c('page').readOnly}
            </Badge>
          )}
        </header>

        {loading ? (
          <div className="mt-6 flex flex-col gap-4 lg:flex-row lg:gap-8">
            <Skeleton className="h-[220px] w-full rounded-lg lg:w-[248px]" />
            <Skeleton className="h-[420px] w-full rounded-lg" />
          </div>
        ) : !settings ? (
          <p className="py-20 text-center text-sm text-[var(--text-muted)]">{c('page').loadError}</p>
        ) : (
          <div className="mt-6 flex flex-col gap-6 lg:flex-row lg:gap-8">
            <StepRail steps={steps} onSelect={setStep} />

            <main className="min-w-0 flex-1">
              <div className="mb-4">
                <h2 className="text-base font-semibold text-[var(--text-primary)]">{activeStep?.label}</h2>
                <p className="mt-0.5 text-xs text-[var(--text-muted)]">{activeStep?.hint}</p>
              </div>

              {/* key on step: a fresh mount per step, so no stale local state bleeds across. */}
              <div key={step} className="erp-step-panel">
                {step === 'connection' && (
                  <StepConnection
                    settings={settings}
                    status={effectiveStatus}
                    dirty={dirty}
                    saving={saving}
                    testing={testing}
                    canManage={canManage}
                    copy={c('connection')}
                    onPatchConfig={patchConfig}
                    onSetProvider={setProvider}
                    onSave={handleSave}
                    onTest={handleTest}
                  />
                )}

                {step === 'compatibility' && (
                  <StepCompatibility
                    report={report}
                    loading={loadingReport}
                    onRun={() => loadReport(true)}
                    onOverride={handleCapabilityOverride}
                    canManage={canManage}
                    copy={c('compat')}
                  />
                )}

                {step === 'mapping' && (
                  <StepMapping
                    canonicalFields={canonicalFields}
                    mappings={mappings}
                    availableFields={availableFields}
                    scopes={scopes}
                    loading={loadingMapping}
                    canManage={canManage}
                    copy={c('mapping')}
                    onUpsert={handleUpsert}
                    onReset={handleReset}
                    onDeleteCustom={handleDeleteExtra}
                  />
                )}

                {step === 'preview' && <StepPreview mappings={mappings} copy={c('preview')} />}

                {step === 'activation' && (
                  <StepActivation
                    status={effectiveStatus === 'STALE' ? 'CONFIGURED' : effectiveStatus}
                    report={report}
                    lastConnectedAt={settings.lastConnectedAt}
                    lastError={settings.lastError}
                    onRetest={handleTest}
                    testing={testing}
                    onGoToStep={setStep}
                    copy={c('activation')}
                  />
                )}
              </div>
            </main>
          </div>
        )}
      </div>
    </div>
  );
}
