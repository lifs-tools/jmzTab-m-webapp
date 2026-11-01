import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DocumentSource } from '@lifs-tools/mztabm-editor';

import { WorkingCopyInfo } from '../../api/models/working-copy-info';
import { AppInfoStore } from '../../core/app-info.store';
import { WorkingCopySource } from './working-copy-source';
import { CONFLICT_NOTICE, EditOps, LINES_MOVED_NOTICE, WorkingCopyStore } from './working-copy.store';

const info = (revision: number, over: Partial<WorkingCopyInfo> = {}): WorkingCopyInfo =>
  ({ id: 'abc', fileName: 'x.mzTab', revision, mzTabVersion: '2.0.0-M', lineCount: 9, canUndo: true, canRedo: true, ...over }) as WorkingCopyInfo;

/**
 * Edits queued behind an edit that moves lines name the revision of the moment they were made: their line
 * numbers are those of the text before the move, so the server refuses them (409) instead of applying them to
 * whatever line now has that number.
 */
describe('WorkingCopyStore: edits behind a line move', () => {
  let http: HttpTestingController;
  let store: WorkingCopyStore;
  let source: WorkingCopySource;
  let notices: string[];
  let changed: number;

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
    store.info.set(info(0));
    notices = [];
    changed = 0;
    store.notices.subscribe((n) => notices.push(n));
    store.changed.subscribe(() => changed++);
  });

  const posts = () => http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/edits'));
  const onePost = (): TestRequest => {
    const found = posts();
    expect(found.length).toBe(1);
    return found[0];
  };
  const revisionOf = (r: TestRequest) => (r.request.body as { revision: number }).revision;
  const opsOf = (r: TestRequest) => (r.request.body as { operations: EditOps }).operations;
  const conflict = (r: TestRequest) => r.flush({ message: 'stale' }, { status: 409, statusText: 'Conflict' });
  const reread = () => http.expectOne((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc'));

  it('two quick removals in different groups: the second is refused, the views reload, one line is removed', () => {
    const errors: unknown[] = [];
    source.applyEdits([{ op: 'DeleteLine', line: 3 }]).subscribe();
    source.applyEdits([{ op: 'DeleteLine', line: 7 }]).subscribe({ error: (e) => errors.push(e) });

    const first = onePost();
    expect(revisionOf(first)).toBe(0);
    first.flush(info(1));
    expect(changed).toBe(1);

    const second = onePost();
    expect(opsOf(second)).toEqual([{ op: 'DeleteLine', line: 7 }]);
    // the number 7 is of revision 0: the server must not apply it to revision 1
    expect(revisionOf(second)).toBe(0);
    conflict(second);
    reread().flush(info(1));

    expect(errors).toEqual([{ status: 409, detail: null }]);
    expect(changed).toBe(2);
    expect(notices).toEqual([LINES_MOVED_NOTICE]);
    expect(store.info()?.revision).toBe(1);
    http.verify();
  });

  it('an edit that keeps the line numbers, behind a line move, is refused as well', () => {
    source.applyEdits([{ op: 'DeleteLine', line: 3 }]).subscribe();
    source.applyEdits([{ op: 'SetMetadataValue', line: 7, value: 'x' }]).subscribe({ error: () => undefined });
    onePost().flush(info(1));
    expect(revisionOf(onePost())).toBe(0);
  });

  it.each([
    ['InsertLine', { op: 'InsertLine', before: 3, text: 'COM\tx' }],
    ['MoveRow', { op: 'MoveRow', line: 3, before: 5 }],
    ['JoinWithPrevious', { op: 'JoinWithPrevious', line: 3 }],
  ])('pins the edit behind a %s', (_name, op) => {
    source.applyEdits([op as never]).subscribe();
    source.applyEdits([{ op: 'SetCell', line: 8, column: 2, value: 'x' } as never]).subscribe({ error: () => undefined });
    onePost().flush(info(1));
    expect(revisionOf(onePost())).toBe(0);
  });

  it('pins the edit behind a quick fix whose batch moves lines', () => {
    store
      .applyFix({ fixId: 'remove-mismatched-field', batch: { revision: 0, operations: [{ op: 'DeleteLine', line: 4 }] } } as never)
      .subscribe();
    store.edit([{ op: 'SetMetadataValue', line: 7, value: 'x' }] as EditOps).subscribe({ error: () => undefined });
    onePost().flush(info(1));
    expect(revisionOf(onePost())).toBe(0);
  });

  it('pins the edit behind an undo or a redo: what they bring back or take away may move lines', () => {
    store.undo();
    store.edit([{ op: 'SetMetadataValue', line: 7, value: 'x' }] as EditOps).subscribe({ error: () => undefined });
    http.expectOne((r) => r.method === 'POST' && r.url.includes('working-copies/abc/undo')).flush(info(1));
    expect(revisionOf(onePost())).toBe(0);
  });

  it('keeps chaining edits behind edits that keep the line numbers', () => {
    source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe();
    source.applyEdits([{ op: 'SetMetadataValue', line: 7, value: 'b' }]).subscribe();
    onePost().flush(info(1));
    const second = onePost();
    expect(revisionOf(second)).toBe(1);
    second.flush(info(2));
    expect(store.info()?.revision).toBe(2);
    expect(notices).toEqual([]);
  });

  it('a line move behind an edit that keeps the line numbers takes the newer revision', () => {
    source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe();
    source.applyEdits([{ op: 'DeleteLine', line: 7 }]).subscribe();
    onePost().flush(info(1));
    expect(revisionOf(onePost())).toBe(1);
  });

  it('an edit made after the line move was answered takes the newer revision', () => {
    source.applyEdits([{ op: 'DeleteLine', line: 3 }]).subscribe();
    onePost().flush(info(1));
    source.applyEdits([{ op: 'DeleteLine', line: 6 }]).subscribe();
    expect(revisionOf(onePost())).toBe(1);
  });

  it('a refused line move leaves the edit behind it valid: it names the revision the server still has', () => {
    source.applyEdits([{ op: 'DeleteLine', line: 3 }]).subscribe({ error: () => undefined });
    source.applyEdits([{ op: 'DeleteLine', line: 7 }]).subscribe();
    onePost().flush({ message: 'no' }, { status: 400, statusText: 'Bad Request' });
    const second = onePost();
    expect(revisionOf(second)).toBe(0);
    second.flush(info(1));
    expect(store.info()?.revision).toBe(1);
  });

  it('a conflict of an edit that was not pinned still says the file changed in another tab', () => {
    source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe({ error: () => undefined });
    conflict(onePost());
    reread().flush(info(4));
    expect(notices).toEqual([CONFLICT_NOTICE]);
  });
});
