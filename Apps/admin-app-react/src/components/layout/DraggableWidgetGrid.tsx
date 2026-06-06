import React, { useState, useEffect, useCallback } from 'react';
import {
  DndContext,
  closestCenter,
  PointerSensor,
  useSensor,
  useSensors,
  DragEndEvent,
  DragStartEvent,
  DragOverlay,
} from '@dnd-kit/core';
import {
  SortableContext,
  verticalListSortingStrategy,
  useSortable,
  arrayMove,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { cn } from '@/lib/utils';

// ── Types ─────────────────────────────────────────────────────────────────────

export interface WidgetItem {
  id: string;
  children: React.ReactNode;
  className?: string;
}

interface DraggableWidgetGridProps {
  storageKey: string;
  items: WidgetItem[];
  className?: string;
}

// ── Drag handle (always visible, brand-blue) ──────────────────────────────────

function DragHandleIcon({ bind }: { bind?: Record<string, unknown> }) {
  return (
    <div
      {...(bind ?? {})}
      className="absolute top-2 left-2 z-20 w-6 h-6 rounded flex items-center justify-center cursor-grab active:cursor-grabbing select-none"
      style={{ background: 'var(--brand-blue-soft)', color: 'var(--brand-blue)' }}
      title="Réorganiser"
      onPointerDown={e => e.stopPropagation()}
    >
      <svg width="10" height="10" viewBox="0 0 10 10" fill="currentColor">
        <circle cx="2" cy="2" r="1" /><circle cx="8" cy="2" r="1" />
        <circle cx="2" cy="5" r="1" /><circle cx="8" cy="5" r="1" />
        <circle cx="2" cy="8" r="1" /><circle cx="8" cy="8" r="1" />
      </svg>
    </div>
  );
}

// ── Sortable item slot (becomes transparent placeholder while dragging) ────────

function SortableWidget({ id, children, className }: WidgetItem) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id });

  return (
    <div
      ref={setNodeRef}
      style={{
        transform: CSS.Translate.toString(transform),
        transition: transition ?? 'transform 200ms ease',
        opacity: isDragging ? 0 : 1,
        position: 'relative',
      }}
      className={cn('relative', className)}
    >
      <DragHandleIcon bind={{ ...attributes, ...listeners }} />
      {children}
    </div>
  );
}

// ── Main export ───────────────────────────────────────────────────────────────

export function DraggableWidgetGrid({ storageKey, items, className }: DraggableWidgetGridProps) {
  const [order, setOrder] = useState<string[]>(() => {
    try {
      const stored = localStorage.getItem(`widget-order:${storageKey}`);
      if (stored) {
        const parsed: string[] = JSON.parse(stored);
        const validIds = new Set(items.map(i => i.id));
        const filtered = parsed.filter(id => validIds.has(id));
        const newIds = items.map(i => i.id).filter(id => !parsed.includes(id));
        return [...filtered, ...newIds];
      }
    } catch { /* ignore */ }
    return items.map(i => i.id);
  });

  const [activeId, setActiveId] = useState<string | null>(null);

  useEffect(() => {
    setOrder(prev => {
      const existingIds = new Set(prev);
      const newIds = items.map(i => i.id).filter(id => !existingIds.has(id));
      if (newIds.length === 0) return prev;
      return [...prev, ...newIds];
    });
  }, [items]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } })
  );

  const handleDragStart = useCallback((event: DragStartEvent) => {
    setActiveId(String(event.active.id));
  }, []);

  const handleDragEnd = useCallback((event: DragEndEvent) => {
    setActiveId(null);
    const { active, over } = event;
    if (over && active.id !== over.id) {
      setOrder(prev => {
        const oldIdx = prev.indexOf(String(active.id));
        const newIdx = prev.indexOf(String(over.id));
        const next = arrayMove(prev, oldIdx, newIdx);
        try { localStorage.setItem(`widget-order:${storageKey}`, JSON.stringify(next)); } catch { /* ignore */ }
        return next;
      });
    }
  }, [storageKey]);

  const itemMap = new Map(items.map(i => [i.id, i]));
  const sorted = order.map(id => itemMap.get(id)).filter(Boolean) as WidgetItem[];
  const activeItem = activeId ? itemMap.get(activeId) : null;

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
    >
      <SortableContext items={order} strategy={verticalListSortingStrategy}>
        <div className={className}>
          {sorted.map(item => (
            <SortableWidget key={item.id} id={item.id} className={item.className}>
              {item.children}
            </SortableWidget>
          ))}
        </div>
      </SortableContext>

      {/* Floating drag overlay — only this moves under the cursor */}
      <DragOverlay dropAnimation={{ duration: 150, easing: 'cubic-bezier(0.18,0.67,0.6,1.22)' }}>
        {activeItem && (
          <div
            className={cn('relative cursor-grabbing', activeItem.className)}
            style={{ opacity: 0.92, boxShadow: 'var(--shadow-card-hover)' }}
          >
            <DragHandleIcon />
            {activeItem.children}
          </div>
        )}
      </DragOverlay>
    </DndContext>
  );
}
