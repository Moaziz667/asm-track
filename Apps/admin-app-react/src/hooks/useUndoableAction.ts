

import { useRef, useCallback } from 'react';
import { toast } from '@/lib/toast';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { useT } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';

interface UndoableOptions<T> {
  /** Message shown in the undo toast (can be a key of apiMessages or a raw string) */
  message: string;
  /** Time in ms the user has to undo (default 5000) */
  delay?: number;
  /** Called to reverse the optimistic update if user hits Undo */
  onUndo?: (target: T) => void;
  /** Called after the undo fires (e.g. re-fetch) */
  onUndone?: (target: T) => void;
}

/**
 * Wraps an async action in an undo-toast pattern.
 *
 * Flow:
 *   1. Call execute(target) → optimistic callback fires immediately
 *   2. Toast appears with "Annuler" button and a countdown
 *   3a. User clicks Annuler → onUndo fires, action is aborted
 *   3b. Countdown expires → action fires for real
 */
export function useUndoableAction<T>(
  action: (target: T) => Promise<void>,
  options: UndoableOptions<T>,
) {
  const { message, delay = 5000, onUndo, onUndone } = options;
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const t = useT();

  const execute = useCallback((target: T) => {
    // Clear any previous pending action
    if (timerRef.current) clearTimeout(timerRef.current);

    let undone = false;

    const translatedMessage = message in t.apiMessages 
      ? (t.apiMessages as any)[message] 
      : message;

    const undoLabel = useLocaleStore.getState().locale === 'ar' 
      ? 'تراجع' 
      : (useLocaleStore.getState().locale === 'en' ? 'Undo' : 'Annuler');

    toast(translatedMessage, {
      duration: delay,
      action: {
        label: undoLabel,
        onClick: () => {
          undone = true;
          if (timerRef.current) clearTimeout(timerRef.current);
          onUndo?.(target);
          onUndone?.(target);
          showSuccessToast('successUndoAction');
        },
      },
    });

    timerRef.current = setTimeout(async () => {
      if (undone) return;
      try {
        await action(target);
      } catch (err: any) {
        showErrorToast(err, 'errorUndoActionFailed');
      }
    }, delay);
  }, [action, message, delay, onUndo, onUndone]);

  return execute;
}

/**
 * Simpler variant: immediately fires the action but shows an undo toast.
 * Use when the backend action is easily reversible (e.g. status toggle).
 */
export function useOptimisticAction<T>(
  optimisticUpdate: (target: T) => void,
  action: (target: T) => Promise<void>,
  rollback: (target: T) => void,
  message: string,
) {
  return useCallback(async (target: T) => {
    optimisticUpdate(target);
    try {
      await action(target);
    } catch (err: any) {
      rollback(target);
      showErrorToast(err, 'errorActionFailed');
    }
  }, [optimisticUpdate, action, rollback]);
}
