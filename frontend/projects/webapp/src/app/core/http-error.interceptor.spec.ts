import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';

import { SUPPRESS_GLOBAL_ERROR, httpErrorInterceptor } from './http-error.interceptor';

describe('httpErrorInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let open: ReturnType<typeof vi.fn>;
  let dismissed: Subject<void>;

  beforeEach(() => {
    dismissed = new Subject<void>();
    open = vi.fn(() => ({ afterDismissed: () => dismissed }));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([httpErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open } },
      ],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
  });

  async function fail(method: string, status: number, context?: HttpContext, url = '/x') {
    let error: unknown;
    http.request(method, url, { context }).subscribe({ error: (e) => (error = e) });
    ctrl.expectOne(url).flush('boom', { status, statusText: 'err' });
    // the snackbar is loaded lazily
    await new Promise((r) => setTimeout(r, 20));
    return error as { status: number };
  }

  it('shows one snackbar for a GET 500 and rethrows the error unchanged', async () => {
    const err = await fail('GET', 500);
    expect(err.status).toBe(500);
    expect(open).toHaveBeenCalledTimes(1);
    expect(open).toHaveBeenCalledWith('Something went wrong. Please try again.', 'Dismiss', {
      duration: 6000,
    });
  });

  it('shows one for a network error (status 0)', async () => {
    await fail('GET', 0);
    expect(open).toHaveBeenCalledTimes(1);
  });

  it('does not stack: no second snackbar while one is open, but again after it closed', async () => {
    await fail('GET', 500, undefined, '/a');
    await fail('GET', 502, undefined, '/b');
    expect(open).toHaveBeenCalledTimes(1);
    dismissed.next();
    await fail('GET', 500, undefined, '/c');
    expect(open).toHaveBeenCalledTimes(2);
  });

  it('ignores GET 503, 4xx, POST 500 and DELETE 500', async () => {
    await fail('GET', 503);
    await fail('GET', 404);
    await fail('POST', 500);
    await fail('DELETE', 500);
    expect(open).not.toHaveBeenCalled();
  });

  it('honours the opt-out token', async () => {
    await fail('GET', 500, new HttpContext().set(SUPPRESS_GLOBAL_ERROR, true));
    expect(open).not.toHaveBeenCalled();
  });
});
