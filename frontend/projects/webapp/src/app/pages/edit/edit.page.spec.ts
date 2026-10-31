import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component } from '@angular/core';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';

import { AppInfoDto } from '../../api/models/app-info-dto';
import { Guidance } from '../../api/models/guidance';
import { ValidationMessage } from '../../api/models/validation-message';
import { WorkingCopyInfo } from '../../api/models/working-copy-info';
import { WorkingCopyValidation } from '../../api/models/working-copy-validation';
import { EditPage, isUnreadable } from './edit.page';
import { CHECK_FAILED, CONFLICT_NOTICE, IDLE_MS, POLL_MS, WorkingCopyStore } from './working-copy.store';

@Component({ template: '<p>a dialog</p>' })
class SomeDialogComponent {}

const APP_INFO: AppInfoDto = {
  retentionDays: 7,
  engines: [
    { line: '2.0', available: true },
    { line: '2.1', available: true },
  ],
};

const msg = (over: Partial<ValidationMessage> = {}): ValidationMessage => ({
  category: 'format',
  code: '[Error-1012] line 24: x',
  message: 'The parameter of instrument[1]-detector is empty',
  message_type: 'error',
  line_number: 24,
  location: { section: 'mtd', metadata_key: 'instrument[1]-detector', metadata_group: 'instrument' },
  ...over,
});
const brokenLine = msg({
  code: '[Error-1000] line 12: x',
  message: 'Line 12 does not start with a section prefix',
  line_number: 12,
  location: undefined,
});
const warn = msg({ code: '[Warn-3014] x', message: 'cv[3] is never used', message_type: 'warn', line_number: 14 });

const done = (over: Partial<WorkingCopyValidation> = {}): WorkingCopyValidation => ({
  status: 'DONE',
  revision: 0,
  stale: false,
  parsed: true,
  readUpTo: null,
  semantic: false,
  messages: [],
  fixed: [],
  guidance: [],
  ...over,
});
const info = (over: Partial<WorkingCopyInfo> = {}): WorkingCopyInfo => ({
  id: 'abc',
  fileName: 'lip.mzTab',
  revision: 0,
  mzTabVersion: '2.0.0-M',
  lineCount: 86,
  sections: { metadataLines: 60, sml: { rows: 2, columns: 20 } },
  canUndo: false,
  canRedo: false,
  expiresAt: '2026-11-07T10:00:00Z',
  validation: done(),
  ...over,
});

describe('EditPage', () => {
  let http: HttpTestingController;

  async function render() {
    const params = new BehaviorSubject(convertToParamMap({ id: 'abc' }));
    await TestBed.configureTestingModule({
      imports: [EditPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MATERIAL_ANIMATIONS, useValue: { animationsDisabled: true } },
        { provide: ActivatedRoute, useValue: { paramMap: params, snapshot: { queryParamMap: convertToParamMap({}) } } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(EditPage);
    fixture.detectChanges();
    http.expectOne((r) => r.url.endsWith('rest/v2/app-info')).flush(APP_INFO);
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  const isCreate = (encoding = 'UTF_8') => (r: { method: string; url: string; params: { get(k: string): string | null } }) =>
    r.method === 'POST' &&
    r.url.endsWith('rest/v2/working-copies') &&
    r.params.get('validation') === 'abc' &&
    r.params.get('encoding') === encoding;

  /** Lets answers, effects and renders run; not whenStable, which waits for every open request. */
  async function settle(fixture: ComponentFixture<EditPage>) {
    for (let i = 0; i < 3; i++) {
      if (vi.isFakeTimers()) await vi.advanceTimersByTimeAsync(0);
      else await new Promise((r) => setTimeout(r, 0));
      fixture.detectChanges();
    }
  }

  /** Opens the editor on a copy: create, the validation record (for its profile), and the first document pages. */
  async function open(copy: WorkingCopyInfo = info(), profile: 'NONE' | 'CATALOG' = 'NONE') {
    const ctx = await render();
    http.expectOne(isCreate()).flush(copy, { status: 201, statusText: 'Created' });
    http
      .expectOne((r) => r.method === 'GET' && r.url.endsWith('rest/v2/validations/abc'))
      .flush({ id: 'abc', status: 'FINISHED', validationProfile: { kind: profile } });
    await settle(ctx.fixture);
    answerDocs();
    await settle(ctx.fixture);
    answerDocs();
    await settle(ctx.fixture);
    return ctx;
  }

  /** Answers the metadata and table requests of the views with an empty document. */
  function answerDocs(): number {
    const docs = http.match((r) => r.method === 'GET' && /working-copies\/abc\/(metadata|tables)/.test(r.url));
    for (const r of docs) {
      if (r.request.url.endsWith('/metadata')) r.flush([{ name: 'general', count: 1 }]);
      else if (r.request.url.includes('/metadata/')) r.flush({ group: 'general', page: 0, size: 50, total: 0, entries: [] });
      else r.flush({ section: 'SML', page: 0, size: 50, totalRows: 0, allColumns: ['SML_ID'], columns: ['SML_ID'], rows: [] });
    }
    return docs.length;
  }

  const getInfo = () => http.expectOne((r) => r.method === 'GET' && r.url.endsWith('rest/v2/working-copies/abc'));
  const postEdit = () => http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/edits'));
  const postValidation = () =>
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations'));
  const noValidation = () =>
    expect(http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations')).length).toBe(0);
  const overlayText = () => document.querySelector('.cdk-overlay-container')?.textContent ?? '';
  const button = (el: HTMLElement, testid: string) => el.querySelector<HTMLButtonElement>(`[data-testid=${testid}]`)!;

  /** An edit the way a document view makes one: through the page's DocumentSource. */
  async function editThroughSource(fixture: ComponentFixture<EditPage>): Promise<TestRequest> {
    const { DocumentSource } = await import('@lifs-tools/mztabm-editor');
    const source = fixture.debugElement.injector.get(DocumentSource);
    source.applyEdits!([{ op: 'SetMetadataValue', line: 3, value: 'x' }]).subscribe({ error: () => undefined });
    await settle(fixture);
    return postEdit();
  }

  afterEach(() => {
    vi.useRealTimers();
    answerDocs();
    http.verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((c) => c.remove());
  });

  it('validates after edits once idle and marks new and fixed messages', async () => {
    vi.useFakeTimers();
    const { fixture, el } = await open(info({ validation: done({ messages: [{ message: msg(), state: 'OPEN' }] }) }));
    expect(el.textContent).not.toContain('Checking your latest changes…');

    const edit = await editThroughSource(fixture);
    expect(edit.request.body).toEqual({ revision: 0, operations: [{ op: 'SetMetadataValue', line: 3, value: 'x' }] });
    expect(button(el, 'save-state').textContent?.trim()).toBe('Saving…');
    edit.flush(info({ revision: 1, canUndo: true, validation: done({ stale: true, messages: [{ message: msg(), state: 'OPEN' }] }) }));
    await settle(fixture);
    expect(button(el, 'save-state').textContent?.trim()).toBe('Saved');
    expect(el.querySelector('[data-testid=run-status]')?.textContent).toContain('Checking your latest changes…');

    await vi.advanceTimersByTimeAsync(IDLE_MS - 1);
    noValidation();
    await vi.advanceTimersByTimeAsync(1);
    const run = postValidation();
    expect(run.request.params.get('semantic')).toBe('false');
    run.flush(info({ revision: 1, canUndo: true, validation: done({ status: 'RUNNING', revision: 1, messages: [{ message: msg(), state: 'OPEN' }] }) }));
    await settle(fixture);

    await vi.advanceTimersByTimeAsync(POLL_MS);
    getInfo().flush(
      info({
        revision: 1,
        canUndo: true,
        validation: done({ revision: 1, messages: [{ message: warn, state: 'NEW' }], fixed: [msg()] }),
      }),
    );
    await settle(fixture);
    expect(el.textContent).not.toContain('Checking your latest changes…');
    const card = el.querySelector('.grp')!;
    expect(card.textContent).toContain('cv[3] is never used');
    expect(card.querySelector('.new-badge')?.textContent).toBe('New');
    const fixed = el.querySelector<HTMLDetailsElement>('[data-testid=fixed]')!;
    expect(fixed.querySelector('summary')?.textContent).toBe('Fixed since the last check (1)');
    expect(fixed.open).toBe(false);
    expect(fixed.textContent).toContain('The parameter of instrument[1]-detector is empty');

    // the run ended: no more polling
    await vi.advanceTimersByTimeAsync(POLL_MS * 3);
    expect(http.match((r) => r.method === 'GET' && r.url.endsWith('rest/v2/working-copies/abc')).length).toBe(0);
  });

  it('reloadsOnConflict', async () => {
    const { fixture } = await open();
    const edit = await editThroughSource(fixture);
    edit.flush({ error: 'Conflict', message: 'stale revision' }, { status: 409, statusText: 'Conflict' });
    await settle(fixture);
    getInfo().flush(info({ revision: 4, canUndo: true }));
    await settle(fixture);
    // the views ask for what they show again
    expect(answerDocs()).toBeGreaterThan(0);
    await settle(fixture);
    expect(overlayText()).toContain(CONFLICT_NOTICE);
    // the next edit names the reloaded revision
    const next = await editThroughSource(fixture);
    expect(next.request.body.revision).toBe(4);
    next.flush(info({ revision: 5 }));
  });

  it('opens the raw line editor for a line without prefix', async () => {
    const { fixture, el } = await open(
      info({ validation: done({ parsed: false, readUpTo: 12, messages: [{ message: brokenLine, state: 'OPEN' }] }) }),
    );
    const chip = Array.from(el.querySelectorAll<HTMLButtonElement>('.chip')).find((c) => c.textContent?.trim() === 'line 12')!;
    expect(chip).toBeTruthy();
    chip.click();
    await settle(fixture);
    const lines = http.expectOne((r) => r.url.endsWith('working-copies/abc/lines'));
    expect(lines.request.params.get('from')).toBe('11');
    expect(lines.request.params.get('count')).toBe('3');
    lines.flush([
      { number: 11, section: 'MTD', cells: ['MTD', 'description', 'Minimal file for'], eol: '\n' },
      { number: 12, section: '?', cells: ['identification of lipids', '', ''], eol: '\n' },
      { number: 13, section: 'MTD', cells: ['MTD', 'cv[1]-label', 'MS'], eol: '\n' },
    ]);
    await settle(fixture);
    const editor = el.querySelector('mztabm-raw-line-editor')!;
    expect(editor.textContent).toContain('Line 12');
    expect(editor.querySelector<HTMLInputElement>('input')!.value).toBe('identification of lipids→→');
  });

  it('decides which lines the raw editor takes', () => {
    const line = (section: string, cells: string[]) => ({ number: 1, section, cells, eol: '\n' });
    const mtd = line('MTD', ['MTD', 'a', 'b']);
    expect(isUnreadable(line('?', ['x']))).toBe(true);
    expect(isUnreadable(line('BLANK', ['']), mtd, mtd)).toBe(true);
    expect(isUnreadable(line('BLANK', ['']), mtd, line('SMH', ['SMH']))).toBe(false);
    expect(isUnreadable(line('MTD', ['MTD', 'description  text']))).toBe(true);
    expect(isUnreadable(line('MTD', ['MTD', 'a', 'b', 'c']))).toBe(true);
    // trailing empty cells, as many tools write them, are not extra parts
    expect(isUnreadable(line('MTD', ['MTD', 'a', 'b', '', '', '']))).toBe(false);
    expect(isUnreadable(line('SML', ['SML', '1', '2']))).toBe(false);
  });

  it('shows how far the file was read when parsing stopped', async () => {
    const { el } = await open(
      info({ validation: done({ parsed: false, readUpTo: 12, messages: [{ message: brokenLine, state: 'OPEN' }] }) }),
    );
    expect(el.querySelector('[data-testid=read-up-to]')?.textContent?.trim()).toBe(
      'jmzTab-m stopped reading at line 12; problems further down are not reported yet.',
    );
    expect(button(el, 'download-json').disabled).toBe(true);
  });

  it('offers JSON download only for a current valid state', async () => {
    const valid = info();
    const { fixture, el } = await open(valid);
    const json = () => button(el, 'download-json');
    expect(json().disabled).toBe(false);
    expect(el.querySelector('[data-testid=download-tsv]')?.getAttribute('href')).toBe('rest/v2/working-copies/abc/file?format=tsv');
    expect(el.querySelector('[data-testid=download-original]')?.getAttribute('href')).toBe('rest/v2/working-copies/abc/original');
    expect(el.querySelector('[data-testid=download-report]')?.getAttribute('href')).toBe('rest/v2/validations/abc/report?format=md');

    // a 409 (the copy changed meanwhile) reloads it, which disables the button
    const createObjectURL = vi.fn(() => 'blob:x');
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
    json().click();
    const download = http.expectOne((r) => r.url.endsWith('working-copies/abc/file') && r.params.get('format') === 'json');
    download.flush(new Blob(['{}']), { status: 409, statusText: 'Conflict' });
    await settle(fixture);
    getInfo().flush(info({ revision: 1, validation: done({ stale: true }) }));
    await settle(fixture);
    expect(json().disabled).toBe(true);
    expect(createObjectURL).not.toHaveBeenCalled();
  });

  it('disables JSON for an error, a stale run or a run of another revision', async () => {
    const cases: WorkingCopyInfo[] = [
      info({ validation: done({ messages: [{ message: msg(), state: 'OPEN' }] }) }),
      info({ validation: done({ stale: true }) }),
      info({ revision: 2, validation: done({ revision: 1 }) }),
      info({ validation: done({ status: 'RUNNING' }) }),
    ];
    for (const copy of cases) {
      TestBed.resetTestingModule();
      const { el } = await open(copy);
      expect(button(el, 'download-json').disabled).toBe(true);
      answerDocs();
      http.match(() => true).forEach((r) => r.flush(copy));
    }
  });

  it('offers re-encoding on not_utf8', async () => {
    const { fixture, el } = await render();
    http.expectOne(isCreate()).flush(
      { error: 'not_utf8', message: 'The file is not encoded as UTF-8; the first line that is not is line 3.' },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    http.expectOne((r) => r.url.endsWith('rest/v2/validations/abc')).flush({ id: 'abc', status: 'FINISHED' });
    await settle(fixture);
    const notice = el.querySelector('[data-testid=not-utf8]')!;
    expect(notice.textContent).toContain('the first line that is not is line 3.');
    const reencode = Array.from(notice.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Re-encode from Windows-1252')!;
    reencode.click();
    await settle(fixture);
    http.expectOne(isCreate('WINDOWS_1252')).flush(info({ validation: undefined }), { status: 201, statusText: 'Created' });
    await settle(fixture);
    // a new copy is checked right away
    postValidation().flush(info({ validation: done({ status: 'RUNNING' }) }));
    await settle(fixture);
    expect(el.querySelector('h1')?.textContent).toBe('lip.mzTab');
    expect(el.querySelector('[data-testid=not-utf8]')).toBeNull();
    answerDocs();
  });

  it('announces a version change after a run', async () => {
    vi.useFakeTimers();
    const { fixture } = await open(info({ mzTabVersion: '2.0.0-M', validation: done({ status: 'RUNNING' }) }));
    expect(overlayText()).not.toContain('now checked as');
    await vi.advanceTimersByTimeAsync(POLL_MS);
    getInfo().flush(info({ mzTabVersion: '2.1.0-M', validation: done() }));
    await settle(fixture);
    expect(overlayText()).toContain('This file is now checked as mzTab-M 2.1.');
  });

  it('says so when the new version is not supported', async () => {
    vi.useFakeTimers();
    const { fixture } = await open(info({ mzTabVersion: '2.0.0-M', validation: done({ status: 'RUNNING' }) }));
    await vi.advanceTimersByTimeAsync(POLL_MS);
    getInfo().flush(info({ mzTabVersion: '3.0.0', validation: done() }));
    await settle(fixture);
    expect(overlayText()).toContain('mzTab-M 3.0 is not supported by this validator.');
  });

  it('says nothing when the version of the line stays', async () => {
    vi.useFakeTimers();
    const { fixture } = await open(info({ mzTabVersion: '2.0', validation: done({ status: 'RUNNING' }) }));
    await vi.advanceTimersByTimeAsync(POLL_MS);
    getInfo().flush(info({ mzTabVersion: '2.0.0-M', validation: done() }));
    await settle(fixture);
    expect(overlayText()).not.toContain('mzTab-M');
  });

  it('checks a new copy right away and polls while the run is running', async () => {
    vi.useFakeTimers();
    const { fixture, el } = await render();
    http.expectOne(isCreate()).flush(info({ validation: undefined }), { status: 201, statusText: 'Created' });
    http.expectOne((r) => r.url.endsWith('rest/v2/validations/abc')).flush({ id: 'abc', status: 'FINISHED' });
    await settle(fixture);
    postValidation().flush(info({ validation: done({ status: 'RUNNING' }) }));
    await settle(fixture);
    answerDocs();
    expect(el.textContent).toContain('Checking your latest changes…');
    await vi.advanceTimersByTimeAsync(POLL_MS - 1);
    expect(http.match((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc')).length).toBe(0);
    await vi.advanceTimersByTimeAsync(1);
    getInfo().flush(info({ validation: done({ status: 'RUNNING' }) }));
    await vi.advanceTimersByTimeAsync(POLL_MS);
    getInfo().flush(info());
    await settle(fixture);
    expect(el.textContent).toContain('No problems found.');
  });

  it('keeps a newer state when a poll that crossed an edit answers late', async () => {
    vi.useFakeTimers();
    const { fixture } = await open(info({ validation: done({ status: 'RUNNING' }) }));
    await vi.advanceTimersByTimeAsync(POLL_MS);
    const late = getInfo();
    (await editThroughSource(fixture)).flush(info({ revision: 1, canUndo: true, validation: done({ status: 'RUNNING' }) }));
    await settle(fixture);
    late.flush(info({ revision: 0, validation: done({ status: 'RUNNING' }) }));
    await settle(fixture);
    const next = await editThroughSource(fixture);
    expect(next.request.body.revision).toBe(1);
    next.flush(info({ revision: 2, validation: done({ revision: 2 }) }));
    await settle(fixture);
    await vi.advanceTimersByTimeAsync(IDLE_MS);
    postValidation().flush(info({ revision: 2, validation: done({ revision: 2 }) }));
    await vi.advanceTimersByTimeAsync(POLL_MS);
    http.match((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc')).forEach((r) => r.flush(info({ revision: 2, validation: done({ revision: 2 }) })));
  });

  it('a poll that crossed the request for the next run cannot end the check', async () => {
    vi.useFakeTimers();
    // run 1 checks revision 4; the copy is at revision 5 already
    const { fixture, el } = await open(info({ revision: 5, validation: done({ status: 'RUNNING', revision: 4 }) }), 'CATALOG');
    await vi.advanceTimersByTimeAsync(POLL_MS);
    const late = getInfo();
    // the request for run 2 (as the idle timer or "Check CV rules" make it)
    fixture.debugElement.injector.get(WorkingCopyStore).validate(true);
    postValidation().flush(info({ revision: 5, validation: done({ status: 'RUNNING', revision: 5, semantic: true }) }));
    await settle(fixture);
    // the answer to the request for run 2 restarts polling: the read sent before it is dropped
    expect(late.cancelled).toBe(true);
    expect(el.querySelector('[data-testid=run-status]')?.textContent).toContain('Checking your latest changes…');

    await vi.advanceTimersByTimeAsync(POLL_MS);
    getInfo().flush(info({ revision: 5, validation: done({ revision: 5, semantic: true }) }));
    await settle(fixture);
    expect(el.querySelector('[data-testid=run-status]')?.textContent).not.toContain('Checking your latest changes…');
  });

  it('a failed request for a check says so and offers Retry', async () => {
    const ctx = await render();
    http.expectOne(isCreate()).flush(info({ validation: undefined }), { status: 201, statusText: 'Created' });
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('rest/v2/validations/abc')).flush({ id: 'abc', status: 'FINISHED' });
    await settle(ctx.fixture);
    postValidation().flush({ message: 'down' }, { status: 503, statusText: 'Unavailable' });
    await settle(ctx.fixture);
    answerDocs();
    const callout = ctx.el.querySelector<HTMLElement>('[data-testid=check-failed]')!;
    expect(callout.getAttribute('role')).toBe('alert');
    expect(callout.textContent).toContain(CHECK_FAILED);
    expect(ctx.el.querySelector('[data-testid=run-status]')?.textContent).not.toContain('Checking your latest changes…');

    [...callout.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Retry')!.click();
    await settle(ctx.fixture);
    expect(ctx.el.querySelector('[data-testid=check-failed]')).toBeNull();
    postValidation().flush(info({ validation: done({ status: 'DONE' }) }));
    await settle(ctx.fixture);
  });

  it('does not say "No problems found." for a check of an older text that could not be redone', async () => {
    // the last run, without messages, checked revision 0; the copy is at revision 1
    await expectNoVerdictAfterAFailedCheck(info({ revision: 1, validation: done({ revision: 0 }) }));
  });

  it('does not say "No problems found." while the last request for a check failed', async () => {
    // the run is of the text as it is, but the check asked for since (with CV rules) did not start
    await expectNoVerdictAfterAFailedCheck(info());
  });

  async function expectNoVerdictAfterAFailedCheck(copy: WorkingCopyInfo) {
    const ctx = await open(copy);
    ctx.fixture.debugElement.injector.get(WorkingCopyStore).validate(true);
    for (const r of http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations'))) {
      r.flush({ message: 'down' }, { status: 503, statusText: 'Unavailable' });
    }
    await settle(ctx.fixture);
    expect(ctx.el.querySelector('[data-testid=check-failed]')).not.toBeNull();
    expect(ctx.el.querySelector('[data-testid=run-status]')?.textContent).not.toContain('No problems found.');
  }

  it('shows "not found" when the copy is gone while polling or on an edit', async () => {
    vi.useFakeTimers();
    const first = await open(info({ validation: done({ status: 'RUNNING' }) }));
    await vi.advanceTimersByTimeAsync(POLL_MS);
    getInfo().flush({ message: 'gone' }, { status: 404, statusText: 'Not Found' });
    await settle(first.fixture);
    expect(first.el.textContent).toContain('File not found');
    // and the poll stops
    await vi.advanceTimersByTimeAsync(POLL_MS * 3);
    expect(http.match((r) => r.method === 'GET' && r.url.endsWith('rest/v2/working-copies/abc')).length).toBe(0);

    vi.useRealTimers();
    TestBed.resetTestingModule();
    const second = await open();
    (await editThroughSource(second.fixture)).flush({ message: 'gone' }, { status: 404, statusText: 'Not Found' });
    await settle(second.fixture);
    expect(second.el.textContent).toContain('File not found');
    expect(overlayText()).not.toContain('could not be saved');
  });

  it("says why the JSON download failed, with the server's reason", async () => {
    const { fixture, el } = await open();
    button(el, 'download-json').click();
    http
      .expectOne((r) => r.url.endsWith('working-copies/abc/file') && r.params.get('format') === 'json')
      .flush(new Blob([JSON.stringify({ message: 'The storage is full.' })]), { status: 500, statusText: 'Error' });
    for (let i = 0; i < 5; i++) await settle(fixture);
    expect(overlayText()).toContain('The storage is full.');
  });

  it('ignores Ctrl+Z while a dialog is open', async () => {
    const { fixture } = await open(info({ revision: 3, canUndo: true }));
    const ref = TestBed.inject(MatDialog).open(SomeDialogComponent);
    await settle(fixture);
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, cancelable: true }));
    expect(http.match((r) => r.url.endsWith('/undo')).length).toBe(0);
    ref.close();
    await settle(fixture);
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, cancelable: true }));
    http.expectOne((r) => r.url.endsWith('working-copies/abc/undo')).flush(info({ revision: 4 }));
    await settle(fixture);
  });

  it('column edits make every view read again; a refused edit carries the reason', async () => {
    const { fixture } = await open();
    const { DocumentSource } = await import('@lifs-tools/mztabm-editor');
    const source = fixture.debugElement.injector.get(DocumentSource);

    let failure: unknown;
    source.applyEdits!([{ op: 'MergeColumns', header: 'SMH', name: 'n', keep: 3, others: [7] }]).subscribe({
      error: (e: unknown) => (failure = e),
    });
    await settle(fixture);
    postEdit().flush({ message: 'Column 7 of SMH is not n' }, { status: 400, statusText: 'Bad Request' });
    await settle(fixture);
    expect(failure).toEqual({ status: 400, detail: 'Column 7 of SMH is not n' });

    source.applyEdits!([{ op: 'DeleteColumn', header: 'SMH', name: 'n', position: 7 }]).subscribe();
    await settle(fixture);
    postEdit().flush(info({ revision: 1, canUndo: true }));
    await settle(fixture);
    expect(answerDocs()).toBeGreaterThan(0);

    let found: unknown;
    source.duplicateColumns!('SFH').subscribe((d) => (found = d));
    http
      .expectOne((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc/columns/SFH/duplicates'))
      .flush([{ name: 'n', positions: [3, 7], conflictingRows: 0, samples: [] }]);
    expect(found).toEqual([{ name: 'n', positions: [3, 7], conflictingRows: 0, samples: [] }]);
    await vi.waitFor(() => undefined);
    http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations')).forEach((r) => r.flush(info({ revision: 1 })));
  });

  it('shows the retention, the save state and the version', async () => {
    const { el } = await open();
    expect(el.querySelector('[data-testid=retention]')?.textContent).toBe('Kept for 7 days after your last change');
    expect(el.querySelector('.lifs-eyebrow')?.textContent).toContain('7 Nov 2026');
    expect(el.querySelector('[data-testid=version-badge]')?.textContent).toBe('mzTab-M 2.0.0-M');
    expect(button(el, 'save-state').textContent?.trim()).toBe('Saved');
  });

  it('has inline help that links to the guide', async () => {
    const { el } = await open();
    const help = el.querySelector('[data-testid=help]')!;
    expect(help.querySelector('summary')?.textContent?.trim()).toBe('How to fix this file');
    expect(help.textContent).toContain('last 100 steps');
    expect(help.querySelector('a')?.getAttribute('href')).toBe('/guide');
  });

  it('undoes and redoes with the buttons and Ctrl+Z / Ctrl+Shift+Z outside inputs', async () => {
    const { fixture, el } = await open(info({ revision: 3, canUndo: true, canRedo: true }));
    const key = (target: EventTarget, shift = false) =>
      target.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, shiftKey: shift, bubbles: true, cancelable: true }));

    key(document.body);
    const undo = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/undo'));
    expect(undo.request.params.get('revision')).toBe('3');
    undo.flush(info({ revision: 4, canUndo: true, canRedo: true }));
    await settle(fixture);
    answerDocs();

    key(document.body, true);
    const redo = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/redo'));
    expect(redo.request.params.get('revision')).toBe('4');
    redo.flush(info({ revision: 5, canUndo: true, canRedo: false }));
    await settle(fixture);
    answerDocs();
    expect(button(el, 'redo').disabled).toBe(true);

    // inside a field, Ctrl+Z belongs to the field
    const input = document.createElement('input');
    el.appendChild(input);
    key(input);
    expect(http.match((r) => r.url.endsWith('/undo')).length).toBe(0);

    button(el, 'undo').click();
    http.expectOne((r) => r.url.endsWith('working-copies/abc/undo')).flush(info({ revision: 6 }));
    await settle(fixture);
  });

  it('offers CV rules only for a session with a profile', async () => {
    const { el } = await open(info(), 'NONE');
    expect(el.querySelector('[data-testid=check-cv]')).toBeNull();
    TestBed.resetTestingModule();
    const ctx = await open(info(), 'CATALOG');
    button(ctx.el, 'check-cv').click();
    const run = postValidation();
    expect(run.request.params.get('semantic')).toBe('true');
    run.flush(info({ validation: done({ status: 'DONE', semantic: true }) }));
  });

  describe('quick fixes', () => {
    const fixGuidance = (quickFix: string, messages = [0]): Guidance => ({
      typeCode: 'Error-1012',
      title: 'Empty parameter',
      explanation: 'x',
      fixHere: 'Remove the line.',
      fixInTool: 'y',
      quickFix,
      messages,
      lines: [],
    });

    it('disables the quick fix while the latest changes are checked', async () => {
      const { el } = await open(
        info({ validation: done({ stale: true, messages: [{ message: msg(), state: 'OPEN' }], guidance: [fixGuidance('remove-empty-parameter')] }) }),
      );
      const fix = el.querySelector<HTMLButtonElement>('button.quick-fix')!;
      expect(fix.disabled).toBe(true);
      expect(fix.closest('.quick-actions')?.textContent).toContain('Checking your latest changes…');
    });

    it('previews, applies and validates once idle', async () => {
      const { fixture, el } = await open(
        info({ validation: done({ messages: [{ message: msg(), state: 'OPEN' }], guidance: [fixGuidance('remove-empty-parameter')] }) }),
      );
      const fix = el.querySelector<HTMLButtonElement>('button.quick-fix')!;
      expect(fix.disabled).toBe(false);
      fix.click();
      const preview = http.expectOne((r) => r.url.endsWith('working-copies/abc/quick-fixes/remove-empty-parameter'));
      expect(preview.request.params.get('message')).toBe('0');
      expect(preview.request.params.get('all')).toBe('false');
      const batch = { revision: 0, operations: [{ op: 'DeleteLine', line: 24 }] };
      preview.flush({ fixId: 'remove-empty-parameter', title: 'Remove the line', revision: 0, occurrences: 1, changes: [{ lineNumber: 24, before: 'MTD\tx\t[, , , ]', after: null }], batch });
      await settle(fixture);
      expect(overlayText()).toContain('Remove the line');
      Array.from(document.querySelectorAll<HTMLButtonElement>('.cdk-overlay-container button')).find((b) => b.textContent?.trim() === 'Apply')!.click();
      await settle(fixture);
      const edit = postEdit();
      expect(edit.request.body).toEqual(batch);
      vi.useFakeTimers();
      edit.flush(info({ revision: 1, canUndo: true, validation: done({ stale: true }) }));
      await settle(fixture);
      answerDocs();
      await vi.advanceTimersByTimeAsync(IDLE_MS);
      postValidation().flush(info({ revision: 1, validation: done({ status: 'RUNNING', revision: 1 }) }));
      await vi.advanceTimersByTimeAsync(POLL_MS);
      getInfo().flush(info({ revision: 1, validation: done({ revision: 1 }) }));
      await settle(fixture);
    });

    it('a preview refused as not checked yet starts a check and shows no conflict', async () => {
      const { fixture, el } = await open(
        info({ validation: done({ messages: [{ message: msg(), state: 'OPEN' }], guidance: [fixGuidance('normalise-doi', [0])] }) }),
      );
      el.querySelector<HTMLButtonElement>('button.quick-fix')!.click();
      http
        .expectOne((r) => r.url.includes('/quick-fixes/'))
        .flush({ message: 'Validate the current revision first.' }, { status: 409, statusText: 'Conflict' });
      await settle(fixture);
      postValidation().flush(info({ validation: done({ status: 'RUNNING' }) }));
      await settle(fixture);
      expect(overlayText()).not.toContain(CONFLICT_NOTICE);
    });

    it('opens the inserted line\'s value after adding missing metadata', async () => {
      const missing = msg({ code: '[Error-2008] x', message: '"sample[1]" MUST be defined in the metadata section', location: undefined, line_number: 30 });
      const { fixture, el } = await open(
        info({ validation: done({ messages: [{ message: missing, state: 'OPEN' }], guidance: [fixGuidance('add-missing-metadata')] }) }),
      );
      el.querySelector<HTMLButtonElement>('button.quick-fix')!.click();
      const batch = { revision: 0, operations: [{ op: 'InsertLine', before: 40, text: 'MTD\tsample[1]\t' }] };
      http
        .expectOne((r) => r.url.includes('/quick-fixes/add-missing-metadata'))
        .flush({ fixId: 'add-missing-metadata', title: 'Add the missing metadata line', revision: 0, occurrences: 1, changes: [{ lineNumber: 40, before: null, after: 'MTD\tsample[1]\t' }], batch });
      await settle(fixture);
      Array.from(document.querySelectorAll<HTMLButtonElement>('.cdk-overlay-container button')).find((b) => b.textContent?.trim() === 'Apply')!.click();
      await settle(fixture);
      postEdit().flush(info({ revision: 1, canUndo: true, validation: done({ stale: true }) }));
      await settle(fixture);
      // the views reload; the metadata view opens the group of the new key and asks for the page that holds it
      const groups = http.expectOne((r) => r.url.endsWith('working-copies/abc/metadata'));
      groups.flush([{ name: 'general', count: 1 }, { name: 'sample', count: 1 }]);
      await settle(fixture);
      await settle(fixture);
      const page = http
        .match((r) => r.url.endsWith('working-copies/abc/metadata/sample'))
        .find((r) => r.request.params.get('key') === 'sample[1]')!;
      expect(page).toBeTruthy();
      page.flush({ group: 'sample', page: 0, size: 50, total: 1, entries: [{ lineNumber: 40, key: 'sample[1]', value: '' }] });
      await settle(fixture);
      await settle(fixture);
      const input = el.querySelector<HTMLInputElement>('input.value-input');
      expect(input).toBeTruthy();
      expect(document.activeElement).toBe(input);
    });
  });
});
