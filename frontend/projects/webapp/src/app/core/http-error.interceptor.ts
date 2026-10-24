import { HttpContext, HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, Injector, inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';

/** Set to `true` on requests whose callers show their own inline error. */
export const SUPPRESS_GLOBAL_ERROR = new HttpContextToken<boolean>(() => false);

const MESSAGE = 'Something went wrong. Please try again.';

/** Opens the error snackbar, never more than one at a time. Loads MatSnackBar on first use. */
@Injectable({ providedIn: 'root' })
class ErrorToaster {
  private readonly injector = inject(Injector);
  /** True while a snackbar is open or being loaded. */
  private busy = false;

  show(): void {
    if (this.busy) return;
    this.busy = true;
    import('@angular/material/snack-bar')
      .then(({ MatSnackBar }) => {
        const ref = this.injector.get(MatSnackBar).open(MESSAGE, 'Dismiss', { duration: 6000 });
        ref.afterDismissed().subscribe(() => (this.busy = false));
      })
      .catch(() => (this.busy = false));
  }
}

function isGlobalError(err: unknown): boolean {
  return (
    err instanceof HttpErrorResponse && (err.status === 0 || (err.status >= 500 && err.status !== 503))
  );
}

/**
 * Shows one "Something went wrong" snackbar for GET requests that fail with a network error or
 * a 5xx other than 503. POST/DELETE forms show inline errors; requests can opt out with
 * {@link SUPPRESS_GLOBAL_ERROR}. The error is always rethrown unchanged.
 */
export const httpErrorInterceptor: HttpInterceptorFn = (req, next) => {
  const toaster = inject(ErrorToaster);
  return next(req).pipe(
    catchError((err: unknown) => {
      if (req.method === 'GET' && !req.context.get(SUPPRESS_GLOBAL_ERROR) && isGlobalError(err)) {
        toaster.show();
      }
      return throwError(() => err);
    }),
  );
};

/** Request context for callers that show their own inline error. */
export function quietRequest(): HttpContext {
  return new HttpContext().set(SUPPRESS_GLOBAL_ERROR, true);
}
