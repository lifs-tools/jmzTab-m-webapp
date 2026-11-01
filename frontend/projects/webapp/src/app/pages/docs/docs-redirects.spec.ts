import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { routes } from '../../app.routes';

describe('docs redirects', () => {
  async function open(url: string): Promise<string> {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    return TestBed.inject(Router).url;
  }

  it('redirects /guide#guide-fixing to /docs/fixing#guide-fixing', async () => {
    expect(await open('/guide#guide-fixing')).toBe('/docs/fixing#guide-fixing');
  });

  it('redirects /guide to /docs/fixing', async () => {
    expect(await open('/guide')).toBe('/docs/fixing');
  });

  it('redirects /problems to /docs/problems', async () => {
    expect(await open('/problems')).toBe('/docs/problems');
  });

  it('redirects /problems#unknown-cv-term to /docs/problems#unknown-cv-term', async () => {
    expect(await open('/problems#unknown-cv-term')).toBe('/docs/problems#unknown-cv-term');
  });
});
