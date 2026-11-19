import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DocEdit, DocumentSource } from '@lifs-tools/mztabm-editor';

import { ImportPreview } from '../../api/models/import-preview';
import { ValidationMessage } from '../../api/models/validation-message';
import { WorkingCopyInfo } from '../../api/models/working-copy-info';
import { WorkingCopyValidation } from '../../api/models/working-copy-validation';
import { AppInfoStore } from '../../core/app-info.store';
import {
  EDITOR_TIMEOUT_MS,
  ENGINE_TIMEOUT_MS,
  READ_RETRY_DELAYS_MS,
  REQUEST_TIMEOUT,
  isTimeout,
  requestTimeoutInterceptor,
} from '../../core/request-timeout.interceptor';
import { WorkingCopySource } from './working-copy-source';
import {
  BUSY_RETRIES,
  CONFLICT_NOTICE,
  COPY_REPLACED_NOTICE,
  EditOps,
  IDLE_MS,
  LAYOUT_CHANGED_NOTICE,
  MAYBE_SAVED_NOTICE,
  POLL_MS,
  SAVE_FAILED,
  WorkingCopyStore,
} from './working-copy.store';

const info = (revision: number, over: Partial<WorkingCopyInfo> = {}): WorkingCopyInfo =>
  ({ id: 'abc', fileName: 'x.mzTab', revision, mzTabVersion: '2.0.0-M', lineCount: 9, canUndo: true, canRedo: true, ...over }) as WorkingCopyInfo;

/**
 * Edits queued behind an edit that moves lines name the revision of the moment they were made: their line
 * numbers are those of the text before the move, so the server refuses them (409) instead of applying them to
 * whatever line now has that number.
 */
describe('WorkingCopyStore: edits behind a layout change', () => {
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
    expect(notices).toEqual([LAYOUT_CHANGED_NOTICE]);
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
    ['InsertLines', { op: 'InsertLines', before: 3, lines: ['COM\tx', 'COM\ty'] }],
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
    expect(notices).toEqual([LAYOUT_CHANGED_NOTICE]);
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

  describe('the revision of a message', () => {
    const structural = { category: 'format', message_type: 'error', line_number: 24 } as ValidationMessage;
    const cv = { category: 'cross_check', message_type: 'error', line_number: 24 } as ValidationMessage;
    const withValidation = (over: Partial<WorkingCopyValidation>, cvCheckedRevision?: number) =>
      store.info.set(
        info(5, {
          cvCheckedRevision,
          validation: { status: 'DONE', revision: 5, stale: false, parsed: true, semantic: false, ...over } as WorkingCopyValidation,
        }),
      );

    it('is the revision of the messages shown, which is not the revision of a run that is running', () => {
      withValidation({ status: 'RUNNING', revision: 5, messagesRevision: 3 });
      expect(store.messageRevision(structural)).toBe(3);
    });

    it('is the revision of the last CV check for a CV message', () => {
      withValidation({ revision: 5, messagesRevision: 5 }, 2);
      expect(store.messageRevision(structural)).toBe(5);
      expect(store.messageRevision(cv)).toBe(2);
    });

    it('falls back to the revision of the run, and to the current one without a run', () => {
      withValidation({ revision: 4 });
      expect(store.messageRevision(structural)).toBe(4);
      expect(store.messageRevision(cv)).toBe(4);
      store.info.set(info(5));
      expect(store.messageRevision(structural)).toBe(5);
    });

    it('knows a layout change of an earlier session from the first answer on', () => {
      store.info.set(info(0));
      expect(store.linesMovedSince(3)).toBe(false);
      source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe();
      onePost().flush(info(5, { layoutRevision: 4 }));
      expect(store.linesMovedSince(3)).toBe(true);
      expect(store.linesMovedSince(4)).toBe(false);
    });

    it('a cell edit after the run of a message leaves the lines of the message valid', () => {
      store.info.set(info(0, { validation: { status: 'DONE', revision: 0, messagesRevision: 0 } as WorkingCopyValidation }));
      source.applyEdits([{ op: 'SetCell', line: 5, cell: 2, text: 'x' }]).subscribe();
      onePost().flush(info(1, { validation: { status: 'DONE', revision: 0, messagesRevision: 0 } as WorkingCopyValidation }));
      expect(store.linesMovedSince(store.messageRevision(structural))).toBe(false);
    });

    it('says whether lines moved since it: after a line move was answered', () => {
      expect(store.linesMovedSince(0)).toBe(false);
      source.applyEdits([{ op: 'DeleteLine', line: 3 }]).subscribe();
      onePost().flush(info(1));
      expect(store.linesMovedSince(0)).toBe(true);
      expect(store.linesMovedSince(1)).toBe(false);
    });
  });

  it('a conflict of an edit that was not pinned still says the file changed in another tab', () => {
    source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe({ error: () => undefined });
    conflict(onePost());
    reread().flush(info(4));
    expect(notices).toEqual([CONFLICT_NOTICE]);
  });

  describe('the copy\'s generation', () => {
    const generation = '3f2b8f0e-5d0c-4c0e-9a55-0d6a3f6f3a11';
    beforeEach(() => store.info.set(info(0, { generation })));
    const request = (suffix: string) =>
      http.expectOne((r) => r.method === 'POST' && r.url.endsWith(`working-copies/abc/${suffix}`));

    it('is sent with an edit', () => {
      source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe();
      expect((onePost().request.body as { generation: string }).generation).toBe(generation);
    });

    it('is sent with an undo and a redo', () => {
      store.undo();
      const undo = request('undo');
      expect(undo.request.params.get('generation')).toBe(generation);
      undo.flush(info(1, { generation }));
      store.redo();
      expect(request('redo').request.params.get('generation')).toBe(generation);
    });

    it('a copy created again is told from a change in another tab', () => {
      source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe({ error: () => undefined });
      onePost().flush({ error: 'copy_replaced', message: 'again' }, { status: 409, statusText: 'Conflict' });
      reread().flush(info(0, { generation: '9c4e1d52-7a0b-4d38-8f21-6b1c2d3e4f50' }));
      expect(notices).toEqual([COPY_REPLACED_NOTICE]);
      expect(changed).toBe(1);
    });

    describe('a copy created again starts anew', () => {
      const again = '9c4e1d52-7a0b-4d38-8f21-6b1c2d3e4f50';
      /** The old copy's lines moved at revision 5; its CV rules were checked here and its report is ready. */
      const oldCopyMovedAt5 = () => {
        store.info.set(info(4, { generation }));
        source.applyEdits([{ op: 'DeleteLine', line: 3 }]).subscribe();
        onePost().flush(info(5, { generation, layoutRevision: 5 }));
        store.validate(true);
        http
          .expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations'))
          .flush(info(5, { generation, layoutRevision: 5, cvCheckedRevision: 5, validation: { status: 'DONE', revision: 5, semantic: true } as WorkingCopyValidation }));
        expect(store.reportReady()).toBe(true);
        expect(store.linesMovedSince(4)).toBe(true);
        notices.length = 0;
        changed = 0;
      };

      it('after copy_replaced, edits of a page read at the new revision are sent and accepted', () => {
        oldCopyMovedAt5();
        source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe({ error: () => undefined });
        onePost().flush({ error: 'copy_replaced', message: 'again' }, { status: 409, statusText: 'Conflict' });
        reread().flush(info(0, { generation: again, layoutRevision: 0 }));
        expect(notices).toEqual([COPY_REPLACED_NOTICE]);
        expect(changed).toBe(1);
        // what the old copy knew is gone: its report, its CV check, its layout changes
        expect(store.reportReady()).toBe(false);
        expect(store.linesMovedSince(0)).toBe(false);

        // two cell edits of a page read at revision 0 of the new copy: neither is refused as stale
        source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'b' }], { readAt: 0 }).subscribe();
        const first = onePost();
        expect(revisionOf(first)).toBe(0);
        expect((first.request.body as { generation: string }).generation).toBe(again);
        first.flush(info(1, { generation: again }));
        source.applyEdits([{ op: 'SetMetadataValue', line: 4, value: 'c' }], { readAt: 0 }).subscribe();
        const second = onePost();
        expect(revisionOf(second)).toBe(1);
        second.flush(info(2, { generation: again }));
        expect(notices).toEqual([COPY_REPLACED_NOTICE]);
      });

      it('a poll that finds a new generation, at a higher revision, resets the state and says so once', () => {
        vi.useFakeTimers();
        try {
          oldCopyMovedAt5();
          store.validate(false);
          http
            .expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations'))
            .flush(info(5, { generation, layoutRevision: 5, validation: { status: 'RUNNING', revision: 5 } as WorkingCopyValidation }));
          vi.advanceTimersByTime(POLL_MS);
          reread().flush(info(7, { generation: again, layoutRevision: 0, validation: { status: 'RUNNING', revision: 7 } as WorkingCopyValidation }));
          expect(notices).toEqual([COPY_REPLACED_NOTICE]);
          expect(changed).toBe(1);
          expect(store.info()?.generation).toBe(again);
          expect(store.reportReady()).toBe(false);
          expect(store.linesMovedSince(0)).toBe(false);
          expect(store.cvStatus()).toBeNull();
          // a late answer of the old copy is not taken back
          store.reload();
          reread().flush(info(6, { generation, layoutRevision: 5 }));
          expect(store.info()?.generation).toBe(again);
          // the poll goes on, and its next answer is not announced again
          vi.advanceTimersByTime(POLL_MS);
          reread().flush(info(7, { generation: again, layoutRevision: 0, validation: { status: 'DONE', revision: 7 } as WorkingCopyValidation }));
          expect(notices).toEqual([COPY_REPLACED_NOTICE]);
          expect(changed).toBe(1);
          expect(store.reportReady()).toBe(true);
        } finally {
          vi.useRealTimers();
        }
      });

      it('a copy_replaced refusal after a poll already took the new copy says so only once', () => {
        oldCopyMovedAt5();
        source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe({ error: () => undefined });
        const edit = onePost();
        // the poll answers first, with the new copy
        store.reload();
        reread().flush(info(0, { generation: again }));
        expect(notices).toEqual([COPY_REPLACED_NOTICE]);
        expect(changed).toBe(1);
        // the edit, sent with the old generation, is refused afterwards
        edit.flush({ error: 'copy_replaced', message: 'again' }, { status: 409, statusText: 'Conflict' });
        reread().flush(info(0, { generation: again }));
        expect(notices).toEqual([COPY_REPLACED_NOTICE]);
        expect(changed).toBe(1);
      });

      it('a late answer about the replaced copy, read after a refusal, changes nothing', () => {
        oldCopyMovedAt5();
        store.reload();
        reread().flush(info(0, { generation: again }));
        notices.length = 0;
        changed = 0;
        source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }], { readAt: 0 }).subscribe({ error: () => undefined });
        conflict(onePost());
        // the re-read is answered from the old copy, at its higher revision
        reread().flush(info(6, { generation, layoutRevision: 6 }));
        expect(notices).toEqual([]);
        expect(changed).toBe(0);
        expect(store.info()?.generation).toBe(again);
        expect(store.linesMovedSince(0)).toBe(false);
        // the next edit of a page read at revision 0 is sent, named at revision 0
        source.applyEdits([{ op: 'SetMetadataValue', line: 4, value: 'b' }], { readAt: 0 }).subscribe();
        const next = onePost();
        expect(revisionOf(next)).toBe(0);
        next.flush(info(1, { generation: again }));
      });

      describe('aReplacedCopyWithoutARunIsCheckedAtOnce', () => {
        beforeEach(() => vi.useFakeTimers());
        afterEach(() => vi.useRealTimers());
        const checks = () => http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations'));
        const gets = () => http.match((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc'));
        const run = (status: 'RUNNING' | 'DONE', revision: number) =>
          ({ status, revision, stale: false, semantic: false }) as WorkingCopyValidation;

        it('found through a refusal (copy_replaced): one structural check, its run polled, nothing asked again', () => {
          store.info.set(info(4, { generation }));
          source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe({ error: () => undefined });
          onePost().flush({ error: 'copy_replaced', message: 'again' }, { status: 409, statusText: 'Conflict' });
          reread().flush(info(0, { generation: again }));
          expect(notices).toEqual([COPY_REPLACED_NOTICE]);
          const [check] = checks();
          expect(check.request.params.get('semantic')).toBe('false');
          check.flush(info(0, { generation: again, validation: run('RUNNING', 0) }));
          expect(checks()).toEqual([]);
          // the check's run is followed
          vi.advanceTimersByTime(POLL_MS);
          reread().flush(info(0, { generation: again, validation: run('DONE', 0) }));
          vi.advanceTimersByTime(POLL_MS * 5);
          expect(gets()).toEqual([]);
          expect(checks()).toEqual([]);
          expect(notices).toEqual([COPY_REPLACED_NOTICE]);
          expect(store.reportReady()).toBe(true);
          http.verify();
        });

        it('found through the poll: one check, and the poll starts again for its run', () => {
          store.info.set(info(4, { generation }));
          store.validate(false);
          checks()[0].flush(info(4, { generation, validation: run('RUNNING', 4) }));
          vi.advanceTimersByTime(POLL_MS);
          // the copy was created again: its run is of revision 0, not of the revision it is at
          reread().flush(info(2, { generation: again, validation: { ...run('DONE', 0), stale: true } }));
          expect(notices).toEqual([COPY_REPLACED_NOTICE]);
          const [check] = checks();
          expect(check.request.params.get('semantic')).toBe('false');
          check.flush(info(2, { generation: again, validation: run('RUNNING', 2) }));
          vi.advanceTimersByTime(POLL_MS);
          reread().flush(info(2, { generation: again, validation: run('DONE', 2) }));
          vi.advanceTimersByTime(POLL_MS * 5);
          expect(gets()).toEqual([]);
          expect(checks()).toEqual([]);
          expect(notices).toEqual([COPY_REPLACED_NOTICE]);
          http.verify();
        });

        it('found by the answer to a check: that check is of the new copy, and is not asked again', () => {
          store.info.set(info(4, { generation }));
          store.validate(false);
          checks()[0].flush(info(0, { generation: again, validation: { ...run('DONE', 0), stale: true } }));
          expect(notices).toEqual([COPY_REPLACED_NOTICE]);
          expect(checks()).toEqual([]);
          http.verify();
        });

        it('an idle wait after an edit of the old copy asks for no second check', () => {
          store.info.set(info(4, { generation }));
          source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe();
          onePost().flush(info(5, { generation }));
          store.reload();
          reread().flush(info(0, { generation: again }));
          expect(checks().length).toBe(1);
          vi.advanceTimersByTime(IDLE_MS * 2);
          expect(checks()).toEqual([]);
          http.verify();
        });

        it('a replaced copy whose current revision is running is polled, not checked again', () => {
          store.info.set(info(4, { generation }));
          store.reload();
          reread().flush(info(1, { generation: again, validation: run('RUNNING', 1) }));
          expect(checks()).toEqual([]);
          vi.advanceTimersByTime(POLL_MS);
          reread().flush(info(1, { generation: again, validation: run('DONE', 1) }));
          expect(checks()).toEqual([]);
          http.verify();
        });
      });

      it('a reload that finds a new generation at a lower revision takes it', () => {
        oldCopyMovedAt5();
        store.reload();
        reread().flush(info(0, { generation: again }));
        expect(store.info()?.revision).toBe(0);
        expect(store.info()?.generation).toBe(again);
        expect(notices).toEqual([COPY_REPLACED_NOTICE]);
        expect(changed).toBe(1);
      });
    });
  });

  describe('edits queued behind a conflict', () => {
    it('are not sent: one notice and one reload per conflict', () => {
      const errors: unknown[] = [];
      source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe({ error: (e) => errors.push(e) });
      source.applyEdits([{ op: 'SetMetadataValue', line: 4, value: 'b' }]).subscribe({ error: (e) => errors.push(e) });
      store.edit([{ op: 'SetMetadataValue', line: 5, value: 'c' }] as EditOps).subscribe({ error: (e) => errors.push(e) });
      conflict(onePost());
      // the copy is read again before anything else is sent
      expect(posts().length).toBe(0);
      reread().flush(info(4));
      // made on the text before the other tab's change: not sent, refused as the views reload
      expect(posts().length).toBe(0);
      expect(errors.length).toBe(3);
      expect(errors.every((e) => (e as { status?: number }).status === 409)).toBe(true);
      expect(notices).toEqual([CONFLICT_NOTICE]);
      expect(changed).toBe(1);
      expect(store.saving()).toBe(false);
      http.verify();
    });

    it('an edit made after the reload is sent at the new revision', () => {
      source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe({ error: () => undefined });
      conflict(onePost());
      reread().flush(info(4));
      source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'b' }], { readAt: 4 }).subscribe();
      const next = onePost();
      expect(revisionOf(next)).toBe(4);
      next.flush(info(5));
      expect(notices).toEqual([CONFLICT_NOTICE]);
    });

    it('an undo queued behind it is not sent either, and adds no message', () => {
      source.applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }]).subscribe({ error: () => undefined });
      store.undo();
      conflict(onePost());
      reread().flush(info(4));
      expect(http.match((r) => r.url.includes('working-copies/abc/undo')).length).toBe(0);
      expect(notices).toEqual([CONFLICT_NOTICE]);
    });
  });

  describe('nothing to undo or redo', () => {
    const undos = () => http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/undo'));
    const nothingToUndo = (r: TestRequest) =>
      r.flush({ error: 'nothing_to_undo', message: 'There is nothing to undo.' }, { status: 409, statusText: 'Conflict' });

    it('aSecondUndoWithNothingToUndoKeepsQueuedEdits', () => {
      store.info.set(info(2, { canUndo: true, canRedo: false }));
      // two quick clicks: the second is queued before the first is answered
      store.undo();
      store.undo();
      const [first] = undos();
      expect(first.request.params.get('revision')).toBe('2');
      first.flush(info(3, { canUndo: true, canRedo: true }));
      expect(changed).toBe(1);
      const [second] = undos();
      expect(second.request.params.get('revision')).toBe('3');
      // an edit of a page read after the undo, queued behind the second undo
      const saved: unknown[] = [];
      const errors: unknown[] = [];
      source.applyEdits([{ op: 'SetMetadataValue', line: 4, value: 'b' }], { readAt: 3 }).subscribe({
        next: (i) => saved.push(i),
        error: (e) => errors.push(e),
      });
      nothingToUndo(second);
      // a quiet re-read: the buttons follow the server
      const fresh = info(3, { canUndo: false, canRedo: true });
      reread().flush(fresh);
      expect(store.info()).toBe(fresh);
      expect(store.info()?.canUndo).toBe(false);
      // the edit behind it is sent, at the revision the undo left
      const edit = onePost();
      expect(revisionOf(edit)).toBe(3);
      edit.flush(info(4, { canUndo: true, canRedo: false }));
      expect(saved.length).toBe(1);
      expect(errors).toEqual([]);
      expect(notices).toEqual([]);
      expect(changed).toBe(1);
      expect(store.info()?.revision).toBe(4);
      http.verify();
    });

    it('a redo with nothing to redo re-reads quietly and the button follows', () => {
      store.info.set(info(2, { canUndo: false, canRedo: true }));
      store.redo();
      nothingToUndo(http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/redo')));
      reread().flush(info(2, { canUndo: false, canRedo: false }));
      expect(store.info()?.canRedo).toBe(false);
      expect(notices).toEqual([]);
      expect(changed).toBe(0);
      expect(store.saving()).toBe(false);
      // the next edit is sent at the revision the copy is at
      source.applyEdits([{ op: 'SetMetadataValue', line: 4, value: 'b' }]).subscribe();
      expect(revisionOf(onePost())).toBe(2);
      http.verify();
    });

    it('a quiet re-read that finds a copy created again says so once and checks it once', () => {
      store.info.set(info(2, { generation: 'g1', canUndo: true }));
      store.undo();
      const errors: unknown[] = [];
      source.applyEdits([{ op: 'SetMetadataValue', line: 4, value: 'b' }], { readAt: 2 }).subscribe({ error: (e) => errors.push(e) });
      nothingToUndo(undos()[0]);
      reread().flush(info(0, { generation: 'g2' }));
      expect(notices).toEqual([COPY_REPLACED_NOTICE]);
      expect(changed).toBe(1);
      expect(store.info()?.generation).toBe('g2');
      // the edit behind it was made on the old copy: not sent
      expect(posts()).toEqual([]);
      expect(errors).toEqual([{ status: 409, detail: null }]);
      expect(http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations')).length).toBe(1);
      http.verify();
    });

    it('a re-read that fails leaves the editor working: nothing said, the next edit is sent', () => {
      store.info.set(info(2, { canUndo: true }));
      store.undo();
      nothingToUndo(undos()[0]);
      reread().flush({ message: 'down' }, { status: 500, statusText: 'Server Error' });
      expect(notices).toEqual([]);
      expect(store.saving()).toBe(false);
      expect(store.view()).not.toBe('error');
      source.applyEdits([{ op: 'SetMetadataValue', line: 4, value: 'b' }]).subscribe();
      expect(revisionOf(onePost())).toBe(2);
      http.verify();
    });
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

  it('says a current CV check is of an earlier session until this page checks the CV rules, also after a reload', () => {
    const earlier = info(3, { cvCheckedRevision: 3, validation: run({ semantic: true }) });
    store.open('abc');
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies')).flush(earlier);
    const EARLIER = 'CV rules checked at revision 3, in an earlier session: press Check CV rules to check them again.';
    expect(store.cvStatus()).toBe(EARLIER);
    // reading the same run again (a reload, as after a refused download, or a conflict) is no check of this page
    store.reload();
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc')).flush(earlier);
    expect(store.cvStatus()).toBe(EARLIER);

    store.validate(true);
    posts()[0].flush(info(3, { cvCheckedRevision: 3, validation: run({ status: 'RUNNING', semantic: true }) }));
    expect(store.cvStatus()).toBe(EARLIER);
    vi.advanceTimersByTime(1000);
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc')).flush(earlier);
    expect(store.cvStatus()).toBe('CV rules checked at revision 3.');
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

/**
 * A column operation, a split of cells or a raw line edit changes what a cell index of a table page means (or
 * which line a row is): a page read before it must not have its cells edited at the newer revision.
 */
describe('WorkingCopyStore: edits behind a column change', () => {
  let http: HttpTestingController;
  let store: WorkingCopyStore;
  let source: WorkingCopySource;
  let notices: string[];

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
    store.notices.subscribe((n) => notices.push(n));
  });

  const post = (): TestRequest => {
    const found = http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/edits'));
    expect(found.length).toBe(1);
    return found[0];
  };
  const revisionOf = (r: TestRequest) => (r.request.body as { revision: number }).revision;

  const columnChanges: [string, DocEdit][] = [
    ['DeleteColumn', { op: 'DeleteColumn', header: 'SMH', name: 'x' }],
    ['InsertColumn', { op: 'InsertColumn', header: 'SMH', position: 2, name: 'x', fill: '' }],
    ['MergeColumns', { op: 'MergeColumns', header: 'SMH', name: 'x', keep: 2, others: [3] }],
    ['RenameColumn', { op: 'RenameColumn', header: 'SMH', name: 'x', newName: 'y' }],
    ['SplitCells', { op: 'SplitCells', line: 5 }],
    ['SetRawLine', { op: 'SetRawLine', line: 5, text: 'SML\t1\ta' }],
  ];

  for (const [name, op] of columnChanges) {
    it(`${name} raises the layout revision: a SetCell read before it is refused with the conflict notice`, () => {
      source.applyEdits([op]).subscribe();
      post().flush(info(1));
      // the page was read at revision 0 and its reload is not in yet
      source
        .applyEdits([{ op: 'SetCell', line: 5, cell: 3, text: 'x', header: 'y' }], { readAt: 0 })
        .subscribe({ error: () => undefined });
      const second = post();
      expect(revisionOf(second)).toBe(0);
      second.flush({ message: 'stale' }, { status: 409, statusText: 'Conflict' });
      http.expectOne((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc')).flush(info(1));
      expect(notices).toEqual([LAYOUT_CHANGED_NOTICE]);
    });
  }

  it('a SetCell queued behind a rename in flight is refused with the conflict notice, not the other-tab one', () => {
    source.applyEdits([{ op: 'RenameColumn', header: 'SMH', name: 'x', newName: 'y' }]).subscribe();
    source
      .applyEdits([{ op: 'SetCell', line: 5, cell: 3, text: 'x', header: 'x' }], { readAt: 0 })
      .subscribe({ error: () => undefined });
    post().flush(info(1));
    const second = post();
    expect(revisionOf(second)).toBe(0);
    second.flush({ message: 'stale' }, { status: 409, statusText: 'Conflict' });
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc')).flush(info(1));
    expect(notices).toEqual([LAYOUT_CHANGED_NOTICE]);
  });

  it('a duplicate-column resolution read before a rename is refused as stale', () => {
    source.applyEdits([{ op: 'RenameColumn', header: 'SMH', name: 'x', newName: 'y' }]).subscribe();
    post().flush(info(1));
    source
      .applyEdits([{ op: 'DeleteColumn', header: 'SMH', name: 'x', position: 3 }], { readAt: 0 })
      .subscribe({ error: () => undefined });
    expect(revisionOf(post())).toBe(0);
  });

  it('a SetCell of a page read after the column change is sent at the current revision', () => {
    source.applyEdits([columnChanges[0][1]]).subscribe();
    post().flush(info(1));
    source.applyEdits([{ op: 'SetCell', line: 5, cell: 3, text: 'x', header: 'y' }], { readAt: 1 }).subscribe();
    expect(revisionOf(post())).toBe(1);
  });
});

describe('WorkingCopyStore: an edit whose answer failed after the server committed it', () => {
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

  const post = () => http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/edits'));
  const reread = () => http.expectOne((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc'));
  const fail = (r: TestRequest, status: number) => r.flush({ message: 'busy' }, { status, statusText: 'x' });
  const changeIdOf = (r: TestRequest) => (r.request.body as { changeId: string }).changeId;
  const edit = (results: unknown[], errors: unknown[]) =>
    source
      .applyEdits([{ op: 'SetMetadataValue', line: 3, value: 'a' }])
      .subscribe({ next: (i) => results.push(i), error: (e) => errors.push(e) });

  it('every change carries a changeId of its own', () => {
    edit([], []);
    const first = post();
    expect(changeIdOf(first)).toMatch(/^[0-9a-f-]{36}$/);
    first.flush(info(1));
    edit([], []);
    expect(changeIdOf(post())).not.toBe(changeIdOf(first));
  });

  it('a 503 after the commit: the copy names this change as its last, so the edit counts as saved', () => {
    const results: unknown[] = [];
    const errors: unknown[] = [];
    edit(results, errors);
    const sent = post();
    fail(sent, 503);
    reread().flush(info(1, { lastChangeId: changeIdOf(sent) }));
    expect(errors).toEqual([]);
    expect(results.length).toBe(1);
    expect(store.info()?.revision).toBe(1);
    expect(notices).toEqual([]);
    http.verify();
  });

  it('a lost answer (no status) is recovered the same way', () => {
    const results: unknown[] = [];
    const errors: unknown[] = [];
    edit(results, errors);
    const sent = post();
    sent.error(new ProgressEvent('error'));
    reread().flush(info(1, { lastChangeId: changeIdOf(sent) }));
    expect(errors).toEqual([]);
    expect(results.length).toBe(1);
    http.verify();
  });

  it('the next edit, queued behind it, names the committed revision and is not refused', () => {
    edit([], []);
    source.applyEdits([{ op: 'SetMetadataValue', line: 4, value: 'b' }]).subscribe();
    const sent = post();
    fail(sent, 503);
    reread().flush(info(1, { lastChangeId: changeIdOf(sent) }));
    expect((post().request.body as { revision: number }).revision).toBe(1);
    http.verify();
  });

  it('a 503 that did not commit: the copy is where it was, and the failure is passed on', () => {
    const errors: unknown[] = [];
    edit([], errors);
    fail(post(), 503);
    reread().flush(info(0));
    expect(errors.length).toBe(1);
    expect((errors[0] as { status: number }).status).toBe(503);
    expect(store.info()?.revision).toBe(0);
    expect(notices).toEqual([]);
    http.verify();
  });

  it('this tab\'s edit fails (busy) while another tab commits the next revision: never claimed as saved', () => {
    const errors: unknown[] = [];
    const results: unknown[] = [];
    edit(results, errors);
    fail(post(), 503);
    reread().flush(info(1, { lastChangeId: 'another-tabs-change' }));
    expect(results).toEqual([]);
    expect(errors).toEqual([{ status: 409, detail: null, maybeSaved: true }]);
    expect(store.info()?.revision).toBe(1);
    expect(changed).toBe(1);
    expect(notices).toEqual([MAYBE_SAVED_NOTICE]);
    // the next edit names the revision that was read, so it is not refused
    edit([], []);
    expect((post().request.body as { revision: number }).revision).toBe(1);
    http.verify();
  });

  it('a copy further ahead, with no id of this change: "may have been saved", reload, no other message', () => {
    const errors: unknown[] = [];
    edit([], errors);
    fail(post(), 500);
    reread().flush(info(3, { lastChangeId: null }));
    expect(errors).toEqual([{ status: 409, detail: null, maybeSaved: true }]);
    expect(store.info()?.revision).toBe(3);
    expect(changed).toBe(1);
    expect(notices).toEqual([MAYBE_SAVED_NOTICE]);
    http.verify();
  });

  it('an undo that may have been saved says so once, not "could not be saved" as well', () => {
    store.info.set(info(1, { canUndo: true }));
    store.undo();
    const sent = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/undo'));
    expect(sent.request.params.get('changeId')).toMatch(/^[0-9a-f-]{36}$/);
    fail(sent, 503);
    reread().flush(info(2, { lastChangeId: 'another-tabs-change' }));
    expect(notices).toEqual([MAYBE_SAVED_NOTICE]);
    http.verify();
  });

  it('an undo whose answer was lost but which was applied counts as done', () => {
    store.info.set(info(1, { canUndo: true }));
    store.undo();
    const sent = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/undo'));
    fail(sent, 503);
    reread().flush(info(2, { lastChangeId: sent.request.params.get('changeId') }));
    expect(notices).toEqual([]);
    expect(store.info()?.revision).toBe(2);
    http.verify();
  });

  it('aFailedSaveThatFindsACopyCreatedAgainAtALowerRevisionAnnouncesIt', () => {
    store.info.set(info(5, { generation: 'g1' }));
    const errors: unknown[] = [];
    edit([], errors);
    // queued behind it: made on the old copy
    store.edit([{ op: 'SetMetadataValue', line: 4, value: 'b' }] as EditOps).subscribe({ error: (e) => errors.push(e) });
    fail(post(), 503);
    // the copy was deleted and created again meanwhile: its revisions count anew, so it is below the one sent
    reread().flush(info(0, { generation: 'g2' }));
    expect(notices).toEqual([COPY_REPLACED_NOTICE]);
    expect(changed).toBe(1);
    expect(store.info()?.generation).toBe('g2');
    expect(store.info()?.revision).toBe(0);
    // refused as a copy created again (409: the views add no "could not be saved"), and the edit queued behind it is not sent
    expect(errors.map((e) => (e as { status: number }).status)).toEqual([409, 409]);
    expect(http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/edits'))).toEqual([]);
    // the new copy has no run: it is checked at once, once
    const checks = http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations'));
    expect(checks.length).toBe(1);
    checks[0].flush(info(0, { generation: 'g2', validation: { status: 'RUNNING', revision: 0 } as WorkingCopyValidation }));
    expect(notices).toEqual([COPY_REPLACED_NOTICE]);
  });

  it('a failed save that finds a copy created again at the same revision announces it too', () => {
    store.info.set(info(5, { generation: 'g1' }));
    const errors: unknown[] = [];
    edit([], errors);
    fail(post(), 503);
    reread().flush(info(5, { generation: 'g2', validation: { status: 'RUNNING', revision: 5 } as WorkingCopyValidation }));
    expect(notices).toEqual([COPY_REPLACED_NOTICE]);
    expect(changed).toBe(1);
    expect(store.info()?.generation).toBe('g2');
    expect(errors).toEqual([{ status: 409, detail: null }]);
  });

  it('an undo whose answer failed, when the copy was created again meanwhile, says only that it was replaced', () => {
    store.info.set(info(5, { generation: 'g1', canUndo: true }));
    store.undo();
    fail(http.expectOne((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/undo')), 503);
    reread().flush(info(0, { generation: 'g2' }));
    expect(notices).toEqual([COPY_REPLACED_NOTICE]);
    expect(notices).not.toContain(SAVE_FAILED);
    expect(changed).toBe(1);
    expect(store.saving()).toBe(false);
    // one check of the new copy, and no conflict re-read
    expect(http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations')).length).toBe(1);
    http.verify();
  });

  it('a failed save whose re-read is a late answer about a replaced copy changes nothing', () => {
    store.info.set(info(5, { generation: 'g1' }));
    store.reload();
    reread().flush(info(0, { generation: 'g2', validation: { status: 'RUNNING', revision: 0 } as WorkingCopyValidation }));
    notices.length = 0;
    changed = 0;
    const errors: unknown[] = [];
    edit([], errors);
    fail(post(), 503);
    reread().flush(info(7, { generation: 'g1' }));
    expect(notices).toEqual([]);
    expect(changed).toBe(0);
    expect(store.info()?.generation).toBe('g2');
    expect((errors[0] as { status: number }).status).toBe(503);
  });

  it('a rejected batch (400) is not re-read: nothing was changed', () => {
    const errors: unknown[] = [];
    edit([], errors);
    fail(post(), 400);
    expect(errors.length).toBe(1);
    http.expectNone((r) => r.method === 'GET');
    http.verify();
  });

  it('when the copy cannot be read either, the original failure is passed on', () => {
    const errors: unknown[] = [];
    edit([], errors);
    fail(post(), 503);
    fail(reread(), 503);
    expect(errors.length).toBe(1);
    expect((errors[0] as { status: number }).status).toBe(503);
    http.verify();
  });
});

/** Imports: a preview names the copy's revision; its batch is applied as a quick fix's is, and a conflict is "stale". */
describe('WorkingCopyStore: imports', () => {
  let http: HttpTestingController;
  let store: WorkingCopyStore;
  let notices: string[];
  let changed: number;

  beforeEach(() => {
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
    store.info.set(info(3, { generation: 'g1' }));
    notices = [];
    changed = 0;
    store.notices.subscribe((n) => notices.push(n));
    store.changed.subscribe(() => changed++);
  });

  const preview = (revision: number): ImportPreview => ({
    kind: 'vocabulary-instance',
    revision,
    mapping: 'mztab-m-2.0-output',
    mappingVersion: '1.0.0',
    suggestions: [{ key: 'title', value: 't', state: 'NEW' }],
    notes: [],
    batch: { revision, operations: [{ op: 'InsertLines', before: 3, lines: ['MTD\ttitle\tt', 'MTD\tdescription\td'] }] as never },
  });
  const request = { kind: 'vocabulary-instance', instance: { datasets: [{}] }, datasetIndex: 1, studyId: 'LCS-1', keepExisting: true };
  const edits = () => http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/edits'));
  const imports = () => http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/imports'));
  const reread = () => http.expectOne((r) => r.method === 'GET' && r.url.endsWith('working-copies/abc'));

  it('applyImport sends one batch: the preview revision, a change id of its own and the generation', () => {
    const outcomes: string[] = [];
    store.applyImport(preview(3)).subscribe((o) => outcomes.push(o));
    const sent = edits();
    expect(sent.length).toBe(1);
    expect(sent[0].request.body).toEqual({
      revision: 3,
      operations: [{ op: 'InsertLines', before: 3, lines: ['MTD\ttitle\tt', 'MTD\tdescription\td'] }],
      changeId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      generation: 'g1',
    });
    sent[0].flush(info(4, { generation: 'g1' }));
    expect(outcomes).toEqual(['applied']);
    expect(store.info()?.revision).toBe(4);
    // the views read again: the import inserted lines
    expect(changed).toBe(1);
    // lines moved: a page read before it is stale for edits that name lines
    expect(store.linesMovedSince(3)).toBe(true);
    http.verify();
  });

  /** A preview as the store hands it out: of generation g1. */
  const stored = (revision: number, generation: string | null = 'g1') => ({ ...preview(revision), generation });

  it('an edit of this tab queued ahead of it: "stale", not sent, nothing said or reloaded, the edit kept', () => {
    const outcomes: string[] = [];
    const saved: unknown[] = [];
    store.edit([{ op: 'SetMetadataValue', line: 7, value: 'x' }] as EditOps).subscribe((i) => saved.push(i));
    store.applyImport(stored(3)).subscribe((o) => outcomes.push(o));
    const sent = edits();
    expect(sent.length).toBe(1);
    expect((sent[0].request.body as { operations: EditOps }).operations).toEqual([{ op: 'SetMetadataValue', line: 7, value: 'x' }]);
    sent[0].flush(info(4, { generation: 'g1' }));
    expect(saved.length).toBe(1);
    expect(outcomes).toEqual(['stale']);
    expect(edits().length).toBe(0);
    expect(notices).toEqual([]);
    expect(changed).toBe(0);
    expect(store.info()?.revision).toBe(4);
    http.verify();
  });

  it('an edit saved since the preview (R+1, preview of R): "stale", not sent', () => {
    store.info.set(info(4, { generation: 'g1' }));
    const outcomes: string[] = [];
    store.applyImport(stored(3)).subscribe((o) => outcomes.push(o));
    expect(outcomes).toEqual(['stale']);
    expect(edits().length).toBe(0);
    expect(notices).toEqual([]);
    expect(changed).toBe(0);
    http.verify();
  });

  it('a preview of another generation of the copy: "stale", not sent', () => {
    const outcomes: string[] = [];
    store.applyImport(stored(3, 'g0')).subscribe((o) => outcomes.push(o));
    expect(outcomes).toEqual(['stale']);
    expect(edits().length).toBe(0);
    http.verify();
  });

  it('an edit queued ahead of it that is refused (400) changed nothing: the preview is still of the copy, and is sent', () => {
    const outcomes: string[] = [];
    store.edit([{ op: 'SetMetadataValue', line: 7, value: 'x' }] as EditOps).subscribe({ error: () => undefined });
    store.applyImport(stored(3)).subscribe((o) => outcomes.push(o));
    edits()[0].flush({ message: 'Line 7 is too long.' }, { status: 400, statusText: 'Bad Request' });
    const sent = edits();
    expect(sent.length).toBe(1);
    expect((sent[0].request.body as { revision: number }).revision).toBe(3);
    sent[0].flush(info(4, { generation: 'g1' }));
    expect(outcomes).toEqual(['applied']);
  });

  it('a preview carries the generation of the copy it was made of', () => {
    const previews: { generation?: string | null }[] = [];
    store.previewImport(request).subscribe((p) => previews.push(p));
    imports()[0].flush(preview(3));
    expect(previews.map((p) => p.generation)).toEqual(['g1']);
  });

  it('a 409 reads the copy again, through the conflict handling, and is "stale"', () => {
    const outcomes: string[] = [];
    store.applyImport(preview(3)).subscribe((o) => outcomes.push(o));
    edits()[0].flush({ message: 'stale' }, { status: 409, statusText: 'Conflict' });
    expect(outcomes).toEqual([]);
    reread().flush(info(5, { generation: 'g1' }));
    expect(outcomes).toEqual(['stale']);
    expect(store.info()?.revision).toBe(5);
    expect(notices).toEqual([CONFLICT_NOTICE]);
    expect(changed).toBe(1);
    http.verify();
  });

  it('a replaced copy is "stale" too, and says so', () => {
    const outcomes: string[] = [];
    store.applyImport(preview(3)).subscribe((o) => outcomes.push(o));
    edits()[0].flush({ error: 'copy_replaced', message: 'replaced' }, { status: 409, statusText: 'Conflict' });
    reread().flush(info(0, { generation: 'g2' }));
    expect(outcomes).toEqual(['stale']);
    expect(notices).toEqual([COPY_REPLACED_NOTICE]);
  });

  it('an import whose answer failed, when the copy was created again meanwhile, is "stale" and says so once', () => {
    const outcomes: string[] = [];
    const errors: unknown[] = [];
    store.applyImport(preview(3)).subscribe({ next: (o) => outcomes.push(o), error: (e) => errors.push(e) });
    edits()[0].flush({ message: 'busy' }, { status: 503, statusText: 'Service Unavailable' });
    reread().flush(info(0, { generation: 'g2' }));
    expect(outcomes).toEqual(['stale']);
    expect(errors).toEqual([]);
    expect(notices).toEqual([COPY_REPLACED_NOTICE]);
    expect(changed).toBe(1);
    expect(http.match((r) => r.method === 'POST' && r.url.endsWith('working-copies/abc/validations')).length).toBe(1);
    http.verify();
  });

  it('another failure is passed on', () => {
    const errors: unknown[] = [];
    store.applyImport(preview(3)).subscribe({ error: (e) => errors.push(e) });
    edits()[0].flush({ message: 'Line 3 is too long.' }, { status: 400, statusText: 'Bad Request' });
    expect((errors[0] as { status: number }).status).toBe(400);
    http.verify();
  });

  it('previewImport names the revision the store knows; a 409 reads the copy again and previews once more', () => {
    const previews: ImportPreview[] = [];
    store.previewImport(request).subscribe((p) => previews.push(p));
    const first = imports();
    expect(first.length).toBe(1);
    expect(first[0].request.body).toEqual({ ...request, revision: 3 });
    first[0].flush({ message: 'stale' }, { status: 409, statusText: 'Conflict' });
    reread().flush(info(6, { generation: 'g1' }));
    const second = imports();
    expect(second.length).toBe(1);
    expect(second[0].request.body).toEqual({ ...request, revision: 6 });
    second[0].flush(preview(6));
    expect(previews.map((p) => p.revision)).toEqual([6]);
    // the copy moved on elsewhere: the views read again
    expect(changed).toBe(1);
    http.verify();
  });

  it('hasProfile comes from the copy: a new copy has its own repository rules', () => {
    expect(store.hasProfile()).toBe(false);
    store.info.set(info(3, { validationProfile: { kind: 'CATALOG', id: 'mztab-m-2.0-default', version: '1.1.0' } }));
    expect(store.hasProfile()).toBe(true);
    store.info.set(info(3, { validationProfile: { kind: 'NONE' } }));
    expect(store.hasProfile()).toBe(false);
  });

  it('opening a copy asks nothing of the validation session', () => {
    store.open('abc');
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('rest/v2/working-copies'));
    http.expectNone((r) => r.url.includes('rest/v2/validations'));
  });
});

/**
 * A server that does not answer: every editor request has a client timeout (30 s; 120 s for the requests that wait
 * for the engine), reads are asked again on a lost answer (status 0) after 1 s and then 3 s, and a change that timed
 * out is sent once more only when the copy read again shows it was not applied. A change names its revision, so a
 * resend after a late commit is refused (409): no change is applied twice.
 */
describe('WorkingCopyStore: a server that does not answer', () => {
  let http: HttpTestingController;
  let store: WorkingCopyStore;
  let source: WorkingCopySource;
  let notices: string[];
  let changed: number;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([requestTimeoutInterceptor])),
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

  afterEach(() => vi.useRealTimers());

  const isPost = (path: string) => (r: { method: string; url: string }) => r.method === 'POST' && r.url.endsWith(`working-copies/abc/${path}`);
  const isRead = (r: { method: string; url: string }) => r.method === 'GET' && r.url.endsWith('working-copies/abc');
  const post = () => http.expectOne(isPost('edits'));
  const reread = () => http.expectOne(isRead);
  const noRead = () => expect(http.match(isRead)).toEqual([]);
  const changeIdOf = (r: TestRequest) => (r.request.body as { changeId: string }).changeId;
  const revisionOf = (r: TestRequest) => (r.request.body as { revision: number }).revision;
  /** The server does not answer: the client gives up after its timeout. */
  const silence = (ms = EDITOR_TIMEOUT_MS) => vi.advanceTimersByTime(ms);
  const lost = (r: TestRequest) => r.error(new ProgressEvent('error'));
  const edit = (results: unknown[], errors: unknown[], value = 'a') =>
    source
      .applyEdits([{ op: 'SetMetadataValue', line: 3, value }])
      .subscribe({ next: (i) => results.push(i), error: (e) => errors.push(e) });

  /** The re-read after a failed save waits 1 s, then 3 s, before it is asked again; each one times out. */
  function rereadTimesOutThreeTimes(): void {
    reread();
    silence();
    vi.advanceTimersByTime(READ_RETRY_DELAYS_MS[0] - 1);
    noRead();
    vi.advanceTimersByTime(1);
    reread();
    silence();
    vi.advanceTimersByTime(READ_RETRY_DELAYS_MS[1] - 1);
    noRead();
    vi.advanceTimersByTime(1);
    reread();
    silence();
  }

  describe('the time each request is given', () => {
    it('an edit, an undo and a redo are given 30 s', () => {
      edit([], []);
      expect(post().request.context.get(REQUEST_TIMEOUT)).toBe(EDITOR_TIMEOUT_MS);
    });

    it.each(['undo', 'redo'] as const)('%s is given 30 s', (action) => {
      store.info.set(info(1, { canUndo: true, canRedo: true }));
      store[action]();
      expect(http.expectOne(isPost(action)).request.context.get(REQUEST_TIMEOUT)).toBe(EDITOR_TIMEOUT_MS);
    });

    it('a quick fix applied and an import applied are given 30 s', () => {
      store.applyFix({ fixId: 'f', batch: { revision: 0, operations: [] } } as never).subscribe({ error: () => undefined });
      expect(post().request.context.get(REQUEST_TIMEOUT)).toBe(EDITOR_TIMEOUT_MS);
    });

    it('a read of the copy is given 30 s', () => {
      store.reload();
      expect(reread().request.context.get(REQUEST_TIMEOUT)).toBe(EDITOR_TIMEOUT_MS);
    });

    it('a request for a check and a quick-fix preview are given 30 s, and are not asked again on a lost answer', () => {
      store.validate(false);
      const check = http.expectOne(isPost('validations'));
      expect(check.request.context.get(REQUEST_TIMEOUT)).toBe(EDITOR_TIMEOUT_MS);
      silence();
      expect(store.checkFailed()).toBe(true);
      store.previewFix('f', 0, false).subscribe({ error: () => undefined });
      const fix = http.expectOne((r) => r.url.endsWith('working-copies/abc/quick-fixes/f'));
      expect(fix.request.context.get(REQUEST_TIMEOUT)).toBe(EDITOR_TIMEOUT_MS);
      lost(fix);
      vi.advanceTimersByTime(10_000);
      http.expectNone(isPost('validations'));
      http.expectNone((r) => r.url.endsWith('working-copies/abc/quick-fixes/f'));
    });

    it('opening a copy (made server-side from the stored file) is given 120 s and is not asked again', () => {
      store.open('abc');
      const create = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('rest/v2/working-copies'));
      expect(create.request.context.get(REQUEST_TIMEOUT)).toBe(ENGINE_TIMEOUT_MS);
      silence(ENGINE_TIMEOUT_MS - 1);
      expect(store.view()).toBe('loading');
      silence(1);
      expect(store.view()).toBe('error');
      vi.advanceTimersByTime(10_000);
      http.expectNone((r) => r.method === 'POST' && r.url.endsWith('rest/v2/working-copies'));
    });

    it('the import preview is given 120 s and is not asked again', () => {
      const errors: unknown[] = [];
      store
        .previewImport({ kind: 'vocabulary-instance', instance: {}, datasetIndex: 0, studyId: 'S', keepExisting: true } as never)
        .subscribe({ error: (e) => errors.push(e) });
      const sent = http.expectOne(isPost('imports'));
      expect(sent.request.context.get(REQUEST_TIMEOUT)).toBe(ENGINE_TIMEOUT_MS);
      silence(ENGINE_TIMEOUT_MS - 1);
      expect(errors).toEqual([]);
      silence(1);
      expect(errors.length).toBe(1);
      expect(isTimeout(errors[0])).toBe(true);
      vi.advanceTimersByTime(10_000);
      http.expectNone(isPost('imports'));
      noRead();
    });

    it('the JSON download, which can be large, has no client timeout', () => {
      store.downloadJson().subscribe({ error: () => undefined });
      const download = http.expectOne((r) => r.url.endsWith('working-copies/abc/file'));
      expect(download.request.context.get(REQUEST_TIMEOUT)).toBeNull();
      vi.advanceTimersByTime(10 * 60_000);
      expect(download.cancelled).toBe(false);
    });
  });

  describe('reads are asked again on a lost answer', () => {
    it('reload: after 1 s, and the answer is taken', () => {
      store.reload();
      reread();
      silence();
      vi.advanceTimersByTime(READ_RETRY_DELAYS_MS[0]);
      reread().flush(info(2));
      expect(store.info()?.revision).toBe(2);
    });

    it('reload: a 503 is not asked again', () => {
      store.reload();
      reread().flush({}, { status: 503, statusText: 'Service Unavailable' });
      vi.advanceTimersByTime(10_000);
      noRead();
    });

    it('the poll: a read that timed out is asked again, and the poll goes on', () => {
      store.reload();
      reread().flush(info(0, { validation: { status: 'RUNNING', revision: 0 } as WorkingCopyValidation }));
      vi.advanceTimersByTime(POLL_MS);
      const first = reread();
      expect(first.request.context.get(REQUEST_TIMEOUT)).toBe(EDITOR_TIMEOUT_MS);
      silence();
      vi.advanceTimersByTime(READ_RETRY_DELAYS_MS[0]);
      reread().flush(info(0, { validation: { status: 'DONE', revision: 0 } as WorkingCopyValidation }));
      expect(store.validation()?.status).toBe('DONE');
    });

    it('the quiet re-read after "nothing to undo" is asked again, and the buttons follow its answer', () => {
      store.info.set(info(2, { canUndo: true }));
      store.undo();
      http
        .expectOne(isPost('undo'))
        .flush({ error: 'nothing_to_undo', message: 'There is nothing to undo.' }, { status: 409, statusText: 'Conflict' });
      lost(reread());
      vi.advanceTimersByTime(READ_RETRY_DELAYS_MS[0]);
      reread().flush(info(2, { canUndo: false }));
      expect(store.info()?.canUndo).toBe(false);
      expect(notices).toEqual([]);
    });

    it('the re-read after a conflict is asked again, and the copy is reloaded with one notice', () => {
      const errors: unknown[] = [];
      edit([], errors);
      post().flush({ message: 'stale' }, { status: 409, statusText: 'Conflict' });
      lost(reread());
      vi.advanceTimersByTime(READ_RETRY_DELAYS_MS[0]);
      reread().flush(info(4));
      expect(store.info()?.revision).toBe(4);
      expect(notices).toEqual([CONFLICT_NOTICE]);
      expect(errors).toEqual([{ status: 409, detail: null }]);
    });
  });

  describe('a re-read that fails after its retries: the failure is shown, nothing hangs', () => {
    it('after a failed save: "could not be saved" as before, and the next edit is sent', () => {
      const errors: unknown[] = [];
      edit([], errors);
      post();
      silence();
      rereadTimesOutThreeTimes();
      // the views say the change could not be saved (no status of a refusal)
      expect(errors).toEqual([{ status: 0, detail: null }]);
      expect(store.saving()).toBe(false);
      expect(notices).toEqual([]);
      // the queue goes on: the next edit is sent, at the revision known
      edit([], [], 'b');
      expect(revisionOf(post())).toBe(0);
    });

    it('after a failed undo: the notice "could not be saved", and the next undo is sent', () => {
      store.info.set(info(1, { canUndo: true }));
      store.undo();
      lost(http.expectOne(isPost('undo')));
      rereadTimesOutThreeTimes();
      expect(notices).toEqual([SAVE_FAILED]);
      expect(store.saving()).toBe(false);
      store.undo();
      http.expectOne(isPost('undo'));
    });

    it('after a conflict: the page shows that the copy could not be read, and the next edit is sent', () => {
      const errors: unknown[] = [];
      edit([], errors);
      post().flush({ message: 'stale' }, { status: 409, statusText: 'Conflict' });
      rereadTimesOutThreeTimes();
      expect(store.view()).toBe('error');
      expect(errors).toEqual([{ status: 409, detail: null }]);
      expect(store.saving()).toBe(false);
      edit([], [], 'b');
      post();
    });
  });

  describe('a change that timed out', () => {
    it('applied (the copy names it as its last change): answered from the re-read, not sent again', () => {
      const results: unknown[] = [];
      const errors: unknown[] = [];
      edit(results, errors);
      const sent = post();
      silence();
      expect(sent.cancelled).toBe(true);
      reread().flush(info(1, { lastChangeId: changeIdOf(sent) }));
      expect(results.length).toBe(1);
      expect(errors).toEqual([]);
      expect(store.info()?.revision).toBe(1);
      vi.advanceTimersByTime(10 * EDITOR_TIMEOUT_MS);
      http.expectNone(isPost('edits'));
      expect(notices).toEqual([]);
    });

    it('not applied (same revision, same generation): sent once more, with the same change id and revision', () => {
      store.info.set(info(4, { generation: 'g1' }));
      const results: unknown[] = [];
      const errors: unknown[] = [];
      edit(results, errors);
      const sent = post();
      silence();
      reread().flush(info(4, { generation: 'g1', lastChangeId: 'an-earlier-change' }));
      const again = post();
      expect(again.request.body).toEqual(sent.request.body);
      expect(changeIdOf(again)).toBe(changeIdOf(sent));
      expect(revisionOf(again)).toBe(4);
      again.flush(info(5, { generation: 'g1', lastChangeId: changeIdOf(sent) }));
      expect(results.length).toBe(1);
      expect(errors).toEqual([]);
      expect(store.info()?.revision).toBe(5);
      expect(notices).toEqual([]);
      http.verify();
    });

    it('not applied, and the second send times out too: the failure is reported, nothing is sent a third time', () => {
      const errors: unknown[] = [];
      edit([], errors);
      post();
      silence();
      reread().flush(info(0, { lastChangeId: null }));
      post();
      silence();
      // the copy is read again after the second timeout as well: it is still not applied
      reread().flush(info(0, { lastChangeId: null }));
      expect(errors).toEqual([{ status: 0, detail: null }]);
      expect(store.saving()).toBe(false);
      vi.advanceTimersByTime(10 * EDITOR_TIMEOUT_MS);
      http.expectNone(isPost('edits'));
      // the next edit is sent
      edit([], [], 'b');
      post();
    });

    it('the second send timed out but was applied: it counts as saved', () => {
      const results: unknown[] = [];
      edit(results, []);
      const sent = post();
      silence();
      reread().flush(info(0));
      post();
      silence();
      reread().flush(info(1, { lastChangeId: changeIdOf(sent) }));
      expect(results.length).toBe(1);
      http.expectNone(isPost('edits'));
    });

    it('an undo not applied is sent once more, with the same change id and revision', () => {
      store.info.set(info(3, { canUndo: true }));
      store.undo();
      const sent = http.expectOne(isPost('undo'));
      silence();
      reread().flush(info(3, { canUndo: true }));
      const again = http.expectOne(isPost('undo'));
      expect(again.request.params.get('changeId')).toBe(sent.request.params.get('changeId'));
      expect(again.request.params.get('revision')).toBe('3');
      again.flush(info(4, { canUndo: false }));
      expect(store.info()?.revision).toBe(4);
      expect(notices).toEqual([]);
    });

    it('a copy that moved on (another tab): "may have been saved", not sent again', () => {
      const errors: unknown[] = [];
      edit([], errors);
      post();
      silence();
      reread().flush(info(1, { lastChangeId: 'another-tabs-change' }));
      expect(errors).toEqual([{ status: 409, detail: null, maybeSaved: true }]);
      expect(notices).toEqual([MAYBE_SAVED_NOTICE]);
      http.expectNone(isPost('edits'));
    });

    it('a copy created again at the same revision: announced as replaced, not sent again', () => {
      store.info.set(info(4, { generation: 'g1' }));
      const errors: unknown[] = [];
      edit([], errors);
      post();
      silence();
      reread().flush(info(4, { generation: 'g2' }));
      expect(notices).toEqual([COPY_REPLACED_NOTICE]);
      expect(errors).toEqual([{ status: 409, detail: null }]);
      http.expectNone(isPost('edits'));
    });

    it('a lost answer that is not a timeout (status 0 from the network) is not sent again: the failure is reported', () => {
      const errors: unknown[] = [];
      edit([], errors);
      lost(post());
      reread().flush(info(0));
      expect(errors).toEqual([{ status: 0, detail: null }]);
      http.expectNone(isPost('edits'));
    });

    it('a 503 that did not commit is not sent again', () => {
      const errors: unknown[] = [];
      edit([], errors);
      post().flush({}, { status: 503, statusText: 'Service Unavailable' });
      reread().flush(info(0));
      expect((errors[0] as { status: number }).status).toBe(503);
      http.expectNone(isPost('edits'));
    });
  });

  /**
   * The server as far as a change is concerned: it applies a change whose revision is the copy's and refuses any
   * other (409). The first send is committed late, after the re-read saw the copy unchanged.
   */
  describe('never two applies', () => {
    let copy: { revision: number; lastChangeId: string | null };
    let applied: string[];

    const serve = (r: TestRequest) => {
      const { revision, changeId } = r.request.body as { revision: number; changeId: string };
      if (revision !== copy.revision) {
        r.flush({ message: 'stale' }, { status: 409, statusText: 'Conflict' });
        return;
      }
      copy = { revision: copy.revision + 1, lastChangeId: changeId };
      applied.push(changeId);
      r.flush(info(copy.revision, { lastChangeId: changeId }));
    };
    const commitLate = (r: TestRequest) => {
      const { changeId } = r.request.body as { changeId: string };
      copy = { revision: copy.revision + 1, lastChangeId: changeId };
      applied.push(changeId);
    };

    beforeEach(() => {
      copy = { revision: 0, lastChangeId: null };
      applied = [];
    });

    it('a resend after a late commit is refused (409) and goes through the conflict path: applied once', () => {
      const results: unknown[] = [];
      const errors: unknown[] = [];
      edit(results, errors);
      const first = post();
      silence();
      // the re-read comes before the server's commit of the first send
      reread().flush(info(copy.revision, { lastChangeId: copy.lastChangeId }));
      const again = post();
      expect(changeIdOf(again)).toBe(changeIdOf(first));
      expect(revisionOf(again)).toBe(revisionOf(first));
      // the first send is committed now, before the second arrives
      commitLate(first);
      serve(again);
      // the conflict path: the copy is read again, the views reload, one notice
      reread().flush(info(copy.revision, { lastChangeId: copy.lastChangeId }));
      expect(applied).toEqual([changeIdOf(first)]);
      expect(errors).toEqual([{ status: 409, detail: null }]);
      expect(results).toEqual([]);
      expect(notices).toEqual([CONFLICT_NOTICE]);
      expect(changed).toBe(1);
      expect(store.info()?.revision).toBe(1);
      vi.advanceTimersByTime(10 * EDITOR_TIMEOUT_MS);
      http.expectNone(isPost('edits'));
    });

    it('a timeout whose re-read sees the commit is not sent again: applied once', () => {
      const results: unknown[] = [];
      edit(results, []);
      const first = post();
      commitLate(first);
      silence();
      reread().flush(info(copy.revision, { lastChangeId: copy.lastChangeId }));
      expect(results.length).toBe(1);
      http.expectNone(isPost('edits'));
      expect(applied).toEqual([changeIdOf(first)]);
    });

    it('two edits queued, the first resent: each applied once, the second at the revision the first made', () => {
      const results: unknown[] = [];
      edit(results, []);
      edit(results, [], 'b');
      const first = post();
      silence();
      reread().flush(info(copy.revision, { lastChangeId: copy.lastChangeId }));
      serve(post());
      const second = post();
      expect(revisionOf(second)).toBe(1);
      serve(second);
      expect(applied).toEqual([changeIdOf(first), changeIdOf(second)]);
      expect(results.length).toBe(2);
      expect(store.info()?.revision).toBe(2);
      http.verify();
    });
  });
});
