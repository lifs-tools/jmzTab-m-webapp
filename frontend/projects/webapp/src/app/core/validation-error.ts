import { HttpErrorResponse } from '@angular/common/http';

/** Inline message for a failed validation request (upload or example). */
export function validationErrorMessage(err: unknown, maxUploadMb?: number): string {
  const generic = 'Something went wrong. Please try again.';
  if (!(err instanceof HttpErrorResponse)) return generic;
  if (err.status === 413) {
    return maxUploadMb ? `The file is larger than ${maxUploadMb} MB.` : 'The file is too large.';
  }
  if ([400, 403, 503].includes(err.status)) {
    const message = typeof err.error === 'object' && err.error ? err.error.message : undefined;
    if (typeof message === 'string' && message) return message;
  }
  return generic;
}
