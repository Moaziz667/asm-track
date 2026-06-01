

import { create } from 'zustand';
import type { ModalConfig, ModalState } from './types';

interface ModalStore extends ModalState {
  open: (config: ModalConfig) => void;
  close: () => void;
  setLoading: (loading: boolean) => void;
  setReason: (reason: string) => void;
}

export const useModalStore = create<ModalStore>((set) => ({
  config: null,
  loading: false,
  reason: '',
  open: (config) => set({ config, loading: false, reason: '' }),
  close: () => set({ config: null, loading: false, reason: '' }),
  setLoading: (loading) => set({ loading }),
  setReason: (reason) => set({ reason }),
}));

/**
 * Hook pour ouvrir/fermer les modales depuis n'importe quel composant.
 *
 * @example
 * const { open } = useModal();
 * open({
 *   type: 'CONFIRM',
 *   title: UX_COPY.confirm.cancelRoute.title,
 *   body: UX_COPY.confirm.cancelRoute.body,
 *   variant: 'danger',
 *   confirmLabel: UX_COPY.confirm.cancelRoute.confirm,
 *   onConfirm: async () => { await cancelRoute(); },
 * });
 */
export function useModal() {
  const { open, close } = useModalStore();
  return { open, close };
}
