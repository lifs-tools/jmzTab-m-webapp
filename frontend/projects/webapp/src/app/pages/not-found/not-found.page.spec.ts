import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { routes } from '../../app.routes';

describe('NotFoundPage', () => {
  it('unknown route renders the not-found page', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/does-not-exist');
    const el = harness.routeNativeElement as HTMLElement;
    expect(el.querySelector('.lifs-eyebrow')?.textContent).toContain('Error 404 · Page not found');
    expect(el.querySelector('h1')?.textContent).toContain("We couldn't find that page");
    const home = el.querySelector<HTMLAnchorElement>('a[href="/"]')!;
    expect(home.textContent).toContain('Validate a file');
    const lifs = el.querySelector<HTMLAnchorElement>('a[href="https://lifs-tools.org/"]')!;
    expect(lifs.textContent).toContain('Browse LIFS tools');
    expect(lifs.target).toBe('_blank');
    expect(lifs.rel).toContain('noopener');
  });
});
