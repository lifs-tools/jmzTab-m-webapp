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
        engines: [
          { line: '2.0', mzTabVersions: ['2.0.0-M'], jmztabmVersion: '3.0.9', available: true },
          { line: '2.1', mzTabVersions: ['2.1.0-M'], available: false },
        ],
        defaultLine: '2.0',
        retentionDays: 7,
        customMappingsEnabled: true,
      });
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the retirement notice and the app version', async () => {
    const el = await render();
    expect(el.textContent).toContain('mzTab 1.0 validation was retired in version 2.0.0.');
    expect(el.textContent).toContain('2026-10-01');
    expect(el.textContent).toContain('kept for 7 days');
    const version = el.querySelector('[aria-labelledby=about-version]')!;
    const terms = Array.from(version.querySelectorAll('dt')).map((dt) => dt.textContent?.trim());
    expect(terms).toEqual(['Validator', 'Build date']);
    expect(version.textContent).not.toContain('jmzTab-m');
  });

  it('lists each mzTab-M version with its jmzTab-m version and availability', async () => {
    const el = await render();
    const section = el.querySelector('[aria-labelledby=about-versions]')!;
    expect(section.querySelector('h2')?.textContent?.trim()).toBe('mzTab-M versions');
    const rows = Array.from(section.querySelectorAll('tbody tr')).map((tr) =>
      Array.from(tr.querySelectorAll('th, td')).map((c) => c.textContent?.trim()),
    );
    expect(rows).toEqual([
      ['mzTab-M 2.0', 'jmzTab-m 3.0.9', 'Available'],
      ['mzTab-M 2.1', '—', 'Not available right now'],
    ]);
    expect(section.textContent?.replace(/\s+/g, ' ')).toContain(
      'mzTab-M 2.0 is validated by jmzTab-m 1.0.x and mzTab-M 2.1 by jmzTab-m 2.1.x. ' +
        'Once mzTab-M 2.1 is the default, 2.0 receives bug fixes only.',
    );
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
