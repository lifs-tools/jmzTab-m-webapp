import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ValidatingPage } from './validating.page';

describe('ValidatingPage', () => {
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
      imports: [ValidatingPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const fixture = TestBed.createComponent(ValidatingPage);
    TestBed.inject(HttpTestingController)
      .expectOne((r) => r.url.endsWith('rest/v2/app-info'))
      .flush(info ?? 'no', info ? {} : { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('lists the validation levels', async () => {
    const el = await render();
    const section = el.querySelector('#about-levels')!;
    expect(section.querySelector('h2')?.id).toBe('levels');
    const levels = Array.from(section.querySelectorAll('li .lifs-lvl')).map((l) => l.textContent?.trim());
    expect(levels).toEqual(['INFO', 'WARN', 'ERROR']);
  });

  it('explains profiles and offers custom mappings only when enabled', async () => {
    const el = await render();
    const section = el.querySelector('#about-profiles')!;
    expect(section.querySelector('h2')?.id).toBe('profiles');
    expect(section.textContent).toContain('A validation profile defines');
    expect(section.textContent).toContain('upload your own mapping file');
    expect(section.querySelector('a[href="/examples"]')).toBeTruthy();
  });

  it('leaves out the custom mapping paragraph when it is disabled', async () => {
    TestBed.resetTestingModule();
    const el = await render({ version: '2.0.0', buildDate: '2026-10-01', engines: [], defaultLine: '2.0' });
    expect(el.querySelector('#about-profiles')?.textContent).not.toContain('upload your own mapping file');
  });

  it('names the network need and links the EBI OLS', async () => {
    const el = await render();
    const section = el.querySelector('[aria-labelledby=network]')!;
    expect(section.querySelector('a[href="https://www.ebi.ac.uk/ols4/"]')).toBeTruthy();
    expect(section.textContent).toContain('network access');
  });

  it('has a section on reading the result', async () => {
    const el = await render();
    expect(el.querySelector('#reading-result')?.textContent?.trim()).toBe('Reading the result');
    expect(el.querySelector('[aria-labelledby=reading-result] a[href="/docs/fixing"]')).toBeTruthy();
  });

  it('puts every link in the page body into prose, so it is underlined', async () => {
    const el = await render();
    const links = Array.from(el.querySelectorAll('.page a'));
    expect(links.length).toBeGreaterThan(0);
    expect(links.every((a) => a.closest('.lifs-prose') !== null)).toBe(true);
  });
});
