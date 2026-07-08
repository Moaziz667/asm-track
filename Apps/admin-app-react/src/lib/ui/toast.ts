
import { toast as sonner } from 'sonner';

export interface ToastOptions {
  duration?: number;
  description?: string;
  action?: { label: string; onClick: () => void };
}

// ── Toast positioning strategy ──────────────────────────────────────────────
// All toasts → bottom-left (unified, away from notification dropdown)

const positions = {
  realtime: 'bottom-left' as const,
  error: 'bottom-left' as const,
  success: 'bottom-left' as const,
};

const durations = {
  success: 3000,
  error: 5000,
  warning: 5000,
  info: 3000,
};

interface ToastFn {
  (message: string, opts?: ToastOptions): void;
  success: (message: string, opts?: ToastOptions) => void;
  error: (message: string, opts?: ToastOptions) => void;
  warning: (message: string, opts?: ToastOptions) => void;
  info: (message: string, opts?: ToastOptions) => void;
  loading: (message: string) => string | number;
  dismiss: (toastId: string | number) => void;
}

const toastImpl = (message: string, opts?: ToastOptions) => {
  sonner(message, {
    duration: opts?.duration,
    description: opts?.description,
    action: opts?.action,
  });
};

toastImpl.success = (message: string, opts?: ToastOptions) => {
  sonner.success(message, {
    duration: opts?.duration ?? durations.success,
    position: positions.success,
    description: opts?.description,
    action: opts?.action,
  });
};

toastImpl.error = (message: string, opts?: ToastOptions) => {
  sonner.error(message, {
    duration: opts?.duration ?? durations.error,
    position: positions.error,
    description: opts?.description,
    action: opts?.action,
  });
};

toastImpl.warning = (message: string, opts?: ToastOptions) => {
  sonner.warning(message, {
    duration: opts?.duration ?? durations.warning,
    position: positions.realtime,
    description: opts?.description,
    action: opts?.action,
  });
};

toastImpl.info = (message: string, opts?: ToastOptions) => {
  sonner.info(message, {
    duration: opts?.duration ?? durations.info,
    position: positions.realtime,
    description: opts?.description,
    action: opts?.action,
  });
};

toastImpl.loading = (message: string) => {
  return sonner.loading(message, {
    position: positions.realtime,
    duration: Infinity,
  });
};

toastImpl.dismiss = (toastId: string | number) => {
  sonner.dismiss(toastId);
};

export const toast: ToastFn = toastImpl;
