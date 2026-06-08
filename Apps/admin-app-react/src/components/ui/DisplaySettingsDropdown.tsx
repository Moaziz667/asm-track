import React, { useState, useRef, useEffect, useCallback } from 'react';
import { useT } from '@/lib/LocaleContext';
import {
  DndContext,
  closestCenter,
  PointerSensor,
  useSensor,
  useSensors,
  DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  verticalListSortingStrategy,
  useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { IconAdjustmentsHorizontal, IconGripVertical, IconCheck, IconRotateClockwise } from '@tabler/icons-react';
import type { ColumnDef } from '@/hooks/useColumnSettings';
import type { Density } from '@/hooks/useDensity';
import { cn } from '@/lib/utils';

// ── Sortable column row ────────────────────────────────────────────────────────

function SortableColumnRow({
  col,
  visible,
  onToggle,
}: {
  col: ColumnDef;
  visible: boolean;
  onToggle: () => void;
}) {
  const t = useT();
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: col.id,
    disabled: !!col.pinned,
  });

  return (
    <div
      ref={setNodeRef}
      style={{
        transform: CSS.Translate.toString(transform),
        transition,
        opacity: isDragging ? 0.4 : 1,
        background: 'var(--surface)',
      }}
      className="flex items-center gap-2 px-2 py-[3.5px] rounded hover:bg-[var(--hover-bg)]"
    >
      {/* Drag handle */}
      <div
        {...(col.pinned ? {} : { ...attributes, ...listeners })}
        className={cn(
          'flex-shrink-0 w-3.5 flex items-center justify-center',
          col.pinned ? 'opacity-0 pointer-events-none' : 'cursor-grab active:cursor-grabbing text-[var(--text-muted)] hover:text-[var(--text-secondary)]',
        )}
      >
        <IconGripVertical size={12} />
      </div>

      {/* Checkbox */}
      <div
        onClick={col.pinned ? undefined : onToggle}
        className={cn(
          'flex-shrink-0 w-3.5 h-3.5 rounded-sm border flex items-center justify-center transition-colors cursor-pointer',
          visible
            ? 'border-[var(--brand)] bg-[var(--brand)]'
            : 'border-[var(--border)] bg-transparent hover:border-[var(--brand)]',
          col.pinned && 'opacity-50 cursor-default pointer-events-none',
        )}
      >
        {visible && <IconCheck size={9} style={{ color: 'white' }} strokeWidth={3} />}
      </div>

      <span
        className="text-[11px] flex-1 select-none truncate"
        style={{ color: col.pinned ? 'var(--text-muted)' : 'var(--text-primary)' }}
      >
        {col.label}
      </span>

      {col.pinned && (
        <span
          className="text-[9px] font-medium px-1 py-0.5 rounded"
          style={{ color: 'var(--text-muted)', background: 'var(--hover-bg)' }}
        >
          {t.displaySettings.pinned}
        </span>
      )}
    </div>
  );
}

// ── Main component ─────────────────────────────────────────────────────────────

interface DisplaySettingsDropdownProps {
  columns: ColumnDef[];
  visibleIds: Set<string>;
  onToggle: (id: string) => void;
  onReorder: (activeId: string, overId: string) => void;
  onReset?: () => void;
  density?: Density;
  onDensityChange?: (d: Density) => void;
  disabled?: boolean;
}

export function DisplaySettingsDropdown({
  columns,
  visibleIds,
  onToggle,
  onReorder,
  onReset,
  density,
  onDensityChange,
  disabled,
}: DisplaySettingsDropdownProps) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  const DENSITY_LABELS: Record<Density, string> = {
    compact:     t.displaySettings.compact,
    comfortable: t.displaySettings.comfortable,
    spacious:    t.displaySettings.spacious,
  };

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  const handleDragEnd = useCallback((event: DragEndEvent) => {
    const { active, over } = event;
    if (over && active.id !== over.id) {
      onReorder(String(active.id), String(over.id));
    }
  }, [onReorder]);

  return (
    <div ref={ref} className="relative">
      {/* Trigger */}
      <button
        type="button"
        disabled={disabled}
        onClick={() => setOpen(v => !v)}
        className={cn(
          'h-7 w-7 flex items-center justify-center rounded border transition-colors',
          open
            ? 'border-[var(--brand)] text-[var(--brand)] bg-[var(--brand-blue-soft)]'
            : 'border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)]',
          disabled && 'opacity-40 cursor-default pointer-events-none',
        )}
        title={t.displaySettings.title}
      >
        <IconAdjustmentsHorizontal size={14} />
      </button>

      {/* Panel */}
      {open && (
        <div
          className="absolute right-0 top-[calc(100%+4px)] z-[200] w-[185px] rounded-lg border py-1.5"
          style={{
            background: 'var(--surface)',
            borderColor: 'var(--border)',
            boxShadow: 'var(--shadow-dropdown)',
          }}
          onMouseDown={e => e.stopPropagation()}
        >
          {/* Density */}
          {density !== undefined && onDensityChange && (
            <div className="px-2.5 pb-2 mb-1" style={{ borderBottom: '1px solid var(--border)' }}>
              <p
                className="text-[9px] font-bold uppercase tracking-widest mb-1.5"
                style={{ color: 'var(--text-muted)' }}
              >
                {t.displaySettings.density}
              </p>
              <div className="flex gap-1">
                {(['compact', 'comfortable', 'spacious'] as Density[]).map(d => (
                  <button
                    key={d}
                    type="button"
                    onClick={() => onDensityChange(d)}
                    className="flex-1 h-6 text-[10px] font-bold rounded transition-colors"
                    style={{
                      border: density === d ? '1.5px solid var(--brand)' : '1px solid var(--border)',
                      color: density === d ? 'var(--brand)' : 'var(--text-muted)',
                      background: density === d ? 'var(--brand-blue-soft)' : 'transparent',
                    }}
                  >
                    {DENSITY_LABELS[d]}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Columns */}
          <div className="px-2.5 flex items-center justify-between mb-1 mt-1">
            <p
              className="text-[9px] font-bold uppercase tracking-widest"
              style={{ color: 'var(--text-muted)' }}
            >
              {t.displaySettings.columns}
            </p>
            {onReset && (
              <button
                type="button"
                onClick={onReset}
                className="flex items-center gap-0.5 text-[10px] transition-colors"
                style={{ color: 'var(--text-muted)' }}
                onMouseEnter={e => (e.currentTarget.style.color = 'var(--text-primary)')}
                onMouseLeave={e => (e.currentTarget.style.color = 'var(--text-muted)')}
              >
                <IconRotateClockwise size={10} />
                {t.displaySettings.reset}
              </button>
            )}
          </div>

          <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
            <SortableContext items={columns.map(c => c.id)} strategy={verticalListSortingStrategy}>
              <div className="px-1">
                {columns.map(col => (
                  <SortableColumnRow
                    key={col.id}
                    col={col}
                    visible={visibleIds.has(col.id)}
                    onToggle={() => onToggle(col.id)}
                  />
                ))}
              </div>
            </SortableContext>
          </DndContext>
        </div>
      )}
    </div>
  );
}
