import { useState, useCallback } from 'react';
import { arrayMove } from '@dnd-kit/sortable';

export interface ColumnDef {
  id: string;
  label: string;
  pinned?: boolean; // pinned columns always visible and can't be reordered
}

interface StoredSettings {
  order: string[];
  hidden: string[];
}

export function useColumnSettings(storageKey: string, columns: ColumnDef[]) {
  const storageK = `table-cols:${storageKey}`;

  const init = (): StoredSettings => {
    try {
      const raw = localStorage.getItem(storageK);
      if (raw) {
        const parsed: StoredSettings = JSON.parse(raw);
        const allIds = new Set(columns.map(c => c.id));
        const order = [
          ...parsed.order.filter(id => allIds.has(id)),
          ...columns.map(c => c.id).filter(id => !parsed.order.includes(id)),
        ];
        return { order, hidden: parsed.hidden.filter(id => allIds.has(id)) };
      }
    } catch { /* ignore */ }
    return { order: columns.map(c => c.id), hidden: [] };
  };

  const [settings, setSettings] = useState<StoredSettings>(init);

  const save = useCallback((next: StoredSettings) => {
    setSettings(next);
    try { localStorage.setItem(storageK, JSON.stringify(next)); } catch { /* ignore */ }
  }, [storageK]);

  const orderedColumns = settings.order
    .map(id => columns.find(c => c.id === id))
    .filter(Boolean) as ColumnDef[];

  const visibleIds = new Set(
    orderedColumns
      .filter(c => c.pinned || !settings.hidden.includes(c.id))
      .map(c => c.id)
  );

  const toggleColumn = useCallback((id: string) => {
    const col = columns.find(c => c.id === id);
    if (!col || col.pinned) return;
    setSettings(prev => {
      const hidden = prev.hidden.includes(id)
        ? prev.hidden.filter(h => h !== id)
        : [...prev.hidden, id];
      const next = { ...prev, hidden };
      try { localStorage.setItem(storageK, JSON.stringify(next)); } catch { /* ignore */ }
      return next;
    });
  }, [columns, storageK]);

  const moveColumn = useCallback((activeId: string, overId: string) => {
    setSettings(prev => {
      const oldIdx = prev.order.indexOf(activeId);
      const newIdx = prev.order.indexOf(overId);
      if (oldIdx === -1 || newIdx === -1 || oldIdx === newIdx) return prev;
      const order = arrayMove(prev.order, oldIdx, newIdx);
      const next = { ...prev, order };
      try { localStorage.setItem(storageK, JSON.stringify(next)); } catch { /* ignore */ }
      return next;
    });
  }, [storageK]);

  const resetColumns = useCallback(() => {
    const next: StoredSettings = { order: columns.map(c => c.id), hidden: [] };
    save(next);
  }, [columns, save]);

  return { orderedColumns, visibleIds, toggleColumn, moveColumn, resetColumns };
}
