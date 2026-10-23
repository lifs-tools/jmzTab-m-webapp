import { HttpErrorResponse } from '@angular/common/http';

/** The server's limit for a custom mapping file. */
export const MAX_MAPPING_BYTES = 5 * 1048576;
export const MAPPING_TOO_LARGE = 'The mapping file is larger than 5 MB.';

/** The message for an mzTab-M file over the upload limit. */
export function fileTooLargeMessage(maxUploadMb?: number): string {
  return maxUploadMb ? `The file is larger than ${maxUploadMb} MB.` : 'The file is too large.';
}

/** Inline message for a failed validation request (upload or example). */
export function validationErrorMessage(err: unknown, maxUploadMb?: number): string {
  const generic = 'Something went wrong. Please try again.';
  if (!(err instanceof HttpErrorResponse)) return generic;
  if (err.status === 413) {
    return fileTooLargeMessage(maxUploadMb);
  }
  if ([400, 403, 503].includes(err.status)) {
    const message = typeof err.error === 'object' && err.error ? err.error.message : undefined;
    if (typeof message === 'string' && message) return message;
  }
  return generic;
}
