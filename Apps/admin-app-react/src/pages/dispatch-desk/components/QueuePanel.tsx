import React, { useEffect, useState } from 'react';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';
import { QueueList } from './QueueList';
import { QueueDetail } from './QueueDetail';
import { useBreakpoint } from '@/hooks/use-mobile';
import { useT } from '@/lib/i18n/LocaleContext';
import { cn } from '@/lib/utils';

/**
 * Split-view ("inbox") layout for the merged Queue tab — a dense list rail
 * on the side and a detail panel that surfaces the alert narration + actions.
 * Replaces the separate Assign/Action card grids (DeliveryCards/ActionCards),
 * which rendered the same orders twice with two different visual languages.
 */
export function QueuePanel() {
  const t = useT();
  const { queueRows, selectedQueueId, setSelectedQueueId } = useDispatchDeskContext();
  const { isMobile, isTablet } = useBreakpoint();
  const isMobileOrTablet = isMobile || isTablet;
  
  const [activeTab, setActiveTab] = useState<'list' | 'detail'>('list');

  // Keep a row selected (first of the list) whenever the filtered set changes
  // and the current selection falls out of view — mirrors inbox apps (Linear/Front).
  useEffect(() => {
    if (queueRows.length === 0) {
      if (selectedQueueId !== null) setSelectedQueueId(null);
      return;
    }
    if (!selectedQueueId || !queueRows.some(q => q.id === selectedQueueId)) {
      setSelectedQueueId(queueRows[0].id);
    }
  }, [queueRows, selectedQueueId, setSelectedQueueId]);

  // Switch tab when selected ID changes on mobile
  useEffect(() => {
    if (selectedQueueId && isMobileOrTablet) {
      setActiveTab('detail');
    }
  }, [selectedQueueId, isMobileOrTablet]);

  // ↑ / ↓ moves the selection; works as long as focus isn't in a text field.
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || (e.target as HTMLElement)?.isContentEditable) return;
      if (queueRows.length === 0) return;
      e.preventDefault();
      const idx = queueRows.findIndex(q => q.id === selectedQueueId);
      const next = e.key === 'ArrowDown'
        ? Math.min(idx + 1, queueRows.length - 1)
        : Math.max(idx - 1, 0);
      setSelectedQueueId(queueRows[Math.max(next, 0)].id);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [queueRows, selectedQueueId, setSelectedQueueId]);

  if (isMobileOrTablet) {
    return (
      <div className="flex-1 flex flex-col min-h-0 overflow-hidden" style={{ background: 'var(--app-bg)' }}>
        {/* Mobile Tab Headers */}
        <div className="flex h-10 shrink-0 border-b" style={{ background: 'var(--surface)', borderColor: 'var(--border)' }}>
          <button
            type="button"
            onClick={() => setActiveTab('list')}
            className={cn(
              "flex-1 text-center text-xs font-[600] uppercase tracking-wide border-b-2 transition-colors",
              activeTab === 'list' ? 'border-[var(--brand)] text-[var(--text-primary)]' : 'border-transparent text-[var(--text-muted)]'
            )}
          >
            Exceptions ({queueRows.length})
          </button>
          <button
            type="button"
            onClick={() => setActiveTab('detail')}
            className={cn(
              "flex-1 text-center text-xs font-[600] uppercase tracking-wide border-b-2 transition-colors",
              activeTab === 'detail' ? 'border-[var(--brand)] text-[var(--text-primary)]' : 'border-transparent text-[var(--text-muted)]'
            )}
            disabled={!selectedQueueId}
          >
            {t.common?.details ?? 'Details'}
          </button>
        </div>

        {/* Tab content panes */}
        <div className="flex-1 flex flex-col min-h-0 overflow-hidden">
          {activeTab === 'list' ? (
            <div className="flex-1 flex flex-col min-h-0 overflow-hidden" style={{ background: 'var(--app-bg)' }}>
              <QueueList />
            </div>
          ) : (
            <div className="flex-1 flex flex-col min-h-0 overflow-hidden" style={{ background: 'var(--app-bg)' }}>
              <QueueDetail />
            </div>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 flex min-h-0 overflow-hidden" style={{ background: 'var(--app-bg)' }}>
      <div
        className="w-[360px] shrink-0 flex flex-col min-h-0 border-e overflow-hidden"
        style={{ background: 'var(--app-bg)', borderColor: 'var(--border)' }}
      >
        <QueueList />
      </div>
      <div className="flex-1 flex flex-col min-h-0 overflow-hidden" style={{ background: 'var(--app-bg)' }}>
        <QueueDetail />
      </div>
    </div>
  );
}
