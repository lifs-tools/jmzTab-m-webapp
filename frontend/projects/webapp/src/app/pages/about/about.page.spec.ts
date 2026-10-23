import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AboutPage } from './about.page';

describe('AboutPage', () => {
  async function render() {
    await TestBed.configureTestingModule({
      imports: [AboutPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const fixture = TestBed.createComponent(AboutPage);
    TestBed.inject(HttpTestingController)
      .expectOne((r) => r.url.endsWith('rest/v2/app-info'))
      .flush({
        version: '2.0.0',
        buildDate: '2026-10-01',
        jmztabmVersion: '3.0.9',
        retentionDays: 7,
        customMappingsEnabled: true,
      });
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the retirement notice and the app version', async () => {
    const el = await render();
    expect(el.textContent).toContain('mzTab 1.0 validation was retired in version 2.0.0.');
    expect(el.textContent).toContain('3.0.9');
    expect(el.textContent).toContain('2026-10-01');
    expect(el.textContent).toContain('kept for 7 days');
  });

  it('links mzTab-M and jmzTab-m', async () => {
    const el = await render();
    expect(el.querySelector('a[href="https://github.com/HUPO-PSI/mzTab"]')).toBeTruthy();
    expect(el.querySelector('a[href="https://github.com/lifs-tools/jmzTab-m"]')).toBeTruthy();
  });

  it('puts every link in the page body into prose, so it is underlined', async () => {
    const el = await render();
    const links = Array.from(el.querySelectorAll('.page a'));
    expect(links.length).toBeGreaterThan(0);
    expect(links.every((a) => a.closest('.lifs-prose') !== null)).toBe(true);
  });

  it('never mentions ISAS', async () => {
    const el = await render();
    expect(el.textContent).not.toMatch(/isas/i);
  });
});
