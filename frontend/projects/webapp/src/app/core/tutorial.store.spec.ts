import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { TutorialStore } from './tutorial.store';

describe('TutorialStore', () => {
  let http: HttpTestingController;
  let store: TutorialStore;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(TutorialStore);
  });

  it('requests each file once', () => {
    const got: (readonly string[])[] = [];
    store.lines('a.mztab').subscribe((l) => got.push(l));
    store.lines('a.mztab').subscribe((l) => got.push(l));
    http.expectOne((r) => r.url.endsWith('rest/v2/examples/a.mztab/content')).flush('MTD\tx\ny\n');
    // a later subscriber gets the cached lines without a request
    store.lines('a.mztab').subscribe((l) => got.push(l));
    http.expectNone((r) => r.url.endsWith('rest/v2/examples/a.mztab/content'));
    expect(got).toEqual([['MTD\tx', 'y'], ['MTD\tx', 'y'], ['MTD\tx', 'y']]);
  });

  it('requests each tour once', () => {
    store.tour('a.mztab').subscribe();
    store.tour('a.mztab').subscribe();
    http.expectOne((r) => r.url.endsWith('rest/v2/examples/a.mztab/tour')).flush({ steps: [] });
  });

  it('tries again after a failed request', () => {
    let failed = 0;
    store.tour('a.mztab').subscribe({ error: () => failed++ });
    http
      .expectOne((r) => r.url.endsWith('/tour'))
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(failed).toBe(1);
    store.tour('a.mztab').subscribe();
    http.expectOne((r) => r.url.endsWith('/tour')).flush({ steps: [] });
  });

  it('offers the tutorial examples that the instance lists', () => {
    const offered = store.offered();
    expect(offered()).toBeUndefined();
    http.expectOne((r) => r.url.endsWith('rest/v2/examples')).flush([
      { id: 'a.mztab', section: 'tutorial', originalUri: 'https://example.org/a' },
      { id: 'other', section: 'reference' },
    ]);
    expect(offered()).toEqual(new Set(['a.mztab']));
    expect(store.examples()()?.get('a.mztab')?.originalUri).toBe('https://example.org/a');
  });

  it('offers nothing when the list cannot be loaded', () => {
    const offered = store.offered();
    http.expectOne((r) => r.url.endsWith('rest/v2/examples')).flush('boom', { status: 500, statusText: 'x' });
    expect(offered()).toEqual(new Set());
  });
});
