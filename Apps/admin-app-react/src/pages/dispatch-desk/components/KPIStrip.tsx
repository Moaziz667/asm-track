import React from 'react';
import { IconClock } from '@tabler/icons-react';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';

export function KPIStrip() {
  const {
    t,
    kpis,
    lastUpdated,
    refreshing,
    doRefresh,
  } = useDispatchDeskContext();

  return (
    <div className="flex items-center h-11 shrink-0 px-4 gap-5" style={{ borderBottom: '1px solid var(--border)', background: 'var(--surface)' }}>
      {[
        { label: t.dispatchDeskPage.kpiCritical,   value: kpis.critical,   color: '#EF4444' },
        { label: t.dispatchDeskPage.kpiUnassigned, value: kpis.unassigned, color: '#6366F1' },
        { label: t.dispatchDeskPage.kpiInTransit,  value: kpis.inTransit,  color: '#F97316' },
        { label: t.dispatchDeskPage.kpiFailed,     value: kpis.failed,     color: '#DC2626' },
      ].map((kpi) => (
        <div key={kpi.label} className="flex items-center gap-1.5">
          <div style={{ width: 6, height: 6, borderRadius: '50%', background: kpi.value > 0 ? kpi.color : 'var(--border-strong)', flexShrink: 0 }} />
          <span style={{ fontSize: 13, fontWeight: 600, color: kpi.value > 0 ? 'var(--text-primary)' : 'var(--text-soft)', fontVariantNumeric: 'tabular-nums' }}>
            {kpi.value}
          </span>
          <span style={{ fontSize: 11, fontWeight: 400, color: 'var(--text-muted)' }}>
            {kpi.label}
          </span>
        </div>
      ))}
      <div className="flex items-center gap-2.5 ml-auto">
        {lastUpdated && (
          <span className="inline-flex items-center gap-1 text-[10px]" style={{ color: 'var(--text-muted)' }}>
            <IconClock size={11} />
            <span>{t.dispatchDeskPage.kpiUpdated}</span>
            <span className="font-mono">{lastUpdated.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })}</span>
          </span>
        )}
        <span style={{ width: 1, height: 16, background: 'var(--border)' }} />
        <Tooltip>
          <TooltipTrigger asChild>
            <RefreshButton refreshing={refreshing} onClick={doRefresh} />
          </TooltipTrigger>
          <TooltipContent>{t.dispatchDeskPage.buttonRefresh}</TooltipContent>
        </Tooltip>
      </div>
    </div>
  );
}
