/**
 * Types centralisés pour le gestionnaire de modales.
 * Toutes les modales doivent passer par ce système.
 */

export interface ConfirmModalConfig {
  type: 'CONFIRM';
  title: string;
  body?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: 'danger' | 'primary';
  reasonLabel?: string;
  reasonPlaceholder?: string;
  onConfirm: (reason?: string) => void | Promise<void>;
}

export interface ReassignModalConfig {
  type: 'REASSIGN';
  entityName?: string;
  entityLabel?: string;
  drivers: Array<{ id: string; name?: string; phone?: string; isAvailable?: boolean }>;
  currentDriverId?: string;
  onConfirm: (driverId: string) => void | Promise<void>;
}

export type ModalConfig = ConfirmModalConfig | ReassignModalConfig;

export interface ModalState {
  config: ModalConfig | null;
  loading: boolean;
  reason: string;
}
