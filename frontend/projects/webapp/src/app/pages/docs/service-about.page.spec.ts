import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ServiceAboutPage } from './service-about.page';

describe('ServiceAboutPage', () => {
  async function render(info: object | null = {
        version: '2.0.0',
        buildDate: '2026-10-01',
        engines: [
          { line: '2.0', mzTabVersions: ['2.0.0-M'], jmztabmVersion: '3.0.9', available: true },
          { line: '2.1', mzTabVersions: ['2.1.0-M'], available: false },
        ],
        defaultLine: '2.0',
        retentionDays: 7,
        customMappingsEnabled: true,
      }) {
    await TestBed.configureTestingModule({
      imports: [ServiceAboutPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const fixture = TestBed.createComponent(ServiceAboutPage);
    TestBed.inject(HttpTestingController)
      .expectOne((r) => r.url.endsWith('rest/v2/app-info'))
      .flush(info ?? 'no', info ? {} : { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('names the retention time', async () => {
    const el = await render();
    const section = el.querySelector('#about-retention')!;
    expect(section.querySelector('h2')?.id).toBe('retention');
    expect(section.textContent).toContain('kept for 7 days');
  });

  it('names no retention time when the server gives none', async () => {
    const el = await render({ version: '2.0.0', buildDate: '2026-10-01', engines: [], defaultLine: '2.0' });
    expect(el.querySelector('#about-retention')?.textContent).toContain('kept for a limited time');
  });

  it('links the citation DOI', async () => {
    const el = await render();
    const section = el.querySelector('#about-cite')!;
    expect(section.querySelector('h2')?.id).toBe('cite');
    expect(section.querySelector('a[href="https://doi.org/10.1021/acs.analchem.8b04310"]')).toBeTruthy();
  });

  it('shows the app version and build date', async () => {
    const el = await render();
    const section = el.querySelector('#about-version')!;
    expect(section.querySelector('h2')?.id).toBe('version');
    const terms = Array.from(section.querySelectorAll('dt')).map((dt) => dt.textContent?.trim());
    expect(terms).toEqual(['Validator', 'Build date']);
    expect(section.textContent).toContain('2026-10-01');
    expect(section.textContent).not.toContain('jmzTab-m');
  });

  it('says so when the version is not available', async () => {
    const el = await render(null);
    expect(el.querySelector('#about-version')?.textContent).toContain('not available right now');
  });

  it('never mentions ISAS', async () => {
    const el = await render();
    expect(el.textContent).not.toMatch(/isas/i);
  });

  it('puts every link in the page body into prose, so it is underlined', async () => {
    const el = await render();
    const links = Array.from(el.querySelectorAll('.page a'));
    expect(links.length).toBeGreaterThan(0);
    expect(links.every((a) => a.closest('.lifs-prose') !== null)).toBe(true);
  });
});
