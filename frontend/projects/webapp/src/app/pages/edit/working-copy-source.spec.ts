import { Component } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DocumentSource, ImportSource, MetadataViewComponent, StillMissingView } from '@lifs-tools/mztabm-editor';

import { AppInfoStore } from '../../core/app-info.store';
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
