import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { getApiError } from '@/lib/errors';

/**
 * Enterprise error handling for CRUD forms: map a backend **errorCode** (the stable envelope contract)
 * to a specific form field so the user sees the reason *under the field* (e.g. "email déjà utilisé"),
 * instead of a disconnected toast.
 *
 * Usage — in the submit catch block:
 * ```ts
 * if (!applyFieldError(err, setError, {
 *   DRIVER_EMAIL_EXISTS: { field: 'email', message: t.validation.emailTaken },
 *   DRIVER_PHONE_EXISTS: { field: 'phone', message: t.validation.phoneTaken },
 * })) {
 *   showErrorToast(err, ...); // only when no field matched
 * }
 * ```
 * Returns `true` when a field error was applied (caller should NOT also toast).
 */
export function applyFieldError<TFieldValues extends FieldValues>(
  err: unknown,
  setError: UseFormSetError<TFieldValues>,
  map: Partial<Record<string, { field: Path<TFieldValues>; message: string }>>,
): boolean {
  const { errorCode } = getApiError(err);
  if (!errorCode) return false;
  const target = map[errorCode];
  if (!target) return false;
  setError(target.field, { type: 'server', message: target.message }, { shouldFocus: true });
  return true;
}
