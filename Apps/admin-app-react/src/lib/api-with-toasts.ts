

import { api } from '@/lib/api';
import {
  showErrorToast,
  showSuccessToast,
  extractContextFromResponse,
  getErrorMessageByStatusCode,
} from '@/lib/toast-service';
import { getCopy } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';

interface ToastConfig {
  showSuccess?: boolean;
  showError?: boolean;
  successMessage?: string;
  errorMessage?: string;
  extractContext?: (response: any) => any;
}

const DEFAULT_CONFIG: ToastConfig = {
  showSuccess: true,
  showError: true,
};

/**
 * Wrapper around API calls that automatically handles and displays toasts
 * Usage: apiWithToasts.get(...) instead of api.get(...)
 */
export const apiWithToasts = {
  async get<T = any>(
    url: string,
    config?: any,
    toastConfig?: ToastConfig,
  ): Promise<any> {
    const cfg = { ...DEFAULT_CONFIG, ...toastConfig };
    try {
      const response = await api.get<T>(url, config);
      if (cfg.showSuccess && cfg.successMessage) {
        const context = cfg.extractContext
          ? cfg.extractContext(response.data)
          : extractContextFromResponse(response.data);
        showSuccessToast(cfg.successMessage, context);
      }
      return response;
    } catch (error: any) {
      if (cfg.showError) {
        const copy = getCopy(useLocaleStore.getState().locale || 'fr');
        const context = cfg.extractContext ? cfg.extractContext(error?.response?.data) : undefined;
        const errorMsg =
          cfg.errorMessage ||
          error?.response?.data?.message ||
          (copy.apiMessages as any)?.[
            getErrorMessageByStatusCode(error?.response?.status)
          ];
        showErrorToast(errorMsg, undefined, context);
      }
      throw error;
    }
  },

  async post<T = any>(
    url: string,
    data?: any,
    config?: any,
    toastConfig?: ToastConfig,
  ): Promise<any> {
    const cfg = { ...DEFAULT_CONFIG, ...toastConfig };
    try {
      const response = await api.post<T>(url, data, config);
      if (cfg.showSuccess && cfg.successMessage) {
        const context = cfg.extractContext
          ? cfg.extractContext(response.data)
          : extractContextFromResponse(response.data);
        showSuccessToast(cfg.successMessage, context);
      }
      return response;
    } catch (error: any) {
      if (cfg.showError) {
        const copy = getCopy(useLocaleStore.getState().locale || 'fr');
        const context = cfg.extractContext ? cfg.extractContext(error?.response?.data) : undefined;
        const errorMsg =
          cfg.errorMessage ||
          error?.response?.data?.message ||
          (copy.apiMessages as any)?.[
            getErrorMessageByStatusCode(error?.response?.status)
          ];
        showErrorToast(errorMsg, undefined, context);
      }
      throw error;
    }
  },

  async put<T = any>(
    url: string,
    data?: any,
    config?: any,
    toastConfig?: ToastConfig,
  ): Promise<any> {
    const cfg = { ...DEFAULT_CONFIG, ...toastConfig };
    try {
      const response = await api.put<T>(url, data, config);
      if (cfg.showSuccess && cfg.successMessage) {
        const context = cfg.extractContext
          ? cfg.extractContext(response.data)
          : extractContextFromResponse(response.data);
        showSuccessToast(cfg.successMessage, context);
      }
      return response;
    } catch (error: any) {
      if (cfg.showError) {
        const copy = getCopy(useLocaleStore.getState().locale || 'fr');
        const context = cfg.extractContext ? cfg.extractContext(error?.response?.data) : undefined;
        const errorMsg =
          cfg.errorMessage ||
          error?.response?.data?.message ||
          (copy.apiMessages as any)?.[
            getErrorMessageByStatusCode(error?.response?.status)
          ];
        showErrorToast(errorMsg, undefined, context);
      }
      throw error;
    }
  },

  async patch<T = any>(
    url: string,
    data?: any,
    config?: any,
    toastConfig?: ToastConfig,
  ): Promise<any> {
    const cfg = { ...DEFAULT_CONFIG, ...toastConfig };
    try {
      const response = await api.patch<T>(url, data, config);
      if (cfg.showSuccess && cfg.successMessage) {
        const context = cfg.extractContext
          ? cfg.extractContext(response.data)
          : extractContextFromResponse(response.data);
        showSuccessToast(cfg.successMessage, context);
      }
      return response;
    } catch (error: any) {
      if (cfg.showError) {
        const copy = getCopy(useLocaleStore.getState().locale || 'fr');
        const context = cfg.extractContext ? cfg.extractContext(error?.response?.data) : undefined;
        const errorMsg =
          cfg.errorMessage ||
          error?.response?.data?.message ||
          (copy.apiMessages as any)?.[
            getErrorMessageByStatusCode(error?.response?.status)
          ];
        showErrorToast(errorMsg, undefined, context);
      }
      throw error;
    }
  },

  async delete<T = any>(
    url: string,
    config?: any,
    toastConfig?: ToastConfig,
  ): Promise<any> {
    const cfg = { ...DEFAULT_CONFIG, ...toastConfig };
    try {
      const response = await api.delete<T>(url, config);
      if (cfg.showSuccess && cfg.successMessage) {
        const context = cfg.extractContext
          ? cfg.extractContext(response.data)
          : extractContextFromResponse(response.data);
        showSuccessToast(cfg.successMessage, context);
      }
      return response;
    } catch (error: any) {
      if (cfg.showError) {
        const copy = getCopy(useLocaleStore.getState().locale || 'fr');
        const context = cfg.extractContext ? cfg.extractContext(error?.response?.data) : undefined;
        const errorMsg =
          cfg.errorMessage ||
          error?.response?.data?.message ||
          (copy.apiMessages as any)?.[
            getErrorMessageByStatusCode(error?.response?.status)
          ];
        showErrorToast(errorMsg, undefined, context);
      }
      throw error;
    }
  },
};

export default apiWithToasts;
