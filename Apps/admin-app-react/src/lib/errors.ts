/**
 * Typed boundary for API/exception handling.
 *
 * The backend (GlobalExceptionHandler) returns a stable error envelope:
 * `{ status, message, errorCode, errorParams, timestamp }`. Axios wraps it in
 * `error.response.data`. Catch blocks should use `catch (err)` (err: unknown)
 * and narrow through {@link getApiError} instead of `catch (err: any)` — this
 * keeps the one unsafe cast in a single reviewed place.
 */

export interface ApiError {
  /** HTTP status, when the failure reached the server. */
  status?: number;
  /** Human-readable message from the backend (may be localized upstream). */
  message?: string;
  /** Stable machine code, e.g. VALIDATION_FAILED / CONCURRENT_UPDATE. */
  errorCode?: string;
  /** Structured params for message interpolation. */
  errorParams?: Record<string, unknown>;
}

interface AxiosLikeError {
  response?: { status?: number; data?: unknown };
  message?: string;
}

/** Narrow an unknown thrown value into the backend error envelope. Never throws. */
export function getApiError(err: unknown): ApiError {
  const e = (err ?? {}) as AxiosLikeError;
  const data = (e.response?.data ?? {}) as Record<string, unknown>;
  return {
    status: e.response?.status,
    message: typeof data.message === 'string' ? data.message : e.message,
    errorCode: typeof data.errorCode === 'string' ? data.errorCode : undefined,
    errorParams:
      data.errorParams && typeof data.errorParams === 'object'
        ? (data.errorParams as Record<string, unknown>)
        : undefined,
  };
}

/** Convenience: the backend message (or a fallback) from any thrown value. */
export function getApiErrorMessage(err: unknown, fallback?: string): string | undefined {
  return getApiError(err).message ?? fallback;
}

/** True when a thrown value is an aborted/cancelled request (fetch abort or axios cancel). */
export function isAbortError(err: unknown): boolean {
  const e = err as { name?: string; code?: string } | null;
  return e?.name === 'AbortError' || e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED';
}
