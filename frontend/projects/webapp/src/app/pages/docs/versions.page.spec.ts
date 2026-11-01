import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { VersionsPage } from './versions.page';

describe('VersionsPage', () => {
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
      imports: [VersionsPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const fixture = TestBed.createComponent(VersionsPage);
    TestBed.inject(HttpTestingController)
      .expectOne((r) => r.url.endsWith('rest/v2/app-info'))
      .flush(info ?? 'no', info ? {} : { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('lists each mzTab-M version with its jmzTab-m version and availability', async () => {
    const el = await render();
    const section = el.querySelector('#about-versions')!;
    expect(section.querySelector('h2')?.id).toBe('versions-engines');
    expect(section.querySelector('h2')?.textContent?.trim()).toBe('Versions and engines');
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

  it('says so when no engine information is available', async () => {
    const el = await render(null);
    expect(el.querySelector('table')).toBeNull();
    expect(el.textContent).toContain('Version information is not available right now.');
  });
});
