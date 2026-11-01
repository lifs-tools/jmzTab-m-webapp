import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { GettingStartedPage } from './getting-started.page';

const TEMPLATE = 'https://lipidcompass.org/assets/templates/lipidcompass-submission-template.xlsx';
const EXAMPLE = 'https://lipidcompass.org/assets/templates/lipidcompass-submission-template-example.xlsx';

describe('GettingStartedPage', () => {
  async function render(info: object = {
        version: '2.0.0',
        buildDate: '2026-10-01',
        engines: [],
        defaultLine: '2.0',
        submissionTemplateUrl: TEMPLATE,
        submissionTemplateExampleUrl: EXAMPLE,
      }) {
    await TestBed.configureTestingModule({
      imports: [GettingStartedPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const fixture = TestBed.createComponent(GettingStartedPage);
    TestBed.inject(HttpTestingController)
      .expectOne((r) => r.url.endsWith('rest/v2/app-info'))
      .flush(info);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('names the three routes', async () => {
    const el = await render();
    const headings = Array.from(el.querySelectorAll('h2')).map((h) => h.textContent?.trim());
    expect(headings).toEqual([
      'My software exported an mzTab-M file',
      'I have a spreadsheet of results',
      'I want to understand the format first',
    ]);
  });

  it('links the template, its example and LipidCompass\'s submission help', async () => {
    const el = await render();
    expect(el.querySelector(`a[href="${TEMPLATE}"]`)).toBeTruthy();
    expect(el.querySelector(`a[href="${EXAMPLE}"]`)).toBeTruthy();
    expect(el.querySelector('a[href="https://lipidcompass.org/guide/data-submission"]')).toBeTruthy();
  });

  it('says where to ask without a template URL', async () => {
    const el = await render({ version: '2.0.0', buildDate: '2026-10-01', engines: [], defaultLine: '2.0' });
    expect(el.querySelector('a[href$=".xlsx"]')).toBeNull();
    expect(el.textContent).toContain('submission template');
    expect(el.querySelector('a[href="https://lifs-tools.org/support.html"]')).toBeTruthy();
  });

  it('links the validator and the chapters of the first and third route', async () => {
    const el = await render();
    const hrefs = Array.from(el.querySelectorAll('a')).map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('/');
    expect(hrefs).toContain('/docs/fixing');
    expect(hrefs).toContain('/docs/format');
  });
});
