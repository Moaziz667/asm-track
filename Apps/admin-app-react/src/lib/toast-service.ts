

import { toast } from '@/lib/toast';
import { getCopy } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { getApiError } from '@/lib/errors';
import { tlabel } from '@/lib/i18n-dict';

type Copy = ReturnType<typeof getCopy>;

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
interface ExtractableResponse {
  order?: { referenceId?: string; erpOrderId?: string; clientName?: string };
  referenceId?: string;
  erpOrderId?: string;
  clientName?: string;
  routeName?: string;
  driverName?: string;
  id?: string;
  deliveryId?: string;
}

export function extractContextFromResponse(data?: ExtractableResponse): ToastContext {
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
 * Stable backend errorCode → apiMessages key.
 *
 * The backend (GlobalExceptionHandler / AppException) returns a stable
 * `errorCode` alongside the human message. Preferring the code over fuzzy
 * English-substring matching of the message makes localization deterministic
 * and immune to backend wording changes.
 *
 * Only *specific* codes belong here — codes that are more precise than a
 * caller's contextual `fallbackKey` (e.g. `errorDriverCreateFailed`). Generic
 * codes (BAD_REQUEST, VALIDATION_FAILED, STATE_CONFLICT…) are intentionally
 * omitted so they don't clobber the richer caller fallback; they still resolve
 * via the status-code path.
 */
const ERROR_CODE_KEYS: Record<string, string> = {
  // Generic-but-specific (infra)
  CONCURRENT_UPDATE: 'errorConcurrentUpdate',
  CONNECTION_FAILED: 'errorNetworkError',
  INSPECTION_REQUIRED: 'errorInspectionRequired',
  GPS_REQUIRED: 'errorMissingGPS',
  DELIVERY_NOT_FOUND: 'errorDeliveryNotFound',
  // Route ops
  INSERT_BEFORE_COMPLETED: 'errorInsertBeforeCompleted',
  // Failure reasons
  FAILURE_REASON_EXISTS: 'errorFailureReasonExists',
  FAILURE_REASON_LAST_DELIVERY: 'errorFailureReasonLastDelivery',
  FAILURE_REASON_LAST_ITEM: 'errorFailureReasonLastItem',
  FAILURE_REASON_NOT_FOUND: 'errorFailureReasonNotFound',
  // Returns / RMA
  DELIVERY_NOT_RETURNABLE: 'errorDeliveryNotReturnable',
  RMA_EMPTY: 'errorRmaEmpty',
  RMA_ALREADY_OPEN: 'errorRmaAlreadyOpen',
  RMA_REASON_REQUIRED: 'errorRmaReasonRequired',
  RMA_NOT_RESTOCKED: 'errorRmaNotRestocked',
  RMA_NOT_FAILED: 'errorRmaNotFailed',
  RMA_NOT_FOUND: 'errorRmaNotFound',
  RMA_TERMINAL: 'errorRmaTerminal',
  RMA_INVALID_TRANSITION: 'errorRmaInvalidTransition',
};

/** Fill {placeholder} tokens from the backend errorParams map. */
function interpolate(template: string, params?: Record<string, unknown>): string {
  if (!params || !template.includes('{')) return template;
  return template.replace(/\{(\w+)\}/g, (m, k) =>
    params[k] != null ? String(params[k]) : m,
  );
}

/**
 * Translate param values that name a known enum (e.g. an RMA status returned as
 * "RESTOCKED") into their localized label, so interpolated tokens never leak a
 * raw enum name into a toast. Generic: any value matching a returnStatusLabels
 * key is swapped for the label regardless of the param name.
 */
function localizeParamValues(
  params: Record<string, unknown> | undefined,
  copy: Copy,
): Record<string, unknown> | undefined {
  if (!params) return params;
  const labels = copy.returnStatusLabels;
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(params)) {
    out[k] = typeof v === 'string' ? (tlabel(labels, v) ?? v) : v;
  }
  return out;
}

/**
 * Map error messages to translation keys
 */
function mapErrorToKey(errorMessage?: string): string | null {
  if (!errorMessage) return null;

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
    return (tlabel(copy.apiMessages, fallbackKey) ?? (fallbackKey as string))
      || tlabel(copy.apiMessages, 'errorUnknownError') || 'Une erreur est survenue';
  }

  // 1. Try as direct key lookup first
  const direct = tlabel(copy.apiMessages, errorMessage);
  if (direct) return direct;

  // 2. If it is already a translated message in the apiMessages dictionary, return it as-is
  const translations = Object.values(copy.apiMessages) as string[];
  if (translations.includes(errorMessage)) {
    return errorMessage;
  }

  // 3. Try mapping backend error message
  if (isError) {
    const translationKey = mapErrorToKey(errorMessage);
    const mapped = translationKey ? tlabel(copy.apiMessages, translationKey) : undefined;
    if (mapped) return mapped;
  }

  // 4. Try fallback key
  const fb = tlabel(copy.apiMessages, fallbackKey);
  if (fb) return fb;

  return errorMessage || (fallbackKey as string) || tlabel(copy.apiMessages, 'errorUnknownError') || 'Une erreur est survenue';
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
  errorMessage?: unknown,
  fallbackKey?: keyof ReturnType<typeof getCopy>['apiMessages'] | string,
  context?: ToastContext,
): void {
  // Prefer the backend's stable errorCode contract over fuzzy message matching.
  const apiErr = errorMessage != null && typeof errorMessage === 'object'
    ? getApiError(errorMessage)
    : undefined;
  const codeKey = apiErr?.errorCode ? ERROR_CODE_KEYS[apiErr.errorCode] : undefined;
  if (codeKey) {
    const copy = getCopy(useLocaleStore.getState().locale || 'fr');
    const resolved = tlabel(copy.apiMessages, codeKey);
    if (resolved) {
      const withParams = interpolate(resolved, localizeParamValues(apiErr?.errorParams, copy));
      const formatted = formatToastMessage(withParams, context);
      toast.error(formatted.title, { description: formatted.description });
      return;
    }
  }

  const msgStr = typeof errorMessage === 'string'
    ? errorMessage
    : apiErr?.message ?? null;
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
