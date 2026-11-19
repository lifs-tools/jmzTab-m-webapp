import { Component } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DocumentSource, ImportSource, MetadataViewComponent, StillMissingView } from '@lifs-tools/mztabm-editor';
import { Observable } from 'rxjs';

import { AppInfoStore } from '../../core/app-info.store';
import {
  EDITOR_TIMEOUT_MS,
  ENGINE_TIMEOUT_MS,
  READ_RETRY_DELAYS_MS,
  REQUEST_TIMEOUT,
  requestTimeoutInterceptor,
} from '../../core/request-timeout.interceptor';
import { WorkingCopyInfo } from '../../api/models/working-copy-info';
import { WorkingCopyImportSource, WorkingCopySource } from './working-copy-source';
import { WorkingCopyStore } from './working-copy.store';

@Component({ imports: [MetadataViewComponent], template: `<mztabm-metadata-view [readonly]="false" />` })
class HostComponent {}

const info = (revision: number): WorkingCopyInfo =>
  ({ id: 'abc', fileName: 'x.mzTab', revision, mzTabVersion: '2.0.0-M', lineCount: 5, canUndo: true, canRedo: false }) as WorkingCopyInfo;

describe('WorkingCopySource', () => {
  let http: HttpTestingController;
  let store: WorkingCopyStore;
  let source: WorkingCopySource;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        WorkingCopyStore,
        WorkingCopySource,
        { provide: DocumentSource, useExisting: WorkingCopySource },
        { provide: AppInfoStore, useValue: { load: () => undefined, info: () => undefined } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(WorkingCopyStore);
    source = TestBed.inject(WorkingCopySource);
    store.id.set('abc');
  });

  const postEdit = () => http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/edits'));

  it.each([
    [{ op: 'DeleteLine', line: 3 }],
    [{ op: 'InsertLine', before: 3, text: 'COM\tx' }],
    [{ op: 'MoveRow', line: 3, before: 5 }],
    [{ op: 'JoinWithPrevious', line: 3 }],
  ])('tells the views to reload after a line-moving edit: %o', (op) => {
    let changed = 0;
    source.changed!.subscribe(() => changed++);
    source.applyEdits([op as never]).subscribe();
    expect(changed).toBe(0);
    postEdit().flush(info(1));
    expect(changed).toBe(1);
  });

  it('does not reload the views after an edit that keeps the line numbers', () => {
    let changed = 0;
    source.changed!.subscribe(() => changed++);
    source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'x' }]).subscribe();
    postEdit().flush(info(1));
    expect(changed).toBe(0);
  });

  it('enables the Remove buttons of a metadata view again once the reload arrives', async () => {
    const fixture = TestBed.createComponent(HostComponent);
    const el = fixture.nativeElement as HTMLElement;
    const settle = async () => {
      for (let i = 0; i < 3; i++) {
        await new Promise((r) => setTimeout(r, 0));
        fixture.detectChanges();
      }
    };
    const answer = async (entries: { lineNumber: number; key: string; value: string }[]) => {
      await settle();
      for (const r of http.match((q) => q.method === 'GET' && /working-copies\/abc\/metadata/.test(q.url))) {
        if (r.request.url.endsWith('/metadata')) r.flush([{ name: 'general', count: entries.length }]);
        else r.flush({ group: 'general', page: 0, size: 50, total: entries.length, entries });
      }
      await settle();
    };
    await answer([]);
    await answer([
      { lineNumber: 2, key: 'mzTab-version', value: '2.0.0-M' },
      { lineNumber: 3, key: 'mzTab-ID', value: 'A' },
    ]);
    await answer([]);
    const buttons = () => Array.from(el.querySelectorAll<HTMLButtonElement>('button.entry-remove'));
    expect(buttons().length).toBe(2);
    buttons()[0].click();
    await settle();
    postEdit().flush(info(1));
    await answer([{ lineNumber: 2, key: 'mzTab-ID', value: 'A' }]);
    await answer([{ lineNumber: 2, key: 'mzTab-ID', value: 'A' }]);
    expect(buttons().length).toBe(1);
    expect(buttons()[0].disabled).toBe(false);
    expect(buttons()[0].getAttribute('aria-label')).toBe('Remove line 2');
  });
});

describe('WorkingCopyImportSource', () => {
  let http: HttpTestingController;
  let store: WorkingCopyStore;
  let source: ImportSource;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        WorkingCopyStore,
        WorkingCopyImportSource,
        { provide: ImportSource, useExisting: WorkingCopyImportSource },
        { provide: AppInfoStore, useValue: { load: () => undefined, info: () => undefined } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(WorkingCopyStore);
    source = TestBed.inject(ImportSource);
    store.id.set('abc');
    store.info.set(info(2));
  });

  it('reads the kinds and the still-missing rules of the copy', () => {
    const kinds: string[][] = [];
    source.importKinds().subscribe((k) => kinds.push(k));
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc/imports/kinds')).flush(['vocabulary-instance']);
    expect(kinds).toEqual([['vocabulary-instance']]);

    const missing: StillMissingView[] = [];
    source.stillMissing().subscribe((m) => missing.push(m));
    const rules = { revision: 2, checked: true, missing: [{ id: 'r', name: 'Quantification method', path: '/q', requirement: 'MUST', present: false }] };
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc/still-missing')).flush(rules);
    expect(missing).toEqual([rules]);
  });

  it("fails with the status and the server's reason, which the panel shows", () => {
    const errors: unknown[] = [];
    source
      .previewImport({ kind: 'vocabulary-instance', instance: {}, datasetIndex: 4, studyId: 'LCS-1', keepExisting: true })
      .subscribe({ error: (e) => errors.push(e) });
    http
      .expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/imports'))
      .flush({ message: 'dataset 4 of 3' }, { status: 400, statusText: 'Bad Request' });
    source.stillMissing().subscribe({ error: (e) => errors.push(e) });
    http
      .expectOne((r) => r.url.endsWith('working-copies/abc/still-missing'))
      .flush({ message: 'The validator is busy.' }, { status: 503, statusText: 'Service Unavailable' });
    expect(errors).toEqual([
      { status: 400, detail: 'dataset 4 of 3' },
      { status: 503, detail: 'The validator is busy.' },
    ]);
  });

  it('applies through the store: a 409 is "stale" once the copy was read again', () => {
    const outcomes: string[] = [];
    source.applyImport({ revision: 2, batch: { revision: 2, operations: [] } }).subscribe((o) => outcomes.push(o));
    http
      .expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/edits'))
      .flush({ message: 'stale' }, { status: 409, statusText: 'Conflict' });
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc')).flush(info(3));
    expect(outcomes).toEqual(['stale']);
  });
});

/** The views' reads are given 30 s and asked again on a lost answer; still missing waits for the engine (120 s, once). */
describe('WorkingCopySource and WorkingCopyImportSource: a server that does not answer', () => {
  let http: HttpTestingController;
  let source: WorkingCopySource;
  let imports: ImportSource;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([requestTimeoutInterceptor])),
        provideHttpClientTesting(),
        WorkingCopyStore,
        WorkingCopySource,
        WorkingCopyImportSource,
        { provide: AppInfoStore, useValue: { load: () => undefined, info: () => undefined } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    source = TestBed.inject(WorkingCopySource);
    imports = TestBed.inject(WorkingCopyImportSource);
    const store = TestBed.inject(WorkingCopyStore);
    store.id.set('abc');
    store.info.set(info(2));
  });

  afterEach(() => vi.useRealTimers());

  const reads: [string, (s: WorkingCopySource, i: ImportSource) => Observable<unknown>, string][] = [
    ['the metadata groups', (s) => s.metadataGroups(), 'working-copies/abc/metadata'],
    ['a metadata page', (s) => s.metadataPage('general', 0, 50), 'working-copies/abc/metadata/general'],
    ['a table page', (s) => s.tablePage('sml', { page: 0, size: 50 }), 'working-copies/abc/tables/sml'],
    ['the duplicates of a column', (s) => s.duplicateColumns('SMH'), 'working-copies/abc/columns/SMH/duplicates'],
    ['the kinds of import', (_s, i) => i.importKinds(), 'working-copies/abc/imports/kinds'],
  ];

  it.each(reads)('%s: 30 s, asked again after 1 s and 3 s, then the failure is passed on', (_name, read, path) => {
    const errors: unknown[] = [];
    read(source, imports).subscribe({ error: (e) => errors.push(e) });
    const at = (r: { url: string }) => r.url.endsWith(path);
    const first = http.expectOne(at);
    expect(first.request.context.get(REQUEST_TIMEOUT)).toBe(EDITOR_TIMEOUT_MS);
    vi.advanceTimersByTime(EDITOR_TIMEOUT_MS);
    vi.advanceTimersByTime(READ_RETRY_DELAYS_MS[0]);
    http.expectOne(at).error(new ProgressEvent('error'));
    vi.advanceTimersByTime(READ_RETRY_DELAYS_MS[1]);
    http.expectOne(at).error(new ProgressEvent('error'));
    expect(errors.length).toBe(1);
    expect((errors[0] as { status: number }).status).toBe(0);
    vi.advanceTimersByTime(60_000);
    http.expectNone(at);
  });

  it('a page asked again carries the revision known when it was asked again', () => {
    const pages: { readAt: number }[] = [];
    source.tablePage('sml', { page: 0, size: 50 }).subscribe((p) => pages.push(p as { readAt: number }));
    http.expectOne((r) => r.url.endsWith('working-copies/abc/tables/sml')).error(new ProgressEvent('error'));
    TestBed.inject(WorkingCopyStore).info.set(info(3));
    vi.advanceTimersByTime(READ_RETRY_DELAYS_MS[0]);
    http.expectOne((r) => r.url.endsWith('working-copies/abc/tables/sml')).flush({ rows: [] });
    expect(pages.map((p) => p.readAt)).toEqual([3]);
  });

  it('still missing: 120 s, not asked again', () => {
    const errors: unknown[] = [];
    imports.stillMissing().subscribe({ error: (e) => errors.push(e) });
    const sent = http.expectOne((r) => r.url.endsWith('working-copies/abc/still-missing'));
    expect(sent.request.context.get(REQUEST_TIMEOUT)).toBe(ENGINE_TIMEOUT_MS);
    vi.advanceTimersByTime(ENGINE_TIMEOUT_MS - 1);
    expect(errors).toEqual([]);
    vi.advanceTimersByTime(1);
    expect(errors).toEqual([{ status: 0, detail: null }]);
    vi.advanceTimersByTime(60_000);
    http.expectNone((r) => r.url.endsWith('working-copies/abc/still-missing'));
  });
});
