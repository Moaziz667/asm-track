import React from 'react';
import { cn } from '@/lib/utils';
import { IconX, IconAlertCircle } from '@tabler/icons-react';
import { IconAssign, IconReassign } from '@/components/icons/DispatchIcons';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';
import { useHandoffs } from '../hooks/useHandoffs';
import { HandoffCards } from './HandoffCards';
import { ActionCards } from './ActionCards';
import { DeliveryCards } from './DeliveryCards';

export function DispatchTabs() {
  const {
    t,
    isReadOnly,
    dispatchTab,
    setDispatchTab,
    tabCounts,
    newSinceLoad,
    setNewSinceLoad,
    doRefresh,
    selectedIds,
    setSelectedIds,
    batchType,
    selectedTargets,
    setDrawerTargets,
  } = useDispatchDeskContext();

  const handoffs = useHandoffs();

  const isHandoffTab = dispatchTab === 'handoff';
  const isActionTab  = dispatchTab === 'action';

  return (
    <div className="flex-1 flex flex-col min-h-0 overflow-hidden">
      {/* Tab bar */}
      <div className="flex items-stretch h-10 shrink-0 flex-nowrap" style={{ borderBottom: '1px solid var(--border)', background: 'var(--surface)' }}>
        {([
          { id: 'assign',  label: t.dispatchDeskPage.tabAssign,     count: tabCounts.assign },
          { id: 'action',  label: t.dispatchDeskPage.tabAction,     count: tabCounts.action },
          { id: 'failed',  label: t.dispatchDeskPage.tabFailed,     count: tabCounts.failed },
          { id: 'gps',     label: t.dispatchDeskPage.tabMissingGps, count: tabCounts.gps },
          { id: 'handoff', label: t.dispatchDeskPage.tabHandoff,    count: handoffs.open.length, danger: handoffs.overdueCount > 0 },
        ] as const).map(tab => {
          const count  = tab.count;
          const active = dispatchTab === tab.id;
          const danger = 'danger' in tab && tab.danger;
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => { setDispatchTab(tab.id); setSelectedIds(new Set()); }}
              className="flex items-center gap-1.5 px-4 h-full shrink-0 transition-colors"
              style={{
                borderBottom: `2px solid ${active ? 'var(--text-primary)' : 'transparent'}`,
                fontSize: 12,
                fontWeight: active ? 500 : 400,
                color: active ? 'var(--text-primary)' : 'var(--text-muted)',
              }}
            >
              {tab.label}
              {count > 0 && (
                <span style={{
                  fontSize: 10,
                  fontWeight: 500,
                  background: danger ? 'rgba(220,38,38,0.12)' : active ? 'var(--brand-soft)' : 'var(--hover-bg)',
                  color: danger ? '#dc2626' : active ? 'var(--brand)' : 'var(--text-muted)',
                  borderRadius: 10,
                  padding: '1px 6px',
                  lineHeight: 1.6,
                }}>
                  {count}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {isHandoffTab ? (
        <HandoffCards
          open={handoffs.open}
          loading={handoffs.loading}
          isReadOnly={isReadOnly}
          cancellingId={handoffs.cancellingId}
          onCancel={handoffs.cancel}
          t={t}
        />
      ) : (
        <>
          {/* New alerts banner */}
          {newSinceLoad > 0 && (
            <div className="flex items-center justify-between px-4 py-1.5 shrink-0" style={{ borderBottom: '1px solid var(--border)', background: 'var(--brand-soft)' }}>
              <div className="flex items-center gap-2">
                <IconAlertCircle size={13} style={{ color: 'var(--text-secondary)' }} />
                <span className="text-[11px] font-[500]" style={{ color: 'var(--text-secondary)' }}>
                  {newSinceLoad} {newSinceLoad > 1 ? t.dispatchDeskPage.newAlertPlural : t.dispatchDeskPage.newAlertSingular}
                </span>
              </div>
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => { setNewSinceLoad(0); doRefresh(); }}
                  className="text-[11px] font-[500] h-6 px-2 rounded-[var(--radius)] border transition-colors hover:bg-[var(--hover-bg)]"
                  style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}
                >
                  {t.dispatchDeskPage.newAlertBannerRefresh}
                </button>
                <button
                  type="button"
                  className="w-5 h-5 flex items-center justify-center rounded"
                  style={{ color: 'var(--text-muted)' }}
                  onClick={() => setNewSinceLoad(0)}
                >
                  <IconX size={11} />
                </button>
              </div>
            </div>
          )}

          {/* Batch bar */}
          {selectedIds.size > 0 && (
            <div className="flex items-center gap-3 px-4 h-10 shrink-0" style={{ borderBottom: '1px solid var(--border)', background: 'var(--brand-soft)' }}>
              <span className="text-[12px] font-[500]" style={{ color: 'var(--text-primary)' }}>
                {selectedIds.size} sélectionnée{selectedIds.size > 1 ? 's' : ''}
              </span>
              {batchType === 'mixed' ? (
                <span className="text-[11px]" style={{ color: 'var(--text-muted)' }}>Sélection mixte — choisissez un seul type</span>
              ) : (
                batchType !== 'none' && (
                  <button
                    type="button"
                    className="text-[11px] font-[500] h-6 px-2.5 rounded-[var(--radius)] border flex items-center gap-1 transition-colors"
                    style={{ background: 'var(--brand)', borderColor: 'var(--brand)', color: '#fff' }}
                    onClick={() => setDrawerTargets(selectedTargets)}
                    disabled={isReadOnly}
                  >
                    {batchType === 'assign' ? <IconAssign size={13} /> : <IconReassign size={13} />}
                    {batchType === 'assign' ? `Assigner (${selectedIds.size})` : `Réassigner (${selectedIds.size})`}
                  </button>
                )
              )}
              <button
                type="button"
                onClick={() => setSelectedIds(new Set())}
                className="text-[11px] h-6 px-2 rounded-[var(--radius)] transition-colors hover:bg-[var(--hover-bg)]"
                style={{ color: 'var(--text-muted)' }}
              >
                Annuler
              </button>
            </div>
          )}

          {/* Content — every tab is a card view */}
          {isActionTab ? <ActionCards /> : <DeliveryCards />}
        </>
      )}
    </div>
  );
}
