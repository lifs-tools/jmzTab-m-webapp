import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';

import { ValidationRecord } from '../../api/models/validation-record';
import { ResultPage } from './result.page';

const BASE: ValidationRecord = {
  id: 'abc',
  fileName: 'lip.mzTab',
  status: 'FINISHED',
  outcome: 'PASSED',
  level: 'INFO',
  maxErrors: 100,
  mzTabVersion: '2.0',
  expiresAt: '2026-10-30T10:00:00Z',
  validationProfile: { kind: 'CATALOG', id: 'lipidcompass-summary-2.0', version: '3' },
  counts: { ERROR: 0, WARN: 0, INFO: 0 },
  messages: [],
  sections: { metadataLines: 5540, sml: { rows: 846, columns: 1990 }, smf: { rows: 1, columns: 2 }, sme: { rows: 3, columns: 4 } },
};

describe('ResultPage', () => {
  let http: HttpTestingController;

  async function render(query: Record<string, string> = {}) {
    await TestBed.configureTestingModule({
      imports: [ResultPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: of(convertToParamMap({ id: 'abc' })),
            snapshot: { queryParamMap: convertToParamMap(query) },
          },
        },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(ResultPage);
    fixture.detectChanges();
    http
      .expectOne((r) => r.url.endsWith('rest/v2/app-info'))
      .flush({ retentionDays: 7, maxUploadBytes: 1, customMappingsEnabled: true });
    return { fixture, el: fixture.nativeElement as HTMLElement, navigate };
  }

  /** The poll's timer fires on a macrotask, so the GET shows up shortly after render/advance. */
  const nextGet = async () => {
    if (vi.isFakeTimers()) await vi.advanceTimersByTimeAsync(0);
    return vi.waitFor(() =>
      http.expectOne((r) => r.method === 'GET' && r.url.endsWith('rest/v2/validations/abc')),
    );
  };

  const flushRecord = async (fixture: ComponentFixture<ResultPage>, rec: ValidationRecord) => {
    (await nextGet()).flush(rec);
    if (vi.isFakeTimers()) await vi.advanceTimersByTimeAsync(0);
    else await fixture.whenStable();
    fixture.detectChanges();
  };

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('polls until FINISHED and then stops', async () => {
    vi.useFakeTimers();
    const { fixture, el } = await render();
    await flushRecord(fixture, { ...BASE, status: 'QUEUED', outcome: undefined });
    expect(el.querySelector('mat-progress-bar')).toBeTruthy();
    expect(el.textContent).toContain('lip.mzTab');
    await vi.advanceTimersByTimeAsync(2000);
    await flushRecord(fixture, { ...BASE, status: 'RUNNING', outcome: undefined });
    await vi.advanceTimersByTimeAsync(2000);
    await flushRecord(fixture, BASE);
    expect(el.querySelector('mat-progress-bar')).toBeNull();
    expect(el.textContent).toContain('Validation passed');
    await vi.advanceTimersByTimeAsync(10000);
    http.expectNone(() => true);
  });

  it('retries failed polls and gives up after 3 consecutive failures', async () => {
    vi.useFakeTimers();
    const { fixture, el } = await render();
    for (let i = 0; i < 3; i++) {
      (await nextGet()).flush('x', { status: 500, statusText: 'err' });
      await vi.advanceTimersByTimeAsync(2000);
    }
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).toContain('Something went wrong');
    http.expectNone(() => true);
  });

  it.each([
    ['PASSED', 'Validation passed', 'ok'],
    ['PASSED_WITH_NOTES', 'Validation passed with notes', 'warn'],
    ['FAILED', 'Validation failed', 'error'],
    ['NOT_PARSED', 'The file could not be read', 'error'],
  ] as const)('maps outcome %s to its banner title', async (outcome, title, tone) => {
    const { fixture, el } = await render();
    await flushRecord(fixture, { ...BASE, outcome, counts: { ERROR: 3, WARN: 1, INFO: 40 }, failureReason: outcome === 'NOT_PARSED' ? 'Bad header' : undefined });
    const banner = el.querySelector('app-result-banner .lifs-callout')!;
    expect(banner.textContent).toContain(title);
    expect(banner.classList).toContain(`lifs-callout--${tone}`);
    expect(banner.textContent).toContain('3 ERRORS');
    expect(banner.textContent).toContain('1 WARNING');
    if (outcome === 'NOT_PARSED') expect(banner.textContent).toContain('Bad header');
  });

  it('shows header eyebrow, badges and report links', async () => {
    const { fixture, el } = await render();
    await flushRecord(fixture, BASE);
    expect(el.querySelector('.lifs-eyebrow')?.textContent).toContain('kept until 30 Oct 2026');
    expect(el.querySelector('h1')?.textContent).toContain('lip.mzTab');
    const badges = Array.from(el.querySelectorAll('.lifs-badge')).map((b) => b.textContent?.trim());
    expect(badges).toEqual(['mzTab-M 2.0', 'lipidcompass-summary-2.0 · v3', 'level Info · max 100']);
    const trigger = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Download report'))!;
    trigger.click();
    fixture.detectChanges();
    const hrefs = Array.from(document.querySelectorAll<HTMLAnchorElement>('.mat-mdc-menu-panel a')).map((a) => a.href);
    expect(hrefs.map((h) => h.replace(/^.*\/rest\//, 'rest/'))).toEqual([
      'rest/v2/validations/abc/report?format=csv',
      'rest/v2/validations/abc/report?format=json',
    ]);
  });

  it('disables absent table tabs', async () => {
    const { fixture, el } = await render();
    await flushRecord(fixture, { ...BASE, sections: { metadataLines: 10, sml: { rows: 2, columns: 3 }, smf: undefined, sme: undefined } });
    const tabs = Array.from(el.querySelectorAll<HTMLButtonElement>('[role="tab"]'));
    const by = (name: string) => tabs.find((t) => t.textContent?.includes(name))!;
    expect(by('Messages').textContent).toContain('0');
    expect(by('Metadata').disabled).toBe(false);
    expect(by('Summary').textContent).toContain('2 × 3');
    expect(by('Features').disabled).toBe(true);
    expect(by('Features').textContent).toContain('—');
    expect(by('Evidence').disabled).toBe(true);
  });

  it('shows only Messages for NOT_PARSED', async () => {
    const { fixture, el } = await render();
    await flushRecord(fixture, { ...BASE, outcome: 'NOT_PARSED', sections: undefined });
    const tabs = Array.from(el.querySelectorAll('[role="tab"]'));
    expect(tabs.length).toBe(1);
    expect(tabs[0].textContent).toContain('Messages');
  });

  it('switches tabs with the arrow keys', async () => {
    const { fixture, el } = await render();
    await flushRecord(fixture, BASE);
    const first = el.querySelector<HTMLButtonElement>('[role="tab"]')!;
    first.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    fixture.detectChanges();
    const selected = el.querySelector('[role="tab"][aria-selected="true"]')!;
    expect(selected.textContent).toContain('Metadata');
    expect(el.querySelector('[role="tabpanel"]')?.textContent).toContain('Section view coming next.');
  });

  async function deleteViaDialog(fixture: ComponentFixture<ResultPage>, el: HTMLElement) {
    const dialog = await vi.waitFor(() => {
      const d = document.querySelector<HTMLElement>('mat-dialog-container');
      if (!d) throw new Error('dialog not open yet');
      return d;
    });
    expect(dialog.textContent).toContain('Delete this result now?');
    expect(dialog.textContent).toContain('This cannot be undone.');
    return dialog;
  }

  it('delete asks for confirmation, then navigates home', async () => {
    const { fixture, el, navigate } = await render();
    await flushRecord(fixture, BASE);
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Delete now'))!.click();
    const dialog = await deleteViaDialog(fixture, el);
    expect(navigate).not.toHaveBeenCalled();
    Array.from(dialog.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Delete')!.click();
    const req = await vi.waitFor(() => http.expectOne((r) => r.method === 'DELETE' && r.url.endsWith('rest/v2/validations/abc')));
    req.flush(null, { status: 204, statusText: 'No Content' });
    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith(['/']));
  });

  it('confirmDelete=true opens the dialog and drops the parameter', async () => {
    const { fixture, navigate } = await render({ confirmDelete: 'true' });
    await flushRecord(fixture, BASE);
    const dialog = await vi.waitFor(() => {
      const d = document.querySelector<HTMLElement>('mat-dialog-container');
      if (!d) throw new Error('dialog not open yet');
      return d;
    });
    expect(dialog.textContent).toContain('Delete this result now?');
    expect(navigate).toHaveBeenCalledWith([], { queryParams: { confirmDelete: null }, replaceUrl: true });
    Array.from(dialog.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Cancel')!.click();
  });

  it('404 shows the expiry explanation', async () => {
    const { fixture, el } = await render();
    (await nextGet()).flush({ message: 'nope' }, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).toContain('This result does not exist or has expired. Results are kept for 7 days.');
    expect(el.querySelector('a[href="/"]')).toBeTruthy();
  });
});
