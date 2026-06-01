

import { toast } from '@/lib/toast';
import { getCopy } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';

export interface ToastContext {
  orderId?: string;
  erpId?: string;
  clientName?: string;
  routeName?: string;
  driverName?: string;
  deliveryId?: string;
  action?: string;
}

interface FormattedToastResult {
  title: string;
  description?: string;
}

/**
 * Extract context from API response data
 */
export function extractContextFromResponse(data: any): ToastContext {
  return {
    orderId: data?.order?.referenceId || data?.referenceId,
    erpId: data?.order?.erpOrderId || data?.erpOrderId,
    clientName: data?.order?.clientName || data?.clientName,
    routeName: data?.routeName,
    driverName: data?.driverName,
    deliveryId: data?.id || data?.deliveryId,
  };
}

/**
 * Map error messages to translation keys
 */
function mapErrorToKey(errorMessage?: string, copy?: any): string | null {
  if (!errorMessage) return null;

  const resolvedCopy = copy || getCopy(useLocaleStore.getState().locale || 'fr');
  const lowerMsg = errorMessage.toLowerCase();

  const mapping: Record<string, string> = {
    // Success messages
    'time windows saved': 'successWindowsSaved',
    'already assigned': 'errorVehicleAlreadyAssigned',

    // Error messages - Stop operations
    'stop not found': 'errorStopNotFound',
    'stop in status': 'errorStopInvalidStatus',
    'cannot cancel an in_transit stop': 'errorStopInTransit',
    'stop cancellation only allowed': 'errorStopCancellationNotAllowed',
    'stop does not belong': 'errorStopDoesNotBelong',

    'route not found': 'errorRouteNotFound',
    'only validated or in_progress routes': 'errorOnlyValidatedRoutes',
    'route validation failed': 'errorRouteValidationFailed',
    'reassignment failed': 'errorRouteReassignmentFailed',

    'delivery not found': 'errorDeliveryNotFound',
    'invalid delivery status': 'errorDeliveryInvalidStatus',

    // Time window mappings (case-insensitive & camelCase checks)
    'time window': 'errorWindowInvalid',
    'timewindow': 'errorWindowInvalid',
    'starttimewindow': 'errorWindowInvalid',
    'endtimewindow': 'errorWindowInvalid',
    'must be before': 'errorWindowInvalid',
    'out of bounds': 'errorWindowOutOfBounds',
    'overlap': 'errorWindowOverlap',

    'missing gps': 'errorMissingGPS',
    'capacity exceeded': 'errorCapacityExceeded',

    'bad request': 'errorBadRequest',
    'unauthorized': 'errorUnauthorized',
    'forbidden': 'errorForbidden',
    'conflict': 'errorConflict',
    'internal server error': 'errorServerError',
  };

  for (const [key, translationKey] of Object.entries(mapping)) {
    if (lowerMsg.includes(key)) {
      return translationKey;
    }
  }

  return null;
}

/**
 * Get translated error message from copy
 */
function getTranslatedErrorMessage(
  errorMessage?: string | null,
  fallbackKey?: string,
  isError: boolean = true,
): string {
  const activeLocale = useLocaleStore.getState().locale || 'fr';
  const copy = getCopy(activeLocale);

  if (!errorMessage) {
    if (fallbackKey && fallbackKey in copy.apiMessages) {
      return (copy.apiMessages as any)[fallbackKey];
    }
    return (fallbackKey as string) || (copy.apiMessages as any).errorUnknownError || 'Une erreur est survenue';
  }

  // 1. Try as direct key lookup first
  if (errorMessage in copy.apiMessages) {
    return (copy.apiMessages as any)[errorMessage];
  }

  // 2. If it is already a translated message in the apiMessages dictionary, return it as-is
  const translations = Object.values(copy.apiMessages) as string[];
  if (translations.includes(errorMessage)) {
    return errorMessage;
  }

  // 3. Try mapping backend error message
  if (isError) {
    const translationKey = mapErrorToKey(errorMessage, copy);
    if (translationKey && (copy.apiMessages as any)?.[translationKey]) {
      return (copy.apiMessages as any)[translationKey];
    }
  }

  // 4. Try fallback key
  if (fallbackKey && fallbackKey in copy.apiMessages) {
    return (copy.apiMessages as any)[fallbackKey];
  }

  return errorMessage || (fallbackKey as string) || (copy.apiMessages as any).errorUnknownError || 'Une erreur est survenue';
}

/**
 * Format toast with rich context and timestamp
 */
function formatToastMessage(
  message: string,
  context?: ToastContext,
): FormattedToastResult {
  const now = new Date();
  const timeStr = now.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });

  const contextParts: string[] = [];

  if (context?.orderId) contextParts.push(`Cmd: ${context.orderId}`);
  if (context?.erpId) contextParts.push(`ERP: ${context.erpId}`);
  if (context?.clientName) contextParts.push(`${context.clientName}`);
  const locale = useLocaleStore.getState().locale;
  const routePrefix = locale === 'ar' ? 'الرحلة' : locale === 'fr' ? 'Tournée' : 'Route';
  if (context?.routeName) contextParts.push(`${routePrefix}: ${context.routeName}`);

  const description =
    contextParts.length > 0 ? `${contextParts.join(' • ')} • ${timeStr}` : timeStr;

  return {
    title: message,
    description,
  };
}

/**
 * Show success toast with formatted context
 */
export function showSuccessToast(
  message: string,
  context?: ToastContext,
): void {
  const translatedMessage = getTranslatedErrorMessage(message, undefined, false);
  const formatted = formatToastMessage(translatedMessage, context);
  toast.success(formatted.title, { description: formatted.description });
}

export function showErrorToast(
  errorMessage?: any,
  fallbackKey?: keyof ReturnType<typeof getCopy>['apiMessages'] | string,
  context?: ToastContext,
): void {
  const msgStr = typeof errorMessage === 'string'
    ? errorMessage
    : (errorMessage && typeof errorMessage === 'object' && 'message' in errorMessage)
      ? String(errorMessage.message)
      : errorMessage
        ? String(errorMessage)
        : null;
  const translatedMessage = getTranslatedErrorMessage(msgStr, fallbackKey, true);
  const formatted = formatToastMessage(translatedMessage, context);
  toast.error(formatted.title, { description: formatted.description });
}

/**
 * Show warning toast
 */
export function showWarningToast(
  message: string,
  context?: ToastContext,
): void {
  const translatedMessage = getTranslatedErrorMessage(message, undefined, false);
  const formatted = formatToastMessage(translatedMessage, context);
  toast.warning(formatted.title, { description: formatted.description });
}

/**
 * Show info toast
 */
export function showInfoToast(
  message: string,
  context?: ToastContext,
): void {
  const translatedMessage = getTranslatedErrorMessage(message, undefined, false);
  const formatted = formatToastMessage(translatedMessage, context);
  toast.info(formatted.title, { description: formatted.description });
}

/**
 * Parse HTTP status code and return appropriate message key
 */
export function getErrorMessageByStatusCode(
  status?: number,
): string {
  switch (status) {
    case 400:
      return 'errorBadRequest';
    case 401:
      return 'errorUnauthorized';
    case 403:
      return 'errorForbidden';
    case 404:
      return 'errorDataLoadFailed';
    case 409:
      return 'errorConflict';
    case 500:
    case 502:
    case 503:
      return 'errorServerError';
    case 0:
      return 'errorNetworkError';
    default:
      return 'errorUnknownError';
  }
}
