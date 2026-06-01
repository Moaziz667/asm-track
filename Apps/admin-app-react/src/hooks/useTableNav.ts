
import { useState, useCallback, useRef } from 'react';
import { useKeyboardShortcuts } from './useKeyboardShortcuts';

interface UseTableNavOptions<T> {
  rows: T[];
  getId: (row: T) => string;
  onEnter?: (row: T) => void;
  enabled?: boolean;
}

/**
 * Keyboard navigation for data tables.
 * Returns the focused row index and helpers to wire into DataTable.
 *
 * Arrow ↑ / ↓  → move focus
 * Enter        → trigger onEnter for focused row
 * Space        → toggle selection for focused row
 * Escape       → clear focus
 */
export function useTableNav<T>({
  rows,
  getId,
  onEnter,
  enabled = true,
}: UseTableNavOptions<T>) {
  const [focusedIndex, setFocusedIndex] = useState<number>(-1);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const containerRef = useRef<HTMLElement | null>(null);

  const move = useCallback((delta: number) => {
    setFocusedIndex((prev) => {
      const next = Math.min(Math.max(0, prev + delta), rows.length - 1);
      // Scroll the focused row into view
      const el = containerRef.current?.querySelector(`[data-row-index="${next}"]`);
      el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      return next;
    });
  }, [rows.length]);

  const toggleSelect = useCallback((id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }, []);

  const selectAll = useCallback(() => {
    setSelected(new Set(rows.map(getId)));
  }, [rows, getId]);

  const clearSelection = useCallback(() => {
    setSelected(new Set());
  }, []);

  useKeyboardShortcuts({
    'ArrowDown': () => move(1),
    'ArrowUp':   () => move(-1),
    'Enter': () => {
      if (focusedIndex >= 0 && focusedIndex < rows.length) {
        onEnter?.(rows[focusedIndex]);
      }
    },
    'Escape': () => {
      setFocusedIndex(-1);
      clearSelection();
    },
    ' ': () => {
      if (focusedIndex >= 0 && focusedIndex < rows.length) {
        toggleSelect(getId(rows[focusedIndex]));
      }
    },
    'meta+a': () => selectAll(),
  }, enabled);

  return {
    focusedIndex,
    setFocusedIndex,
    selected,
    toggleSelect,
    selectAll,
    clearSelection,
    containerRef,
  };
}
