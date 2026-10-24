import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BreakpointObserver } from '@angular/cdk/layout';
import { ApplicationRef } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject, map } from 'rxjs';

import { ValidationMessage } from '../../api/models/validation-message';
import { ValidationRecord } from '../../api/models/validation-record';
import { MetadataTabComponent } from './metadata-tab.component';
import { ResultPage } from './result.page';
import { TableTabComponent } from './table-tab.component';

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

/** An SML cell error at a 0-based row; three of them form one group with three targets. */
const cellError = (row: number): ValidationMessage => ({
  category: 'format',
  code: `[Error-2010] line ${20 + row}: not a number\r\n`,
  message: `"abundance_assay[3]" in SML row ${row + 1} is not a number`,
  message_type: 'error',
  line_number: 20 + row,
  suggestion: 'Enter a number or "null".',
  location: { section: 'sml', row_index: row, column: 'abundance_assay[3]' },
});
const termWarning: ValidationMessage = {
  category: 'cross_check',
  code: '[Warn-1003] Term MS:1000040 is not allowed\r\n',
  message: 'Term MS:1000040 is not allowed on assay[3]-separation',
  message_type: 'warn',
  rule_id: 'assay_separation_rule',
  suggestion: 'Replace the separation term with a chromatography term.',
  location: { section: 'mtd', metadata_key: 'assay[3]-separation', metadata_group: 'assay' },
};
const LINKED: ValidationRecord = {
  ...BASE,
  outcome: 'FAILED',
  counts: { ERROR: 3, WARN: 1, INFO: 0 },
  messages: [cellError(1), cellError(3), cellError(60), termWarning],
};
const NOT_PARSED: ValidationRecord = {
  ...BASE,
  outcome: 'NOT_PARSED',
  sections: undefined,
  failureReason: 'Bad header',
  messages: [{ ...cellError(1), location: { section: 'sml', row_index: 1 } }],
};

describe('ResultPage', () => {
  let http: HttpTestingController;
  /** What the BreakpointObserver stub reports for '(min-width: 1100px)'; tests may change it at any time. */
  let screen: BehaviorSubject<boolean>;

  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

  async function render(query: Record<string, string> = {}) {
    params = new BehaviorSubject(convertToParamMap({ id: 'abc' }));
    await TestBed.configureTestingModule({
      imports: [ResultPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: BreakpointObserver,
          useValue: { observe: () => screen.pipe(map((matches) => ({ matches, breakpoints: {} }))) },
        },
        // Dialogs close only after their exit animation; without animations the
        // close (and the DELETE it triggers) does not depend on runner speed.
        { provide: MATERIAL_ANIMATIONS, useValue: { animationsDisabled: true } },
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: params,
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

  beforeEach(() => (screen = new BehaviorSubject(false)));

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
    // Overlays live outside the fixture; don't let one test's dialog leak into the next.
    document.querySelectorAll('.cdk-overlay-container').forEach((c) => c.remove());
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
    expect(el.querySelector('[role="tabpanel"] app-metadata-tab')).toBeTruthy();
    http.expectOne((r) => r.url.endsWith('/validations/abc/metadata')).flush([]);
  });

  /**
   * The dialog's DOM exists before its first change detection, but `[mat-dialog-close]="true"`
   * is only bound by that pass; a click in between closes the dialog with `undefined`.
   * Run change detection before handing the dialog to a test.
   */
  async function renderedDialog(): Promise<HTMLElement> {
    const dialog = await vi.waitFor(() => {
      const d = document.querySelector<HTMLElement>('mat-dialog-container');
      if (!d) throw new Error('dialog not open yet');
      return d;
    });
    TestBed.inject(ApplicationRef).tick();
    return dialog;
  }

  async function deleteViaDialog(fixture: ComponentFixture<ResultPage>, el: HTMLElement) {
    const dialog = await renderedDialog();
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
    const req = await vi.waitFor(
      () => http.expectOne((r) => r.method === 'DELETE' && r.url.endsWith('rest/v2/validations/abc')),
      { timeout: 5000 },
    );
    req.flush(null, { status: 204, statusText: 'No Content' });
    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith(['/']));
  });

  it('confirmDelete=true opens the dialog and drops the parameter', async () => {
    const { fixture, navigate } = await render({ confirmDelete: 'true' });
    await flushRecord(fixture, BASE);
    const dialog = await renderedDialog();
    expect(dialog.textContent).toContain('Delete this result now?');
    expect(navigate).toHaveBeenCalledWith([], { relativeTo: expect.anything(), queryParams: { confirmDelete: null }, replaceUrl: true });
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

  const dialogEl = () => document.querySelector<HTMLElement>('mat-dialog-container');
  const openDialog = renderedDialog;
  const button = (root: ParentNode, text: string) =>
    Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === text)!;

  describe('delete edge cases', () => {
    it('Cancel sends no DELETE', async () => {
      const { fixture, el, navigate } = await render();
      await flushRecord(fixture, BASE);
      button(el, 'Delete now').click();
      button(await openDialog(), 'Cancel').click();
      await vi.waitFor(() => expect(dialogEl()).toBeNull());
      http.expectNone((r) => r.method === 'DELETE');
      expect(navigate).not.toHaveBeenCalled();
    });

    it('DELETE 404 counts as success', async () => {
      const { fixture, el, navigate } = await render();
      await flushRecord(fixture, BASE);
      button(el, 'Delete now').click();
      button(await openDialog(), 'Delete').click();
      (await vi.waitFor(() => http.expectOne((r) => r.method === 'DELETE'))).flush(null, { status: 404, statusText: 'Not Found' });
      await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith(['/']));
    });

    it('DELETE 500 shows the error and stays', async () => {
      const { fixture, el, navigate } = await render();
      await flushRecord(fixture, BASE);
      button(el, 'Delete now').click();
      button(await openDialog(), 'Delete').click();
      (await vi.waitFor(() => http.expectOne((r) => r.method === 'DELETE'))).flush(null, { status: 500, statusText: 'err' });
      await fixture.whenStable();
      fixture.detectChanges();
      expect(el.textContent).toContain('The result could not be deleted. Please try again.');
      expect(navigate).not.toHaveBeenCalled();
      expect(button(el, 'Delete now').disabled).toBe(false);
    });

    it('a double click opens one dialog and sends one DELETE', async () => {
      const { fixture, el } = await render();
      await flushRecord(fixture, BASE);
      const del = button(el, 'Delete now');
      del.click();
      del.click();
      await openDialog();
      await new Promise((r) => setTimeout(r, 20));
      expect(document.querySelectorAll('mat-dialog-container').length).toBe(1);
      button(dialogEl()!, 'Delete').click();
      const req = await vi.waitFor(() => http.expectOne((r) => r.method === 'DELETE'));
      fixture.detectChanges();
      expect(button(el, 'Delete now').disabled).toBe(true);
      req.flush(null, { status: 204, statusText: 'No Content' });
    });
  });

  describe('polling edge cases', () => {
    it('resets the failure counter after a success', async () => {
      vi.useFakeTimers();
      const { fixture, el } = await render();
      const fail = async () => {
        (await nextGet()).flush('x', { status: 500, statusText: 'err' });
        await vi.advanceTimersByTimeAsync(2000);
      };
      await fail();
      await fail();
      (await nextGet()).flush({ ...BASE, status: 'RUNNING', outcome: undefined });
      await vi.advanceTimersByTimeAsync(2000);
      await fail();
      await fail();
      // Two failures since the success: still polling, no error state.
      fixture.detectChanges();
      expect(el.textContent).not.toContain('Something went wrong');
      (await nextGet()).flush(BASE);
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();
      expect(el.textContent).toContain('Validation passed');
    });

    it('a route param change cancels the old poll and starts the new one', async () => {
      vi.useFakeTimers();
      const { fixture, el } = await render();
      (await nextGet()).flush({ ...BASE, status: 'RUNNING', outcome: undefined });
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();
      expect(el.querySelector('mat-progress-bar')).toBeTruthy();
      params.next(convertToParamMap({ id: 'xyz' }));
      fixture.detectChanges();
      expect(el.querySelector('mat-progress-bar')).toBeNull();
      await vi.advanceTimersByTimeAsync(0);
      const req = http.expectOne((r) => r.url.endsWith('rest/v2/validations/xyz'));
      req.flush({ ...BASE, id: 'xyz', fileName: 'other.mzTab' });
      await vi.advanceTimersByTimeAsync(10000);
      http.expectNone(() => true);
      fixture.detectChanges();
      expect(el.textContent).toContain('other.mzTab');
    });

    it('destroying the component stops polling', async () => {
      vi.useFakeTimers();
      const { fixture } = await render();
      (await nextGet()).flush({ ...BASE, status: 'RUNNING', outcome: undefined });
      await vi.advanceTimersByTimeAsync(0);
      fixture.destroy();
      await vi.advanceTimersByTimeAsync(10000);
      http.expectNone(() => true);
    });
  });

  describe('linked messages', () => {
    let fixture: ComponentFixture<ResultPage>;
    let root: HTMLElement;

    const wide = (on: boolean) => screen.next(on);
    const el = (sel: string) => root.querySelector<HTMLElement>(sel);
    const els = (sel: string) => Array.from(root.querySelectorAll<HTMLElement>(sel));
    const label = (tab: Element) => tab.querySelector('.tab-label')?.textContent?.trim() ?? '';
    const roleNames = (role: string) => els(`[role="${role}"]`).map(label);
    const selectedTab = () => label(el('[role="tab"][aria-selected="true"]')!);
    const tableHighlight = () =>
      fixture.debugElement.query(By.directive(TableTabComponent))?.componentInstance.highlight();
    const metadataHighlight = () =>
      fixture.debugElement.query(By.directive(MetadataTabComponent))?.componentInstance.highlight();

    /** A macrotask and a change detection pass; `whenStable` would wait for the open requests themselves. */
    const settle = async () => {
      await new Promise((r) => setTimeout(r, 0));
      fixture.detectChanges();
    };
    const docRequests = () => http.match((r) => /\/validations\/abc\/(metadata|tables)/.test(r.url));

    /** Answers every metadata and table request the document pane makes, until it stops asking. */
    async function serve() {
      for (let round = 0; round < 10; round++) {
        await settle();
        let reqs = docRequests();
        if (!reqs.length) {
          await settle();
          reqs = docRequests();
          if (!reqs.length) return;
        }
        for (const r of reqs) {
          const url = r.request.url;
          if (url.includes('/tables/')) {
            const page = Number(r.request.params.get('page') ?? 0);
            r.flush({
              section: 'SML',
              page,
              size: 50,
              totalRows: 100,
              allColumns: ['SML_ID', 'abundance_assay[3]'],
              columns: ['SML_ID', 'abundance_assay[3]'],
              rows: [1, 3, 60]
                .filter((i) => Math.floor(i / 50) === page)
                .map((i) => ({ index: i, cells: [String(i + 1), 'x'] })),
            });
          } else if (url.endsWith('/metadata')) {
            r.flush([{ name: 'assay', count: 2 }]);
          } else {
            r.flush({
              group: 'assay',
              page: 0,
              size: 50,
              total: 2,
              entries: [
                { key: 'assay[3]-name', value: 'a3', lineNumber: 7 },
                { key: 'assay[3]-separation', value: '[MS, MS:1000040, m/z, ]', lineNumber: 8 },
              ],
            });
          }
        }
      }
      throw new Error('the document pane keeps requesting');
    }

    async function renderFinished(rec: ValidationRecord) {
      const r = await render();
      fixture = r.fixture;
      root = r.el;
      (await nextGet()).flush(rec);
      await serve();
    }

    async function click(sel: string) {
      el(sel)!.click();
      await serve();
    }

    it('shows messages and the document side by side on wide screens', async () => {
      wide(true);
      await renderFinished(LINKED);
      expect(el('.split .messages app-messages-tab')).toBeTruthy();
      expect(roleNames('tab')).toEqual(['Metadata', 'Summary', 'Features', 'Evidence']);
      expect(selectedTab()).toBe('Metadata');
    });

    it('a chip click on a wide screen changes only the right pane', async () => {
      wide(true);
      await renderFinished(LINKED);
      await click('.chip'); // SML row 2 (row_index 1)
      expect(selectedTab()).toBe('Summary');
      expect(el('.split .messages app-messages-tab')).toBeTruthy();
      expect(el('.grp[aria-current="true"]')!.textContent).toContain('Enter a number');
      expect(el('[aria-live] .occ')!.textContent).toContain('Showing occurrence 1 of 3: SML row 2 · abundance_assay[3]');
      expect(tableHighlight()).toEqual({ rows: [1], focusRow: 1, column: 'abundance_assay[3]', header: false, moveFocus: true });
      expect(el('.back-bar')).toBeNull();
      expect(el('.split .stepper')!.textContent).toContain('1 of 3');
    });

    it('opens the metadata entry a semantic message names, with the profile on its card', async () => {
      wide(true);
      await renderFinished(LINKED);
      const card = els('.grp').find((g) => g.textContent?.includes('Replace the separation term'))!;
      expect(card.textContent).toContain('lipidcompass-summary-2.0 v3');
      card.querySelector<HTMLElement>('.chip')!.click();
      fixture.detectChanges();
      await serve();
      expect(selectedTab()).toBe('Metadata');
      expect(metadataHighlight()).toEqual({
        group: 'assay',
        keys: ['assay[3]-separation'],
        focusKey: 'assay[3]-separation',
        moveFocus: true,
      });
      expect(el('.entry--hl')!.textContent).toContain('assay[3]-separation');
    });

    it('falls back to jumping on narrow screens and offers the way back', async () => {
      wide(false);
      await renderFinished(LINKED);
      expect(el('.split')).toBeNull();
      await click('.chip');
      expect(selectedTab()).toBe('Summary');
      expect(el('.back-bar')!.textContent).toContain('Back to message');
      expect(el('.back-bar')!.textContent).toContain('Enter a number');
      expect(el('.back-bar')!.textContent).toContain('1 of 3');
      expect(el('[aria-live] .occ')!.textContent).toContain('Showing occurrence 1 of 3');
      await click('.back-bar .back');
      expect(selectedTab()).toMatch(/^Messages/);
      expect(el('.back-bar')).toBeNull();
      await vi.waitFor(() => expect(document.activeElement).toBe(el('.grp[aria-current="true"] button')));
    });

    /** Sets a filter control of the messages tab, as typing or choosing would. */
    function setFilter(sel: string, value: string, event = 'input') {
      const input = el(`app-messages-tab ${sel}`) as HTMLInputElement | HTMLSelectElement;
      input.value = value;
      input.dispatchEvent(new Event(event));
      fixture.detectChanges();
    }
    const focusedGroupButton = () => el('.grp[aria-current="true"] button');
    const filler = (i: number): ValidationMessage => ({
      category: 'format',
      code: `[Error-${3000 + i}] line 1: filler\r\n`,
      message: `Filler ${i}`,
      message_type: 'error',
      line_number: 1,
    });

    it('"Back to message" returns to the group on its messages page', async () => {
      await renderFinished({ ...LINKED, messages: [...Array.from({ length: 25 }, (_, i) => filler(i)), ...LINKED.messages!] });
      await click('app-messages-tab .pager button[aria-label="Next page"]');
      await click('.chip');
      expect(selectedTab()).toBe('Summary');
      await click('.back-bar .back');
      expect(el('app-messages-tab .pager')!.textContent).toContain('26–27 of 27');
      await vi.waitFor(() => expect(focusedGroupButton()).not.toBeNull());
      await vi.waitFor(() => expect(document.activeElement).toBe(focusedGroupButton()));
    });

    it('keeps filters and expanded chips across a jump and back', async () => {
      const many = Array.from({ length: 12 }, (_, r) => cellError(r));
      await renderFinished({ ...LINKED, messages: [...many, termWarning] });
      setFilter('.f-text', 'number');
      setFilter('.f-level', 'ERROR', 'change');
      setFilter('.f-category', 'format', 'change');
      await click('app-messages-tab .more');
      expect(els('.chip').length).toBe(12);
      await click('.chip');
      expect(selectedTab()).toBe('Summary');
      await click('.back-bar .back');
      expect((el('app-messages-tab .f-text') as HTMLInputElement).value).toBe('number');
      expect((el('app-messages-tab .f-level') as HTMLSelectElement).value).toBe('ERROR');
      expect((el('app-messages-tab .f-category') as HTMLSelectElement).value).toBe('format');
      expect(els('.grp').length).toBe(1);
      expect(els('.chip').length).toBe(12);
      await vi.waitFor(() => expect(document.activeElement).toBe(focusedGroupButton()));
    });

    it('a layout switch keeps the messages view state', async () => {
      await renderFinished(LINKED);
      setFilter('.f-text', 'number');
      await click('app-messages-tab .view-toggle');
      expect(el('app-messages-tab table')).toBeTruthy();
      wide(true);
      await serve();
      expect(el('.split .messages app-messages-tab')).toBeTruthy();
      expect((el('app-messages-tab .f-text') as HTMLInputElement).value).toBe('number');
      expect(el('app-messages-tab table')).toBeTruthy();
      expect(els('app-messages-tab tbody tr').length).toBe(3);
    });

    it('"Back to message" clears a text filter that hides the group', async () => {
      wide(true);
      await renderFinished(LINKED);
      await click('.chip');
      setFilter('.f-text', 'zzz');
      expect(els('.grp').length).toBe(0);
      wide(false);
      await serve();
      await click('#tab-summary');
      await click('.back-bar .back');
      expect((el('app-messages-tab .f-text') as HTMLInputElement).value).toBe('');
      await vi.waitFor(() => expect(focusedGroupButton()).not.toBeNull());
      await vi.waitFor(() => expect(document.activeElement).toBe(focusedGroupButton()));
    });

    it('steps through occurrences', async () => {
      wide(true);
      await renderFinished(LINKED);
      await click('.chip');
      const prev = () => el('.stepper button[aria-label="Previous occurrence"]') as HTMLButtonElement;
      const next = () => el('.stepper button[aria-label="Next occurrence"]') as HTMLButtonElement;
      expect(prev().disabled).toBe(true);
      const first = tableHighlight();
      await click('.stepper button[aria-label="Next occurrence"]');
      await click('.stepper button[aria-label="Next occurrence"]');
      expect(el('[aria-live] .occ')!.textContent).toContain('Showing occurrence 3 of 3');
      expect(next().disabled).toBe(true);
      expect(prev().disabled).toBe(false);
      expect(tableHighlight()).toEqual({ rows: [60], focusRow: 60, column: 'abundance_assay[3]', header: false, moveFocus: false });
      expect(tableHighlight()).not.toBe(first);
    });

    it('stepping keeps the focus on the stepper, and on ‹ once › is disabled', async () => {
      wide(true);
      await renderFinished(LINKED);
      await click('.chip');
      await vi.waitFor(() => expect(document.activeElement?.closest('app-table-tab')).toBeTruthy());
      const prev = () => el('.stepper button[aria-label="Previous occurrence"]') as HTMLButtonElement;
      const next = () => el('.stepper button[aria-label="Next occurrence"]') as HTMLButtonElement;
      next().focus();
      await click('.stepper button[aria-label="Next occurrence"]');
      await new Promise((r) => setTimeout(r, 20));
      expect(document.activeElement).toBe(next());
      expect(el('[aria-live] .occ')!.textContent).toContain('Showing occurrence 2 of 3: SML row 4 · abundance_assay[3]');
      await click('.stepper button[aria-label="Next occurrence"]');
      await new Promise((r) => setTimeout(r, 20));
      expect(next().disabled).toBe(true);
      expect(document.activeElement).toBe(prev());
      expect(el('[aria-live] .occ')!.textContent).toContain('Showing occurrence 3 of 3: SML row 61 · abundance_assay[3]');
    });

    it('steps on narrow screens from the back bar', async () => {
      await renderFinished(LINKED);
      await click('.chip');
      await click('.back-bar button[aria-label="Next occurrence"]');
      expect(el('.back-bar')!.textContent).toContain('2 of 3');
      expect(tableHighlight()).toEqual({ rows: [3], focusRow: 3, column: 'abundance_assay[3]', header: false, moveFocus: false });
    });

    it('highlights every occurrence of a group with "Highlight all"', async () => {
      wide(true);
      await renderFinished(LINKED);
      await click('.act-all');
      expect(selectedTab()).toBe('Summary');
      expect(tableHighlight()).toEqual({ rows: [1, 3, 60], focusRow: 1, column: 'abundance_assay[3]', header: false, moveFocus: true });
    });

    it('a second click on the same chip is a new jump', async () => {
      wide(true);
      await renderFinished(LINKED);
      await click('.chip');
      const first = tableHighlight();
      await click('.chip');
      expect(tableHighlight()).toEqual(first);
      expect(tableHighlight()).not.toBe(first);
    });

    /** Presses a key on the selected tab, as a keyboard user would. */
    async function key(k: string) {
      el('[role="tab"][aria-selected="true"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }));
      await serve();
    }

    it('a manual tab change consumes the jump: coming back neither replays it nor takes the focus', async () => {
      wide(true);
      await renderFinished(LINKED);
      await click('.chip');
      expect(tableHighlight()).not.toBeNull();
      await key('ArrowLeft');
      expect(selectedTab()).toBe('Metadata');
      expect(metadataHighlight()).toBeNull();
      await key('ArrowRight');
      expect(selectedTab()).toBe('Summary');
      expect(tableHighlight()).toBeNull();
      await new Promise((r) => setTimeout(r, 20));
      expect(document.activeElement).toBe(el('#tab-summary'));
    });

    it('a layout switch consumes the jump', async () => {
      await renderFinished(LINKED);
      await click('.chip');
      expect(selectedTab()).toBe('Summary');
      expect(tableHighlight()).not.toBeNull();
      wide(true);
      await serve();
      expect(el('.split')).toBeTruthy();
      expect(selectedTab()).toBe('Summary');
      expect(tableHighlight()).toBeNull();
      wide(false);
      await serve();
      expect(el('.split')).toBeNull();
      expect(tableHighlight()).toBeNull();
    });

    it('shows the stepper only on the tab of the shown occurrence; a step there is a new jump', async () => {
      wide(true);
      await renderFinished(LINKED);
      await click('.chip');
      await click('#tab-features');
      expect(el('.stepper')).toBeNull();
      await click('#tab-summary');
      expect(el('.stepper')!.textContent).toContain('1 of 3');
      expect(tableHighlight()).toBeNull();
      await click('.stepper button[aria-label="Next occurrence"]');
      expect(tableHighlight()).toEqual({ rows: [3], focusRow: 3, column: 'abundance_assay[3]', header: false, moveFocus: false });
    });

    it('shows the back bar only on the tab of the shown occurrence', async () => {
      await renderFinished(LINKED);
      await click('.chip');
      await click('#tab-features');
      expect(el('.back-bar')).toBeNull();
      await click('#tab-summary');
      expect(el('.back-bar')!.textContent).toContain('1 of 3');
    });

    it('renders no link for a message whose tab is absent', async () => {
      const toEvidence: ValidationMessage = {
        ...cellError(0),
        code: '[Error-2011] line 40: bad evidence\r\n',
        suggestion: 'Fix the evidence row.',
        location: { section: 'sme', row_index: 0 },
      };
      wide(true);
      await renderFinished({
        ...LINKED,
        sections: { ...BASE.sections!, sme: undefined },
        messages: [...LINKED.messages!, toEvidence],
      });
      const card = els('.grp').find((g) => g.textContent?.includes('Fix the evidence row'))!;
      expect(card.querySelector('.chip')).toBeNull();
      expect(card.querySelector('.act-first')).toBeNull();
      expect(els('.chip').length).toBeGreaterThan(0);
    });

    it('moves through the document tabs with the keyboard, skipping disabled ones', async () => {
      wide(true);
      await renderFinished({ ...LINKED, sections: { ...BASE.sections!, smf: undefined } });
      const visit: [string, string][] = [
        ['ArrowRight', 'Summary'],
        ['ArrowRight', 'Evidence'],
        ['ArrowRight', 'Metadata'],
        ['ArrowLeft', 'Evidence'],
        ['Home', 'Metadata'],
        ['End', 'Evidence'],
      ];
      for (const [k, tab] of visit) {
        await key(k);
        expect(selectedTab()).toBe(tab);
        expect(label(document.activeElement!)).toBe(tab);
        expect(el('[role="tab"][aria-selected="true"]')!.getAttribute('tabindex')).toBe('0');
      }
      expect(els('[role="tab"][tabindex="0"]').length).toBe(1);
    });

    it('stays single-column for unparsed files even when wide, without links', async () => {
      wide(true);
      await renderFinished({ ...NOT_PARSED });
      expect(el('.split')).toBeNull();
      expect(roleNames('tab')).toEqual(['Messages']);
      expect(el('.chip')).toBeNull();
      expect(el('.grp')!.textContent).toContain('Enter a number');
    });
  });
});
