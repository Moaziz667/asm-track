import { create } from 'zustand';

/**
 * Open/close state for the global assistant panel. Ephemeral (not persisted): the panel is a
 * transient tool you summon from the top bar on any page, not a view whose state should survive a
 * reload. The top-bar button toggles it; the panel reads it.
 */
type AssistantPanelState = {
  open: boolean;
  setOpen: (open: boolean) => void;
  toggle: () => void;
};

export const useAssistantPanel = create<AssistantPanelState>((set) => ({
  open: false,
  setOpen: (open) => set({ open }),
  toggle: () => set((s) => ({ open: !s.open })),
}));
