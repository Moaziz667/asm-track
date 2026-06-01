
import { useEffect, useCallback } from 'react';

type ShortcutMap = Record<string, (e: KeyboardEvent) => void>;

/**
 * Registers keyboard shortcuts that are disabled when focus is in
 * an input / textarea / select / contenteditable element.
 *
 * Key format: plain key string, e.g. 'r', 'Escape', 'ArrowDown'
 * Modifier combos: 'shift+r', 'meta+k', 'ctrl+a'
 *
 * @example
 * useKeyboardShortcuts({
 *   'r': () => refresh(),
 *   'Escape': () => closePanel(),
 *   'shift+c': () => cancelSelected(),
 * });
 */
export function useKeyboardShortcuts(
  shortcuts: ShortcutMap,
  enabled = true,
) {
  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    if (!enabled) return;

    // Never fire when typing in an interactive element
    const target = e.target as HTMLElement;
    if (
      target instanceof HTMLInputElement ||
      target instanceof HTMLTextAreaElement ||
      target instanceof HTMLSelectElement ||
      target.isContentEditable
    ) return;

    const parts: string[] = [];
    if (e.metaKey || e.ctrlKey) parts.push('meta');
    if (e.shiftKey) parts.push('shift');
    if (e.altKey) parts.push('alt');
    parts.push(e.key);

    const combo = parts.join('+');

    const handler = shortcuts[combo] ?? shortcuts[e.key];
    if (handler) {
      e.preventDefault();
      handler(e);
    }
  }, [shortcuts, enabled]);

  useEffect(() => {
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [handleKeyDown]);
}

/**
 * G-chord navigation: press G then a letter within 500ms.
 * Usage: useGotoShortcuts({ o: () => router('/ops'), r: () => router('/routes') })
 */
export function useGotoShortcuts(
  gotoMap: Record<string, () => void>,
  enabled = true,
) {
  useEffect(() => {
    if (!enabled) return;
    let gPressed = false;
    let timer: ReturnType<typeof setTimeout>;

    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        target instanceof HTMLSelectElement ||
        target.isContentEditable
      ) return;

      if (e.key === 'g' && !e.metaKey && !e.ctrlKey) {
        gPressed = true;
        clearTimeout(timer);
        timer = setTimeout(() => { gPressed = false; }, 500);
        return;
      }

      if (gPressed) {
        gPressed = false;
        clearTimeout(timer);
        const fn = gotoMap[e.key];
        if (fn) {
          e.preventDefault();
          fn();
        }
      }
    };

    window.addEventListener('keydown', handler);
    return () => {
      window.removeEventListener('keydown', handler);
      clearTimeout(timer);
    };
  }, [gotoMap, enabled]);
}
