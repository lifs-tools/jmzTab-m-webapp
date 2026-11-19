/*
 * Copyright 2026 LIFS Tools Consortium.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { HttpContext, HttpContextToken, HttpErrorResponse, HttpEvent, HttpInterceptorFn } from '@angular/common/http';
import { MonoTypeOperatorFunction, Observable, retry, throwError, timer } from 'rxjs';

import { quietRequest } from './http-error.interceptor';

/**
 * How long a request may take, in milliseconds, before the client gives up on it; null (the default) for no limit.
 * Only the editor's requests set it: an upload, a synchronous validation or a conversion takes as long as it needs.
 */
export const REQUEST_TIMEOUT = new HttpContextToken<number | null>(() => null);

/** The time an editor request is given. */
export const EDITOR_TIMEOUT_MS = 30_000;
/** The time a request that waits for the engine is given (an import preview, still missing). */
export const ENGINE_TIMEOUT_MS = 120_000;
/** The waits before a read that got no answer (status 0) is asked again: twice, after 1 s and then 3 s. */
export const READ_RETRY_DELAYS_MS: readonly number[] = [1_000, 3_000];

const TIMEOUT_STATUS_TEXT = 'Timeout';

/**
 * Gives up on a request that has not ended within its {@link REQUEST_TIMEOUT}: the request is cancelled and fails
 * with an `HttpErrorResponse` of status 0 and statusText `'Timeout'`, as a lost answer does. The time counts from the
 * request on, whatever events (progress) come meanwhile. A request without the token is passed on untouched.
 *
 * It comes after the error interceptor in `withInterceptors`, so that interceptor, and the caller, see the timeout as
 * an `HttpErrorResponse`.
 */
export const requestTimeoutInterceptor: HttpInterceptorFn = (req, next) => {
  const ms = req.context.get(REQUEST_TIMEOUT);
  if (ms == null) return next(req);
  return new Observable<HttpEvent<unknown>>((subscriber) => {
    const timer = setTimeout(
      () => subscriber.error(new HttpErrorResponse({ status: 0, statusText: TIMEOUT_STATUS_TEXT, url: req.urlWithParams })),
      ms,
    );
    const inner = next(req).subscribe({
      next: (event) => subscriber.next(event),
      error: (err: unknown) => subscriber.error(err),
      complete: () => subscriber.complete(),
    });
    // also on the timeout: unsubscribing from the request cancels it
    return () => {
      clearTimeout(timer);
      inner.unsubscribe();
    };
  });
};

/** Whether a failure is a request given up by {@link requestTimeoutInterceptor}. */
export function isTimeout(err: unknown): boolean {
  return err instanceof HttpErrorResponse && err.status === 0 && err.statusText === TIMEOUT_STATUS_TEXT;
}

/** Request context of an editor request: quiet (its caller shows the failure), with a timeout of `timeoutMs`. */
export function editorRequest(timeoutMs: number = EDITOR_TIMEOUT_MS): HttpContext {
  return quietRequest().set(REQUEST_TIMEOUT, timeoutMs);
}

/** Request context of an editor request that waits for the engine: quiet, with {@link ENGINE_TIMEOUT_MS}. */
export function engineRequest(): HttpContext {
  return editorRequest(ENGINE_TIMEOUT_MS);
}

/**
 * Asks a read again when it got no answer (status 0: a timeout or a lost connection), after each of
 * {@link READ_RETRY_DELAYS_MS}; then, or on any other failure at once, the failure is passed on. For reads only: a
 * change is never sent again this way.
 */
export function retryReads<T>(): MonoTypeOperatorFunction<T> {
  return retry({
    count: READ_RETRY_DELAYS_MS.length,
    delay: (err: unknown, attempt: number) =>
      err instanceof HttpErrorResponse && err.status === 0
        ? timer(READ_RETRY_DELAYS_MS[attempt - 1])
        : throwError(() => err),
  });
}
