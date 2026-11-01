import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { TAKE_THE_TOUR } from '../../core/tour-labels';
import { TourService } from '../../core/tour.service';

import { GettingStartedPage } from './getting-started.page';

const TEMPLATE = 'https://lipidcompass.org/assets/templates/lipidcompass-submission-template.xlsx';
const EXAMPLE = 'https://lipidcompass.org/assets/templates/lipidcompass-submission-template-example.xlsx';

describe('GettingStartedPage', () => {
  let start: ReturnType<typeof vi.fn>;
  let http: HttpTestingController;
  let fixture: ComponentFixture<GettingStartedPage>;

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
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: TourService, useValue: { start: (start = vi.fn(() => of(undefined))) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(GettingStartedPage);
    http = TestBed.inject(HttpTestingController);
    http
      .expectOne((r) => r.url.endsWith('rest/v2/app-info'))
      .flush(info);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  /** Answers the examples list that decides whether the tour is offered. */
  async function listExamples(ids: string[]) {
    http
      .expectOne((r) => r.url.endsWith('rest/v2/examples'))
      .flush(ids.map((id) => ({ id, section: 'tutorial', mzTabVersion: '2.0.0-M' })));
    await fixture.whenStable();
  }

  const tourButton = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === TAKE_THE_TOUR);

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

  it('offers the tour in the third route when its example is offered', async () => {
    const el = await render();
    await listExamples(['teaching-lipidomics-2.0.mztab']);
    const button = tourButton(el)!;
    expect(button.closest('section')?.getAttribute('aria-labelledby')).toBe('route-format');
    button.click();
    expect(start).toHaveBeenCalledWith('teaching-lipidomics-2.0.mztab', 1);
  });

  it('does not offer the tour when its example is not offered', async () => {
    const el = await render();
    await listExamples(['teaching-lipidomics-2.1.mztab']);
    expect(tourButton(el)).toBeUndefined();
    expect(el.querySelector('a[href="/docs/format"]')).toBeTruthy();
  });
});
