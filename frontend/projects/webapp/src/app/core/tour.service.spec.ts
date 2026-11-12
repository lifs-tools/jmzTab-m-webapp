import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { TOUR_ID_PATTERN, TourService } from './tour.service';

@Component({ template: '' })
class ElsewhereComponent {}

const ID = 'teaching-lipidomics-2.0.mztab';

describe('TourService', () => {
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.fn>;
  let tours: TourService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: 'elsewhere', component: ElsewhereComponent }])],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true) as never;
    tours = TestBed.inject(TourService);
  });

  afterEach(() => http.verify());

  const validation = () =>
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith(`rest/v2/examples/${ID}/validations`));

  it('validates the example and opens the result at the step', async () => {
    let done = false;
    tours.start(ID, 3).subscribe(() => (done = true));
    const req = validation();
    expect(req.request.body).toEqual({ level: 'INFO' });
    req.flush({ id: 'r1' });
    await vi.waitFor(() => expect(done).toBe(true));
    expect(navigate).toHaveBeenCalledWith(['/result', 'r1'], {
      queryParams: { tour: ID, step: 3 },
      replaceUrl: false,
    });
    expect(tours.createdByTour('r1')).toBe(true);
    expect(tours.createdByTour('other')).toBe(false);
  });

  it('starts at step 1 and can replace the address', async () => {
    let done = false;
    tours.start(ID, undefined, true).subscribe(() => (done = true));
    validation().flush({ id: 'r2' });
    await vi.waitFor(() => expect(done).toBe(true));
    expect(navigate).toHaveBeenCalledWith(['/result', 'r2'], {
      queryParams: { tour: ID, step: 1 },
      replaceUrl: true,
    });
  });

  it('gives up when the user goes elsewhere before the example has been validated', async () => {
    let completed = false;
    tours.start(ID, 2).subscribe({ complete: () => (completed = true) });
    const req = validation();
    await TestBed.inject(Router).navigateByUrl('/elsewhere');
    expect(req.cancelled).toBe(true);
    expect(completed).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('accepts example ids as the server does, but no ids of dots only', () => {
    for (const id of [ID, 'a', 'study-design-2.1.mztab', '...a', 'x'.repeat(100)]) {
      expect(TOUR_ID_PATTERN.test(id), id).toBe(true);
    }
    for (const id of ['.', '..', '...', '', '../x', 'a/b', 'a b', 'x'.repeat(101)]) {
      expect(TOUR_ID_PATTERN.test(id), id).toBe(false);
    }
  });

  it('passes a failed validation on and does not navigate', () => {
    let failed = false;
    tours.start(ID).subscribe({ error: () => (failed = true) });
    validation().flush({ message: 'engine down' }, { status: 503, statusText: 'Unavailable' });
    expect(failed).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });
});
