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

  it('an edit of a page read before a line move, made after the move was answered, names the old revision', () => {
    source.applyEdits([{ op: 'DeleteLine', line: 3 }], { readAt: 0 }).subscribe();
    onePost().flush(info(1));
    // the views have not read their pages again yet: line 6 is a number of revision 0
    source.applyEdits([{ op: 'DeleteLine', line: 6 }], { readAt: 0 }).subscribe({ error: () => undefined });
    const second = onePost();
    expect(revisionOf(second)).toBe(0);
    conflict(second);
    reread().flush(info(1));
    expect(notices).toEqual([LINES_MOVED_NOTICE]);
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

  describe('pages are stamped with the revision they were read at', () => {
    const metadataGet = (group: string) =>
      http.expectOne((r) => r.method === 'GET' && r.url.endsWith(`working-copies/abc/metadata/${group}`));
    const pageOf = (group: string, line: number) => ({
      group,
      page: 0,
      size: 50,
      total: 1,
      entries: [{ lineNumber: line, key: `${group}[1]`, value: 'v' }],
    });

    async function read(group: string, line: number) {
      let got: { readAt?: number | null } | undefined;
      source.metadataPage(group, 0, 50).subscribe((p) => (got = p));
      metadataGet(group).flush(pageOf(group, line));
      return got!;
    }

    it('stamps metadata and table pages with the revision of the moment they were asked for', async () => {
      const a = await read('sample', 5);
      expect(a.readAt).toBe(0);
      store.info.set(info(3));
      let table: { readAt?: number | null } | undefined;
      source.tablePage('sml', { page: 0, size: 50 }).subscribe((p) => (table = p));
      http.expectOne((r) => r.url.endsWith('working-copies/abc/tables/sml')).flush({ section: 'SML', page: 0, rows: [] });
      expect(table!.readAt).toBe(3);
    });

    it('× in group A, then × in group B before B was read again: B is refused, one line is removed', async () => {
      const a = await read('sample', 5);
      const b = await read('assay', 9);
      source.applyEdits([{ op: 'DeleteLine', line: 5 }], { readAt: a.readAt }).subscribe();
      onePost().flush(info(1));
      expect(changed).toBe(1);

      // in the gap: B's page still has the numbers of revision 0
      const errors: unknown[] = [];
      source.applyEdits([{ op: 'DeleteLine', line: 9 }], { readAt: b.readAt }).subscribe({ error: (e) => errors.push(e) });
      const second = onePost();
      expect(revisionOf(second)).toBe(0);
      conflict(second);
      reread().flush(info(1));
      expect(errors).toEqual([{ status: 409, detail: null }]);
      expect(changed).toBe(2);
      expect(store.info()?.revision).toBe(1);
      http.verify();
    });

    it('Enter in the value editor of another group in the gap is refused the same way', async () => {
      const a = await read('sample', 5);
      const b = await read('assay', 9);
      source.applyEdits([{ op: 'DeleteLine', line: 5 }], { readAt: a.readAt }).subscribe();
      onePost().flush(info(1));
      source.applyEdits([{ op: 'SetMetadataValue', line: 9, value: 'x' }], { readAt: b.readAt }).subscribe({
        error: () => undefined,
      });
      const second = onePost();
      expect(revisionOf(second)).toBe(0);
      conflict(second);
      reread().flush(info(1));
    });

    it('after undo or redo, an edit of a page read before it is refused too', async () => {
      const b = await read('assay', 9);
      store.undo();
      http.expectOne((r) => r.method === 'POST' && r.url.includes('working-copies/abc/undo')).flush(info(1));
      source.applyEdits([{ op: 'SetMetadataValue', line: 9, value: 'x' }], { readAt: b.readAt }).subscribe({
        error: () => undefined,
      });
      expect(revisionOf(onePost())).toBe(0);
    });

    it('after a conflict reload, an edit of a page read before it is refused too', async () => {
      const b = await read('assay', 9);
      source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }], { readAt: 0 }).subscribe({ error: () => undefined });
      conflict(onePost());
      reread().flush(info(4));
      source.applyEdits([{ op: 'SetMetadataValue', line: 9, value: 'x' }], { readAt: b.readAt }).subscribe({
        error: () => undefined,
      });
      expect(revisionOf(onePost())).toBe(0);
    });

    it('edits that keep the line numbers, of pages read after the reload, chain without a conflict', async () => {
      source.applyEdits([{ op: 'DeleteLine', line: 5 }], { readAt: 0 }).subscribe();
      onePost().flush(info(1));
      const b = await read('assay', 8);
      expect(b.readAt).toBe(1);
      source.applyEdits([{ op: 'SetMetadataValue', line: 8, value: 'x' }], { readAt: b.readAt }).subscribe();
      source.applyEdits([{ op: 'SetMetadataValue', line: 8, value: 'y' }], { readAt: b.readAt }).subscribe();
      const first = onePost();
      expect(revisionOf(first)).toBe(1);
      first.flush(info(2));
      const second = onePost();
      expect(revisionOf(second)).toBe(2);
      second.flush(info(3));
      expect(notices).toEqual([]);
      // an edit that keeps the line numbers does not make the pages read before it stale
      source.applyEdits([{ op: 'SetMetadataValue', line: 8, value: 'z' }], { readAt: b.readAt }).subscribe();
      expect(revisionOf(onePost())).toBe(3);
    });
  });
});
