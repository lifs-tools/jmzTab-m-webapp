import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DocumentSource } from '@lifs-tools/mztabm-editor';

import { WorkingCopyInfo } from '../../api/models/working-copy-info';
import { AppInfoStore } from '../../core/app-info.store';
import { WorkingCopySource } from './working-copy-source';
import {
  BUSY_RETRIES,
  CONFLICT_NOTICE,
  EditOps,
  IDLE_MS,
  LINES_MOVED_NOTICE,
  WorkingCopyStore,
} from './working-copy.store';

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

/** A check refused because the server's queue of checks is full: its message is shown, and it is asked again. */
describe('WorkingCopyStore: a busy check (503)', () => {
  let http: HttpTestingController;
  let store: WorkingCopyStore;
  const FULL = 'Too many files are being checked. Please try again in a moment.';

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        WorkingCopyStore,
        { provide: AppInfoStore, useValue: { load: () => undefined, info: () => undefined } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(WorkingCopyStore);
    store.id.set('abc');
    store.info.set(info(0));
  });

  afterEach(() => vi.useRealTimers());

  const posts = () => http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations'));
  const refuse = (retryAfter: string | null, message = FULL) =>
    posts()[0].flush(
      { message },
      { status: 503, statusText: 'Service Unavailable', headers: retryAfter === null ? {} : { 'Retry-After': retryAfter } },
    );

  it('shows the 503 message instead of the general one', () => {
    store.validate(false);
    refuse('2');
    expect(store.checkFailed()).toBe(true);
    expect(store.checkFailure()).toBe(FULL);
  });

  it('asks again after Retry-After, not before', () => {
    store.validate(false);
    refuse('2');
    vi.advanceTimersByTime(1999);
    expect(posts().length).toBe(0);
    vi.advanceTimersByTime(1);
    const again = posts();
    expect(again.length).toBe(1);
    expect(again[0].request.params.get('semantic')).toBe('false');
    again[0].flush(info(0, { validation: { status: 'DONE', revision: 0 } as WorkingCopyInfo['validation'] }));
    expect(store.checkFailed()).toBe(false);
    expect(store.checkFailure()).toBeNull();
  });

  it('keeps the kind of check (CV rules) when it asks again', () => {
    store.validate(true);
    refuse('1');
    vi.advanceTimersByTime(1000);
    const [again] = posts();
    expect(again.request.params.get('semantic')).toBe('true');
    again.flush(info(0));
  });

  it('gives up after a few busy answers; Retry (a new request) starts again', () => {
    store.validate(false);
    for (let i = 0; i < BUSY_RETRIES; i++) {
      refuse('1');
      vi.advanceTimersByTime(1000);
    }
    refuse('1');
    vi.advanceTimersByTime(120_000);
    expect(posts().length).toBe(0);
    expect(store.checkFailed()).toBe(true);
    store.validate(false);
    const fresh = posts();
    expect(fresh.length).toBe(1);
    fresh[0].flush(info(0));
    http.verify();
  });

  it('does not ask again by itself for another failure, and keeps the general text', () => {
    store.validate(false);
    posts()[0].flush('oops', { status: 500, statusText: 'Server Error' });
    vi.advanceTimersByTime(120_000);
    expect(posts().length).toBe(0);
    expect(store.checkFailed()).toBe(true);
    expect(store.checkFailure()).toBeNull();
  });

  it('a 503 without Retry-After is asked again after the default wait', () => {
    store.validate(false);
    refuse(null);
    vi.advanceTimersByTime(4999);
    expect(posts().length).toBe(0);
    vi.advanceTimersByTime(1);
    const again = posts();
    expect(again.length).toBe(1);
    again[0].flush(info(0));
  });
});

/**
 * A copy is opened with the check it had: one that is not of the current text, and not running (the page was
 * left during the idle wait, the check failed, the server restarted: runs live in memory), is checked again,
 * structurally only, whatever the kind of the last one: the CV rules are checked on request only. One that is
 * running is polled; one that is current is left alone.
 */
describe('WorkingCopyStore: a reopened copy', () => {
  let http: HttpTestingController;
  let store: WorkingCopyStore;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        WorkingCopyStore,
        { provide: AppInfoStore, useValue: { load: () => undefined, info: () => undefined } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(WorkingCopyStore);
  });

  afterEach(() => vi.useRealTimers());

  type V = NonNullable<WorkingCopyInfo['validation']>;
  const run = (over: Partial<V>) => ({ status: 'DONE', revision: 3, stale: false, semantic: false, ...over }) as V;
  const posts = () => http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations'));
  const reopen = (validation: V) => {
    store.open('abc');
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies')).flush(info(3, { validation }));
  };

  it('requests a structural run when the last is stale and not running, even after a CV check', () => {
    reopen(run({ revision: 2, stale: true, semantic: true }));
    const [req] = posts();
    expect(req.request.params.get('semantic')).toBe('false');
    req.flush(info(3, { validation: run({ status: 'RUNNING', revision: 3 }) }));
  });

  it('requests a structural run after a structural one', () => {
    reopen(run({ revision: 2, stale: true, semantic: false }));
    expect(posts()[0].request.params.get('semantic')).toBe('false');
  });

  it('checks again a failed check of an older revision (the server restarted or the check failed)', () => {
    reopen(run({ status: 'FAILED', revision: 2, stale: true, semantic: true }));
    expect(posts().length).toBe(1);
  });

  it('requests nothing for a failed check of the current text: the failure is shown, with Retry', () => {
    reopen(run({ status: 'FAILED', revision: 3, stale: false, failureReason: 'boom' }));
    expect(posts().length).toBe(0);
    expect(store.validating()).toBe(false);
  });

  it('polls a run that is RUNNING, and requests nothing', () => {
    reopen(run({ status: 'RUNNING', revision: 3, stale: false }));
    expect(posts().length).toBe(0);
    vi.advanceTimersByTime(1000);
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc')).flush(info(3, { validation: run({}) }));
  });

  it('requests nothing when the last check is current', () => {
    reopen(run({}));
    expect(posts().length).toBe(0);
    expect(store.current()).toBe(true);
  });

  it('does not loop: a request that fails after a reopen is not repeated by itself', () => {
    reopen(run({ revision: 2, stale: true }));
    posts()[0].flush({ message: 'x' }, { status: 500, statusText: 'Server Error' });
    vi.advanceTimersByTime(120_000);
    expect(posts().length).toBe(0);
    expect(store.checkFailed()).toBe(true);
  });

  it('a reopen whose request meets a busy server shows its message and asks again after Retry-After, a few times', () => {
    reopen(run({ revision: 2, stale: true, semantic: true }));
    let pending = posts()[0];
    const refuse = () =>
      pending.flush(
        { message: 'Too many files are being checked.' },
        { status: 503, statusText: 'Service Unavailable', headers: { 'Retry-After': '2' } },
      );
    refuse();
    expect(store.checkFailure()).toBe('Too many files are being checked.');
    for (let i = 0; i < BUSY_RETRIES; i++) {
      vi.advanceTimersByTime(2000);
      [pending] = posts();
      expect(pending.request.params.get('semantic')).toBe('false');
      refuse();
    }
    vi.advanceTimersByTime(120_000);
    expect(posts().length).toBe(0);
    expect(store.checkFailed()).toBe(true);
  });
});

/**
 * CV rules are checked on request only; the structural check after each change runs by itself. A structural
 * request never replaces a CV check that was asked for and not yet accepted: it is sent as a CV check instead.
 */
describe('WorkingCopyStore: a CV check is not replaced by a structural one', () => {
  let http: HttpTestingController;
  let store: WorkingCopyStore;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        WorkingCopyStore,
        { provide: AppInfoStore, useValue: { load: () => undefined, info: () => undefined } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(WorkingCopyStore);
    store.id.set('abc');
    store.info.set(info(0));
  });

  afterEach(() => vi.useRealTimers());

  const posts = () => http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations'));
  const kinds = (sent: TestRequest[]) => sent.map((r) => r.request.params.get('semantic'));
  const running = (revision: number, semantic: boolean) =>
    info(revision, { validation: { status: 'RUNNING', revision, semantic } as WorkingCopyInfo['validation'] });
  /** An accepted edit: the structural check is due once the user is idle. */
  const edited = (revision: number) => {
    store.edit([{ op: 'SetMetadataValue', line: 3, value: `v${revision}` }] as EditOps).subscribe();
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/edits')).flush(info(revision));
  };
  const busy = (seconds: string) =>
    posts()[0].flush(
      { message: 'Too many files are being checked.' },
      { status: 503, statusText: 'Service Unavailable', headers: { 'Retry-After': seconds } },
    );

  it('"Check CV rules" during the idle wait cancels the structural check and checks the CV rules', () => {
    edited(1);
    vi.advanceTimersByTime(500);
    store.validate(true);
    const sent = posts();
    expect(kinds(sent)).toEqual(['true']);
    sent[0].flush(running(1, true));
    vi.advanceTimersByTime(IDLE_MS * 2);
    expect(posts()).toEqual([]);
  });

  it('an idle structural check while a refused CV check waits for its retry is sent as the CV check', () => {
    store.validate(true);
    busy('10');
    edited(1);
    vi.advanceTimersByTime(IDLE_MS);
    const sent = posts();
    expect(kinds(sent)).toEqual(['true']);
    sent[0].flush(running(1, true));
    // the CV check was accepted: its retry is not made as well
    vi.advanceTimersByTime(20_000);
    expect(posts()).toEqual([]);
    expect(store.checkFailed()).toBe(false);
  });

  it('Retry after a refused CV check checks the CV rules', () => {
    store.validate(true);
    posts()[0].flush('oops', { status: 500, statusText: 'Server Error' });
    expect(store.checkFailed()).toBe(true);
    // the page's Retry
    store.validate(false);
    const sent = posts();
    expect(kinds(sent)).toEqual(['true']);
    sent[0].flush(running(0, true));
  });

  it('once a CV check is accepted, the check after the next change is structural again', () => {
    store.validate(true);
    posts()[0].flush(running(0, true));
    edited(1);
    vi.advanceTimersByTime(IDLE_MS);
    const sent = posts();
    expect(kinds(sent)).toEqual(['false']);
    sent[0].flush(running(1, false));
  });
});
