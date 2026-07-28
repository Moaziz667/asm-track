import {
  IconDatabase, IconPlugConnected, IconPlugConnectedX, IconAlertTriangle, IconCircleCheck, IconClock,
} from '@tabler/icons-react';
import { Button } from '@/components/ui/button';
import { FieldInput, FieldSelect } from '@/components/ui/field';
import type { ErpConfig, ErpProvider, ErpSettings, ConnStatus } from '@/lib/api/erpIntegration';

export type EffStatus = ConnStatus | 'STALE';

/**
 * Credentials, and the one action that proves them.
 *
 * "Tester" does more than authenticate: the backend certifies the integration contract in the same
 * call and refuses a NO_GO. So a green result here means "these credentials work *and* this ERP can
 * be driven", which is exactly what the next steps assume.
 */
export function StepConnection({
  settings, status, dirty, saving, testing, canManage, copy,
  onPatchConfig, onSetProvider, onSave, onTest,
}: {
  settings: ErpSettings;
  status: EffStatus;
  dirty: boolean;
  saving: boolean;
  testing: boolean;
  canManage: boolean;
  copy: Record<string, string>;
  onPatchConfig: (patch: Partial<ErpConfig>) => void;
  onSetProvider: (p: ErpProvider) => void;
  onSave: () => void;
  onTest: () => void;
}) {
  const provider = settings.activeErpProvider;
  const conf = settings.erpConfiguration;

  return (
    <div className="flex flex-col gap-5">
      <FieldSelect
        label={copy.provider}
        value={provider}
        onChange={(e) => onSetProvider(e.target.value as ErpProvider)}
        disabled={!canManage}
        options={[
          { value: 'NONE', label: 'NONE' },
          { value: 'ODOO', label: 'ODOO' },
          { value: 'ERPNEXT', label: 'ERPNEXT' },
          { value: 'DUX', label: 'DUX' },
        ]}
      />

      {provider === 'ODOO' && conf && (
        <ConfigGrid>
          <FieldInput
            wrapperClassName="md:col-span-2"
            label={copy.url}
            placeholder="https://client.odoo.com"
            value={conf.url ?? ''}
            onChange={(e) => onPatchConfig({ url: e.target.value })}
            disabled={!canManage}
          />
          <FieldInput label={copy.db} value={conf.db ?? ''} onChange={(e) => onPatchConfig({ db: e.target.value })} disabled={!canManage} />
          <FieldInput label={copy.login} value={conf.login ?? ''} onChange={(e) => onPatchConfig({ login: e.target.value })} disabled={!canManage} />
          <FieldInput
            wrapperClassName="md:col-span-2"
            type="password"
            label={copy.apiKey}
            placeholder="••••••••"
            value={conf.apiKey ?? ''}
            onChange={(e) => onPatchConfig({ apiKey: e.target.value })}
            disabled={!canManage}
          />
          <FieldInput
            wrapperClassName="md:col-span-2"
            label={copy.reportId}
            placeholder="stock.report_deliveryslip"
            value={conf.reportId ?? ''}
            onChange={(e) => onPatchConfig({ reportId: e.target.value })}
            disabled={!canManage}
          />
        </ConfigGrid>
      )}

      {provider === 'ERPNEXT' && conf && (
        <ConfigGrid>
          <FieldInput
            wrapperClassName="md:col-span-2"
            label={copy.url}
            placeholder="https://your-site.frappe.cloud"
            value={conf.url ?? ''}
            onChange={(e) => onPatchConfig({ url: e.target.value })}
            disabled={!canManage}
          />
          <FieldInput type="password" label={copy.apiKey} placeholder="••••••••" value={conf.apiKey ?? ''} onChange={(e) => onPatchConfig({ apiKey: e.target.value })} disabled={!canManage} />
          <FieldInput type="password" label={copy.apiSecret} placeholder="••••••••" value={conf.apiSecret ?? ''} onChange={(e) => onPatchConfig({ apiSecret: e.target.value })} disabled={!canManage} />
          <FieldInput
            wrapperClassName="md:col-span-2"
            label={copy.company}
            hint={copy.companyHint}
            placeholder="TEST (Demo)"
            value={conf.company ?? ''}
            onChange={(e) => onPatchConfig({ company: e.target.value })}
            disabled={!canManage}
          />
        </ConfigGrid>
      )}

      {provider === 'DUX' && conf && (
        <ConfigGrid single>
          <FieldInput label={copy.url} value={conf.url ?? ''} onChange={(e) => onPatchConfig({ url: e.target.value })} disabled={!canManage} />
          <FieldInput type="password" label={copy.apiKey} placeholder="••••••••" value={conf.apiKey ?? ''} onChange={(e) => onPatchConfig({ apiKey: e.target.value })} disabled={!canManage} />
        </ConfigGrid>
      )}

      {provider !== 'NONE' && (
        <Lifecycle status={status} lastTestedAt={settings.lastTestedAt} lastError={settings.lastError} copy={copy} />
      )}

      <div className="flex items-center justify-end gap-2 pt-1">
        <Button
          variant="outline"
          size="sm"
          onClick={onTest}
          disabled={testing || saving || provider === 'NONE' || dirty}
          title={dirty ? copy.testDirtyHint : undefined}
        >
          <IconPlugConnected size={15} /> {testing ? copy.testing : copy.test}
        </Button>
        {canManage && (
          <Button size="sm" onClick={onSave} disabled={saving || testing}>
            <IconDatabase size={15} /> {saving ? copy.savingAndTesting : copy.saveAndTest}
          </Button>
        )}
      </div>
    </div>
  );
}

function ConfigGrid({ children, single = false }: { children: React.ReactNode; single?: boolean }) {
  return (
    <div
      className={`grid grid-cols-1 ${single ? '' : 'md:grid-cols-2'} gap-4 p-4 rounded-lg bg-[var(--app-bg)] border border-dashed border-[var(--border)]`}
    >
      {children}
    </div>
  );
}

/** Says where the connection stands and what to do next — never just a colour. */
function Lifecycle({
  status, lastTestedAt, lastError, copy,
}: {
  status: EffStatus; lastTestedAt?: string | null; lastError?: string | null; copy: Record<string, string>;
}) {
  const tone =
    status === 'CONNECTED' ? { fg: 'var(--success)', icon: <IconCircleCheck size={16} />, msg: copy.lifecycleConnected }
    : status === 'ERROR' ? { fg: 'var(--danger)', icon: <IconPlugConnectedX size={16} />, msg: lastError || copy.lifecycleError }
    : status === 'STALE' ? { fg: 'var(--warning)', icon: <IconAlertTriangle size={16} />, msg: copy.lifecycleStale }
    : status === 'CONFIGURED' ? { fg: 'var(--warning)', icon: <IconAlertTriangle size={16} />, msg: copy.lifecycleConfigured }
    : { fg: 'var(--text-muted)', icon: <IconAlertTriangle size={16} />, msg: copy.lifecycleNotConfigured };

  return (
    <div
      className="flex items-start gap-2.5 rounded-lg p-3 text-xs"
      style={{ background: `color-mix(in srgb, ${tone.fg} 8%, transparent)`, color: tone.fg }}
    >
      <span className="shrink-0 mt-px">{tone.icon}</span>
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="font-semibold leading-snug">{tone.msg}</span>
        {lastTestedAt && (
          <span className="inline-flex items-center gap-1 text-2xs opacity-80">
            <IconClock size={11} /> {copy.lastTested} : {new Date(lastTestedAt).toLocaleString()}
          </span>
        )}
      </div>
    </div>
  );
}
