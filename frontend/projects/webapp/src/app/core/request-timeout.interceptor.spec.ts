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
import { HttpClient, HttpContext, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, defer, throwError } from 'rxjs';

import { SUPPRESS_GLOBAL_ERROR, httpErrorInterceptor } from './http-error.interceptor';
import {
  EDITOR_TIMEOUT_MS,
  ENGINE_TIMEOUT_MS,
  READ_RETRY_DELAYS_MS,
  REQUEST_TIMEOUT,
  editorRequest,
  engineRequest,
  isTimeout,
  requestTimeoutInterceptor,
  retryReads,
} from './request-timeout.interceptor';

describe('requestTimeoutInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let open: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    open = vi.fn(() => ({ afterDismissed: () => new Observable<void>() }));
    TestBed.configureTestingModule({
      providers: [
        // the order of app.config.ts: the error interceptor sees the timeout's error
        provideHttpClient(withInterceptors([httpErrorInterceptor, requestTimeoutInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open } },
      ],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
  });

  afterEach(() => vi.useRealTimers());

  function get(context?: HttpContext) {
    const seen: { value?: unknown; error?: unknown } = {};
    http.get('/x', { context }).subscribe({ next: (v) => (seen.value = v), error: (e) => (seen.error = e) });
    return seen;
  }

  it('times out at the token\'s value, with status 0 and statusText Timeout', () => {
    const seen = get(new HttpContext().set(REQUEST_TIMEOUT, 5_000));
    const req = ctrl.expectOne('/x');
    vi.advanceTimersByTime(4_999);
    expect(seen.error).toBeUndefined();
    vi.advanceTimersByTime(1);
    expect(seen.error).toBeInstanceOf(HttpErrorResponse);
    expect((seen.error as HttpErrorResponse).status).toBe(0);
    expect((seen.error as HttpErrorResponse).statusText).toBe('Timeout');
    expect(isTimeout(seen.error)).toBe(true);
    // the request is given up: the browser cancels it
    expect(req.cancelled).toBe(true);
  });

  it('counts the whole request, not the time between its events', () => {
    const seen = get(new HttpContext().set(REQUEST_TIMEOUT, 1_000));
    ctrl.expectOne('/x').event({ type: 3, loaded: 1 } as never);
    vi.advanceTimersByTime(1_000);
    expect(isTimeout(seen.error)).toBe(true);
  });

  it('an answer in time is passed on, and no timeout fires afterwards', () => {
    const seen = get(new HttpContext().set(REQUEST_TIMEOUT, 5_000));
    vi.advanceTimersByTime(4_000);
    ctrl.expectOne('/x').flush({ ok: true });
    vi.advanceTimersByTime(10_000);
    expect(seen.value).toEqual({ ok: true });
    expect(seen.error).toBeUndefined();
  });

  it('a server error before the timeout is passed on unchanged', () => {
    const seen = get(new HttpContext().set(REQUEST_TIMEOUT, 5_000));
    ctrl.expectOne('/x').flush('busy', { status: 503, statusText: 'Service Unavailable' });
    expect((seen.error as HttpErrorResponse).status).toBe(503);
    expect(isTimeout(seen.error)).toBe(false);
  });

  it('leaves a request without the token alone: it waits as long as the server takes', () => {
    const seen = get();
    const req = ctrl.expectOne('/x');
    expect(req.request.context.get(REQUEST_TIMEOUT)).toBeNull();
    vi.advanceTimersByTime(60 * 60_000);
    expect(seen.error).toBeUndefined();
    expect(req.cancelled).toBe(false);
    req.flush('late');
    expect(seen.value).toBe('late');
  });

  it('the error interceptor sees the timeout: a GET that is not quiet shows the snackbar', async () => {
    get(new HttpContext().set(REQUEST_TIMEOUT, 1_000));
    vi.advanceTimersByTime(1_000);
    await vi.waitFor(() => expect(open).toHaveBeenCalledTimes(1));
  });

  it('editorRequest is quiet with 30 s, or the time given; engineRequest is quiet with 120 s', () => {
    expect(EDITOR_TIMEOUT_MS).toBe(30_000);
    expect(ENGINE_TIMEOUT_MS).toBe(120_000);
    expect(editorRequest().get(REQUEST_TIMEOUT)).toBe(30_000);
    expect(editorRequest().get(SUPPRESS_GLOBAL_ERROR)).toBe(true);
    expect(editorRequest(7_000).get(REQUEST_TIMEOUT)).toBe(7_000);
    expect(engineRequest().get(REQUEST_TIMEOUT)).toBe(120_000);
    expect(engineRequest().get(SUPPRESS_GLOBAL_ERROR)).toBe(true);
    // a fresh context each time: a caller's change does not leak into the next request
    expect(editorRequest()).not.toBe(editorRequest());
  });
});

describe('retryReads', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  const lost = () => new HttpErrorResponse({ status: 0, statusText: 'Timeout' });

  function run(errors: unknown[]) {
    let calls = 0;
    const seen: { value?: unknown; error?: unknown } = {};
    defer(() => {
      const err = errors[calls++];
      return err === undefined ? ['ok'] : throwError(() => err);
    })
      .pipe(retryReads())
      .subscribe({ next: (v) => (seen.value = v), error: (e) => (seen.error = e) });
    return { seen, calls: () => calls };
  }

  it('waits 1 s, then 3 s, then gives up with the last failure', () => {
    expect(READ_RETRY_DELAYS_MS).toEqual([1_000, 3_000]);
    const last = lost();
    const r = run([lost(), lost(), last]);
    expect(r.calls()).toBe(1);
    vi.advanceTimersByTime(999);
    expect(r.calls()).toBe(1);
    vi.advanceTimersByTime(1);
    expect(r.calls()).toBe(2);
    vi.advanceTimersByTime(2_999);
    expect(r.calls()).toBe(2);
    vi.advanceTimersByTime(1);
    expect(r.calls()).toBe(3);
    expect(r.seen.error).toBe(last);
    vi.advanceTimersByTime(60_000);
    expect(r.calls()).toBe(3);
  });

  it('a retry that succeeds is the answer', () => {
    const r = run([lost()]);
    vi.advanceTimersByTime(1_000);
    expect(r.seen.value).toBe('ok');
    expect(r.seen.error).toBeUndefined();
  });

  it('retries status 0 only: a 503, a 409 or an error that is not HTTP fails at once', () => {
    for (const err of [
      new HttpErrorResponse({ status: 503 }),
      new HttpErrorResponse({ status: 409 }),
      new HttpErrorResponse({ status: 500 }),
      new Error('x'),
    ]) {
      const r = run([err]);
      vi.advanceTimersByTime(10_000);
      expect(r.calls()).toBe(1);
      expect(r.seen.error).toBe(err);
    }
  });
});
