import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AsyncSubject,
  Observable,
  Subject,
  catchError,
  concatMap,
  defer,
  exhaustMap,
  finalize,
  from,
  map,
  of,
  switchMap,
  takeWhile,
  tap,
  throwError,
  timer,
} from 'rxjs';

import { EditBatch } from '../../api/models/edit-batch';
import { ImportPreview } from '../../api/models/import-preview';
import { ImportRequest } from '../../api/models/import-request';
import { QuickFixPreview } from '../../api/models/quick-fix-preview';
import { ValidationMessage } from '../../api/models/validation-message';
import { WorkingCopyInfo } from '../../api/models/working-copy-info';
import { WorkingCopiesService } from '../../api/services/working-copies.service';
import { AppInfoStore } from '../../core/app-info.store';
import { quietRequest } from '../../core/http-error.interceptor';
import { ENGINE_TIMEOUT_MS, editorRequest, engineRequest, isTimeout, retryReads } from '../../core/request-timeout.interceptor';
import { lineOf } from '../../core/mztab-version';

/** After an accepted batch, structural validation starts once the user has been idle this long [my call]. */
export const IDLE_MS = 1500;
/** While a run is RUNNING, the copy is read again at this interval. */
export const POLL_MS = 1000;

export const CONFLICT_NOTICE =
  'This file was changed in another tab, so this change and any made after it were not saved. It was reloaded: please make them again.';
export const COPY_REPLACED_NOTICE = 'This copy was replaced (deleted and created again). The editor reloaded it.';
/** The `error` of the server's 409 for a generation that is not the copy's. */
const COPY_REPLACED_CODE = 'copy_replaced';
/** The `error` of the server's 409 for an undo or redo with nothing to undo or redo: the copy is as this tab knows it. */
const NOTHING_TO_UNDO_CODE = 'nothing_to_undo';
export const LAYOUT_CHANGED_NOTICE = 'The lines or columns changed before this change was saved, so it was not saved. The file was reloaded: please make the change again.';
export const LINES_MOVED_NOTICE =
  'The lines or columns changed after this message was checked, so its line number may name another line now. Wait for the next check, then try again.';
/**
 * The notice for a CV message whose lines moved since its CV check. Not "wait for the next check": a structural
 * check keeps the CV messages with the line numbers of their CV check, so only a CV check gives them new ones.
 */
export const cvLinesMovedNotice = (revision: number) =>
  `This message is from the CV check of revision ${revision}; the lines have changed since. Press Check CV rules to check them again.`;
/**
 * The failure the store hands on when it cannot tell whether a change was saved (the copy moved on under it, so the
 * views were reloaded and the notice {@link MAYBE_SAVED_NOTICE} was shown). It has the status 409: the views, the
 * quick fix dialog and undo/redo already treat that as "the host reloaded and said so", and add no message.
 */
export class MaybeSavedError extends HttpErrorResponse {
  constructor() {
    super({ status: 409, statusText: 'Maybe saved', error: { message: MAYBE_SAVED_NOTICE } });
  }
}

export const SAVE_FAILED = 'This change could not be saved. Please try again.';
export const MAYBE_SAVED_NOTICE =
  'The copy changed while this change was being saved, so it may or may not have been saved. The file was reloaded: please check it.';
export const CHECK_FAILED = 'The file could not be checked.';
/** How often a check refused as busy (503) is asked for again by itself, after the server's Retry-After. */
export const BUSY_RETRIES = 3;
/** The wait when a 503 gives no usable Retry-After, and the longest wait taken from one, in seconds. */
const BUSY_DEFAULT_S = 5;
const BUSY_MAX_S = 60;
export const JSON_NOT_READY = 'JSON is offered once the latest changes are checked and have no errors.';
export const JSON_FAILED = 'The JSON could not be downloaded. Please try again.';

export type EditOps = NonNullable<EditBatch['operations']>;
/** What an import preview asks for; the store adds the revision it knows. */
export type ImportChoices = Omit<ImportRequest, 'revision'>;
/** An import preview as the store hands it out: with the generation of the copy it was made of. */
export type StoredImportPreview = ImportPreview & { generation?: string | null };

/**
 * An import preview that was not sent: when its turn came, the copy was no longer the one it was made of (another
 * revision, e.g. after an edit of this tab, or another generation). Nothing was asked of the server, nothing said.
 */
export class PreviewOutOfDate extends Error {
  constructor() {
    super('The copy changed since the preview.');
  }
}

/**
 * Edits that change the layout of the text: lines move, columns are inserted, deleted, merged or renamed (a rename moves no column, but a cell edit queued behind it names the old header), cells are split
 * or a raw line is replaced. The line numbers and cell positions every view shows are stale afterwards. (A raw
 * line is counted whatever line it is: the store cannot tell a table row from another.)
 */
export const LAYOUT_OPS: ReadonlySet<string> = new Set([
  'DeleteLine',
  'InsertLine',
  'InsertLines',
  'MoveRow',
  'JoinWithPrevious',
  'InsertColumn',
  'DeleteColumn',
  'MergeColumns',
  'RenameColumn',
  'SplitCells',
  'SetRawLine',
]);
const changesLayout = (ops: readonly { op?: string }[] | undefined) =>
  (ops ?? []).some((o) => LAYOUT_OPS.has(o.op ?? ''));
type View = 'loading' | 'ready' | 'notFound' | 'notUtf8' | 'error';

/**
 * A queued change that was not sent: the copy was read again under it (a conflict, a copy created again, a change
 * that may have been saved) and the user was told so once. It was made on the text before, so it is refused like a
 * conflict (409), which the views, the quick fix dialog and undo/redo answer with no message of their own.
 */
export class NotSentError extends HttpErrorResponse {
  constructor() {
    super({ status: 409, statusText: 'Not sent', error: { message: 'The file was reloaded before this change was sent.' } });
  }
}

/**
 * The failure the store hands on when a change failed and the copy read again was another one (deleted and created
 * again): the new copy does not hold the change, whatever the old one did. The views were reloaded and
 * {@link COPY_REPLACED_NOTICE} was shown; the status 409 makes the views, the quick fix dialog, undo/redo and an
 * import ("stale") add no message of their own.
 */
export class CopyReplacedError extends HttpErrorResponse {
  constructor() {
    super({ status: 409, statusText: 'Copy replaced', error: { error: COPY_REPLACED_CODE, message: COPY_REPLACED_NOTICE } });
  }
}

/** A quick-fix preview was refused because the last check is not of the current text; a new one was started. */
export class NotCheckedYet extends Error {
  constructor() {
    super('Validate the current revision first.');
  }
}

const newChangeId = (): string =>
  typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => Math.floor(Math.random() * 16).toString(16));

const statusOf = (err: unknown) => (err instanceof HttpErrorResponse ? err.status : undefined);

/** The server's reason in a JSON error body, e.g. why an edit was refused (400). */
export function serverMessage(err: unknown): string | null {
  const body = err instanceof HttpErrorResponse ? err.error : null;
  const message = typeof body === 'object' && body ? (body as { message?: unknown }).message : undefined;
  return typeof message === 'string' && message.trim() ? message : null;
}

/** The wait the server asks for in a 503's Retry-After (seconds), or null for no usable one. */
function retryAfterMs(err: unknown): number | null {
  if (!(err instanceof HttpErrorResponse) || err.status !== 503) return null;
  const seconds = Number(err.headers?.get('Retry-After'));
  const s = Number.isFinite(seconds) && seconds > 0 ? seconds : BUSY_DEFAULT_S;
  return Math.min(s, BUSY_MAX_S) * 1000;
}

/** A poll that found the copy gone (404). */
const GONE = 'gone' as const;

/**
 * The state of one working copy and every request on it. The edit page provides it; the document views reach it
 * through WorkingCopySource.
 *
 * - Edits, undo, redo and applied quick fixes are queued: each request names the revision of the latest answer,
 *   so batches the user makes quickly one after another never conflict with each other. Not behind a batch that
 *   changes the layout (or an undo or redo, which may): an edit made while such a batch is queued names the line numbers
 *   of the text before it, so it names the revision of the moment it was made, and the server refuses it (409)
 *   rather than apply it to the line that now has that number. The same holds for an edit of a page the views
 *   read before the last layout change (`readAt`, a page's stamp), also once the move is answered: the views read
 *   their pages again only after `changed`, one round trip later.
 * - After each accepted batch, a structural run is requested once the user has been idle for {@link IDLE_MS}.
 *   While a run is RUNNING, `GET /{id}` is polled every {@link POLL_MS}.
 * - The CV rules are checked on request only ("Check CV rules"), and such a request is never replaced by a
 *   structural one: it cancels the idle wait, and until the server has accepted it (a refused one waits for its
 *   retry, or for Retry) any request for a check is sent as a CV check.
 * - A 409 from an edit, undo or redo means the copy changed elsewhere (another tab): the info is read again,
 *   `changed` tells the views to reload, and the conflict notice is shown. The re-read is part of the refused
 *   request's turn, and the requests queued behind it are not sent ({@link NotSentError}): they were made on the
 *   text before, and are refused once, with the one notice, rather than each with a notice of its own. Not a 409
 *   with nothing to undo or redo (a second undo queued behind the first): the copy is as this tab knows it, so it
 *   is read again quietly for `canUndo` and `canRedo`, and the requests behind it are sent.
 * - A copy deleted and created again (another `generation`) starts anew: what the store knew of the old one (its
 *   layout changes, CV checks, report) is dropped, the views reload, {@link COPY_REPLACED_NOTICE} is shown once and
 *   the new copy is checked as an opened one is.
 * - Every request has a client timeout ({@link editorRequest}: 30 s; 120 s for the import preview and the creation
 *   of the copy), except the JSON download. A read of the copy that gets no answer (status 0) is asked again after
 *   1 s and then 3 s ({@link retryReads}). A change is never sent again blindly: one that timed out goes through
 *   {@link committedOrFailed}, which sends it once more only when the copy read again shows it was not applied.
 */
@Injectable()
export class WorkingCopyStore {
  private readonly api = inject(WorkingCopiesService);
  private readonly appInfo = inject(AppInfoStore);
  private readonly destroyRef = inject(DestroyRef);

  readonly id = signal('');
  readonly view = signal<View>('loading');
  /** The server's message for a file that is not UTF-8 (it names the first such line). */
  readonly notUtf8 = signal<string | null>(null);
  /**
   * Why the copy could not be made, in the server's words: the engine could not read the file (400), the file
   * is over the editor's caps (422 too_large) or the storage is full (503). Null for a failure without a reason.
   */
  readonly openFailure = signal<string | null>(null);
  readonly info = signal<WorkingCopyInfo | undefined>(undefined);

  private readonly inFlight = signal(0);
  private readonly idlePending = signal(false);
  readonly saving = computed(() => this.inFlight() > 0);
  /** The last request for a check failed (not a run that failed: the request itself); Retry asks again. */
  readonly checkFailed = signal(false);
  /** The server's reason the request for a check failed (e.g. "Too many files are being checked"), if it gave one. */
  readonly checkFailure = signal<string | null>(null);
  private busyRetries = 0;
  private busyTimer: ReturnType<typeof setTimeout> | undefined;
  /** A CV check was asked for and the server has not accepted it yet. */
  private cvCheckPending = false;

  /**
   * The copy has a report (else `GET /{id}/report` answers 409): the server names its run (`reportRevision`), or a
   * check of this copy has finished (DONE) since it was opened (a server that does not name it).
   */
  readonly reportReady = signal(false);

  readonly validation = computed(() => this.info()?.validation ?? null);
  /** The messages of the last ended run, each NEW or OPEN against the run before. */
  readonly messages = computed(() => this.validation()?.messages ?? []);
  readonly fixed = computed(() => this.validation()?.fixed ?? []);
  readonly guidance = computed(() => this.validation()?.guidance ?? []);
  /** The last run is DONE and of the text as it is now: quick fixes can be previewed. */
  readonly current = computed(() => {
    const v = this.validation();
    return !!v && v.status === 'DONE' && !v.stale && v.revision === this.info()?.revision && !this.saving();
  });
  /** A check of the latest changes is pending or running. */
  readonly validating = computed(() => {
    const v = this.validation();
    if (this.idlePending() || this.saving()) return true;
    if (this.checkFailed()) return false;
    if (!v) return this.view() === 'ready';
    return v.status === 'RUNNING' || !!v.stale || v.revision !== this.info()?.revision;
  });
  /** JSON is offered only for the current text, read to its end, without an error. */
  readonly jsonReady = computed(() => {
    const v = this.validation();
    return (
      this.current() &&
      !!v?.parsed &&
      !(v.messages ?? []).some((t) => t.message?.message_type === 'error')
    );
  });
  /** The copy's repository rules (its session's for an upload, its own for a new file) decide whether CV rules can be checked. */
  readonly hasProfile = computed(() => {
    const kind = this.info()?.validationProfile?.kind;
    return kind === 'CATALOG' || kind === 'CUSTOM';
  });
  /**
   * A CV check that this page asked for has ended (DONE). A semantic run read again by a reload, a conflict or the
   * opening, without a request of this page, is of an earlier session.
   */
  private readonly cvCheckedHere = signal(false);
  /** The revision of the last CV check this page asked for and the server accepted; null if none. */
  private cvRequestedAt: number | null = null;
  /**
   * What the status line says about the CV messages shown: whether they are of the text as it is now. A structural
   * run keeps the CV messages of the last CV check (the session's at revision 0), so after a change they are shown
   * until the next CV check; a reopened copy shows those of a check of an earlier session. Nothing is said until a
   * check of the copy has ended (no messages are listed before), nor for a copy without a profile whose CV rules
   * were never checked.
   */
  readonly cvStatus = computed(() => {
    if (!this.reportReady()) return null;
    const copy = this.info();
    const checked = copy?.cvCheckedRevision;
    if (checked == null) return this.hasProfile() ? 'CV rules not checked yet.' : null;
    if (checked !== copy?.revision) return 'CV rules not checked since your last change.';
    return checked > 0 && !this.cvCheckedHere()
      ? `CV rules checked at revision ${checked}, in an earlier session: press Check CV rules to check them again.`
      : `CV rules checked at revision ${checked}.`;
  });

  /** The document changed under the views (undo, redo, a quick fix, a reload): they request again what they show. */
  readonly changed = new Subject<void>();
  /** Short texts for the snackbar. */
  readonly notices = new Subject<string>();

  private readonly jobs = new Subject<() => Observable<unknown>>();
  /** Queued requests that change the layout (or may), not yet answered. */
  private layoutChangesQueued = 0;
  /**
   * The revision of the last answer after which lines or columns may have moved (a batch that changes the layout, an undo or redo, a
   * reload after a conflict). An edit of a page read at an older revision names that revision, so the server
   * refuses it (409) and the views read again.
   */
  private lastLayoutRevision = -1;
  /**
   * Counts the times the copy was read again under the queued requests (and the user was told): a request queued
   * before is not sent.
   */
  private reloads = 0;
  /** Generations of copies that were replaced: a late answer about one of them is not taken. */
  private readonly retired = new Set<string>();
  private readonly idle = new Subject<boolean>();
  /** Each value starts polling anew: the previous poll, and a read it has in flight, are cancelled. */
  private readonly polls = new Subject<void>();
  private polling = false;
  /** The version the last ended run checked the text as; a change is announced. */
  private lastVersion: string | null | undefined;

  constructor() {
    this.destroyRef.onDestroy(() => clearTimeout(this.busyTimer));
    this.jobs.pipe(concatMap((run) => run().pipe(catchError(() => of(null)))), takeUntilDestroyed()).subscribe();
    this.idle
      .pipe(
        switchMap((start) => (start ? timer(IDLE_MS).pipe(map(() => true)) : of(false))),
        takeUntilDestroyed(),
      )
      .subscribe((fire) => {
        this.idlePending.set(false);
        if (fire) this.validate(false);
      });
    this.polls
      .pipe(
        switchMap(() =>
          defer(() => {
            this.polling = true;
            return timer(POLL_MS, POLL_MS).pipe(
              exhaustMap(() =>
                this.api.getWorkingCopy({ id: this.id() }, editorRequest()).pipe(
                  retryReads(),
                  catchError((err: unknown) => of(statusOf(err) === 404 ? GONE : null)),
                ),
              ),
              takeWhile((info) => info === null || (info !== GONE && info.validation?.status === 'RUNNING'), true),
              finalize(() => (this.polling = false)),
            );
          }),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((info) => {
        if (info === GONE) this.view.set('notFound');
        else if (info) this.setInfo(info);
      });
  }

  // ---- opening ----

  open(id: string): void {
    this.id.set(id);
    this.view.set('loading');
    this.info.set(undefined);
    this.reportReady.set(false);
    this.notUtf8.set(null);
    this.openFailure.set(null);
    this.lastVersion = undefined;
    this.retired.clear();
    this.create('UTF_8');
  }

  /** Creates the copy again, reading the upload as Windows-1252 ("Re-encode from Windows-1252"). */
  reencode(): void {
    this.view.set('loading');
    this.create('WINDOWS_1252');
  }

  private create(encoding: 'UTF_8' | 'WINDOWS_1252'): void {
    this.forget();
    this.api
      // the server copies the stored file: given as long as a request that waits for the engine, and not asked again
      .createWorkingCopy({ validation: this.id(), encoding }, editorRequest(ENGINE_TIMEOUT_MS))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (info) => {
          this.lastVersion = info.mzTabVersion ?? null;
          this.setInfo(info);
          this.view.set('ready');
          this.checkOnOpen(info);
        },
        error: (err: unknown) => {
          const body = err instanceof HttpErrorResponse ? err.error : null;
          if (statusOf(err) === 422 && body?.error === 'not_utf8') {
            this.notUtf8.set(typeof body.message === 'string' ? body.message : 'The file is not encoded as UTF-8.');
            this.view.set('notUtf8');
          } else {
            const status = statusOf(err);
            this.openFailure.set(status === 400 || status === 422 || status === 503 ? serverMessage(err) : null);
            this.view.set(status === 404 ? 'notFound' : 'error');
          }
        },
      });
  }

  /** A new copy counts its revisions anew, and nothing has been asked of it yet (also a copy created again). */
  private forget(): void {
    this.lastLayoutRevision = -1;
    this.cvCheckPending = false;
    this.cvCheckedHere.set(false);
    this.cvRequestedAt = null;
    this.reportReady.set(false);
    clearTimeout(this.busyTimer);
    this.busyRetries = 0;
    this.checkFailed.set(false);
    this.checkFailure.set(null);
  }

  /**
   * A copy that is opened is checked unless its last check is current or running. A new copy has no run: it is
   * checked, so that its messages and quick fixes refer to its lines. A reopened copy may have a check of an older
   * revision with none running (the page was left during the idle wait, the check failed, or the server
   * restarted: runs live in memory): its structure is checked again, whatever the kind of the last check. The CV
   * rules are checked on request only; the CV messages of an earlier CV check stay shown, and the status line
   * says which check they are of. A failed check of the current text is not asked again (that would loop): its
   * failure is shown, with Retry.
   */
  private checkOnOpen(info: WorkingCopyInfo): void {
    const v = info.validation;
    if (!v || (v.status !== 'RUNNING' && (v.stale || v.revision !== info.revision))) this.validate(false);
  }

  // ---- edits ----

  /**
   * Applies one batch; completes when it is accepted. A 409 reloads (and the error is passed on). `reload` tells
   * every view to read again (the batch changed more than the view that made it shows, e.g. the columns).
   */
  edit(ops: EditOps, reload = false, readAt: number | null = null): Observable<WorkingCopyInfo> {
    return this.enqueue(
      (revision, changeId, generation) =>
        this.api.editWorkingCopy({ id: this.id(), body: { revision, operations: ops, changeId, generation } }, editorRequest()),
      { reload, layout: changesLayout(ops), readAt },
    );
  }

  undo(): void {
    if (!this.info()?.canUndo) return;
    // undo names no line, so it may follow a layout change; what it brings back or takes away may change it
    this.enqueue((revision, changeId, generation) =>
      this.api.undoWorkingCopy({ id: this.id(), revision, changeId, generation }, editorRequest()), {
      reload: true,
      layout: true,
      linesNamed: false,
    }).subscribe({ error: (err: unknown) => this.failed(err) });
  }

  redo(): void {
    if (!this.info()?.canRedo) return;
    this.enqueue((revision, changeId, generation) =>
      this.api.redoWorkingCopy({ id: this.id(), revision, changeId, generation }, editorRequest()), {
      reload: true,
      layout: true,
      linesNamed: false,
    }).subscribe({ error: (err: unknown) => this.failed(err) });
  }

  /** Applies a quick fix's batch as it was previewed: a newer revision is refused (409) and reloads. */
  applyFix(preview: QuickFixPreview): Observable<WorkingCopyInfo> {
    return this.applyBatch(preview.batch);
  }

  /**
   * Applies an import's batch whole, as it was previewed, the way a quick fix is applied: one change, so one undo
   * takes it back. It is `'stale'`, and a new preview is of the copy as it is now, when:
   * - at its turn the copy this tab knows is no longer the one previewed: another revision (an edit of this tab,
   *   saved since or queued ahead of it) or another generation. It is not sent, and nothing is said or reloaded: the
   *   tab's own edits are not a change elsewhere;
   * - the server refuses it (409): the copy changed in another tab, or was created again, or a change queued before
   *   it may have been saved. The store has read the copy again and said so, as for any change.
   */
  applyImport(preview: StoredImportPreview): Observable<'applied' | 'stale'> {
    const batch = preview.batch;
    const outOfDate = () =>
      this.revision() !== (batch?.revision ?? -1) ||
      (preview.generation !== undefined && (this.info()?.generation ?? null) !== preview.generation);
    return this.applyBatch(batch, outOfDate).pipe(
      map(() => 'applied' as const),
      catchError((err: unknown) =>
        err instanceof PreviewOutOfDate || statusOf(err) === 409 ? of('stale' as const) : throwError(() => err),
      ),
    );
  }

  /**
   * A previewed batch, sent with the revision it was previewed at, a change id of its own and the copy's generation;
   * not sent if `outOfDate` says so at its turn.
   */
  private applyBatch(batch: EditBatch | undefined, outOfDate?: () => boolean): Observable<WorkingCopyInfo> {
    return this.enqueue((_, changeId, generation) =>
      this.api.editWorkingCopy({ id: this.id(), body: { ...(batch ?? {}), changeId, generation } }, editorRequest()), {
      sentAt: batch?.revision ?? null,
      reload: true,
      layout: changesLayout(batch?.operations),
      outOfDate,
    });
  }

  /**
   * Previews an import at the revision of the copy's last answer. A 409 means the copy is at another revision
   * (another tab changed it, or an edit of this one is on its way): the copy is read again and the preview asked
   * for once more, at the revision read.
   */
  previewImport(choices: ImportChoices): Observable<StoredImportPreview> {
    const send = () => {
      // the copy the preview is of: applying it to another generation is stale
      const generation = this.info()?.generation ?? null;
      return this.api
        // waits for the engine; not asked again by itself (the panel's Try again does)
        .previewWorkingCopyImport({ id: this.id(), body: { ...choices, revision: this.revision() } }, engineRequest())
        .pipe(map((p): StoredImportPreview => ({ ...p, generation })));
    };
    return defer(send).pipe(
      catchError((err: unknown) =>
        statusOf(err) === 409 ? this.reread().pipe(switchMap(() => defer(send))) : throwError(() => err),
      ),
    );
  }

  /**
   * Reads the copy again without a notice. When it moved on while this tab has no change on its way, it changed
   * elsewhere: lines may have moved, and the views read again. (A copy created again says so itself.)
   */
  private reread(): Observable<void> {
    const known = this.revision();
    return this.api.getWorkingCopy({ id: this.id() }, editorRequest()).pipe(
      retryReads(),
      map((info) => {
        const replaced = this.setInfo(info);
        const now = info.revision ?? 0;
        if (!replaced && !this.saving() && now > known) {
          this.lastLayoutRevision = Math.max(this.lastLayoutRevision, now);
          this.changed.next();
        }
      }),
    );
  }

  /** The user is still typing or moving around: a pending check waits for the pause. */
  activity(): void {
    if (this.idlePending()) this.idle.next(true);
  }

  private revision(): number {
    return this.info()?.revision ?? 0;
  }

  /** The revision of the copy's last answer: what a page read now shows at least. */
  currentRevision(): number {
    return this.revision();
  }

  /**
   * The revision whose text a message's line numbers are of, which an action started from the message names as
   * `readAt`: the CV check's for a CV message (a structural run keeps the CV messages of the last CV check), else
   * the run's whose messages are shown (not the revision of a run still running, nor the current one).
   */
  messageRevision(m: ValidationMessage): number {
    const copy = this.info();
    const v = copy?.validation;
    const own = v?.messagesRevision ?? v?.revision ?? this.revision();
    return m.category === 'cross_check' ? (copy?.cvCheckedRevision ?? own) : own;
  }

  /** Whether lines or columns may have moved since `readAt`: line numbers read at that revision may name other lines. */
  linesMovedSince(readAt: number): boolean {
    return readAt < this.lastLayoutRevision;
  }

  /** The last CV check is older than the last layout change: the kept CV messages' lines may name other lines. */
  cvLinesMoved(): boolean {
    const checked = this.info()?.cvCheckedRevision;
    return checked != null && this.linesMovedSince(checked);
  }

  /**
   * What to tell the user when a message's place is not shown because lines moved since it was checked: for a
   * structural message, to wait for the next check (it comes after each change); for a CV message, which a
   * structural check keeps with the line numbers of its CV check, to check the CV rules again.
   */
  linesMovedNotice(m: ValidationMessage): string {
    const checked = this.info()?.cvCheckedRevision;
    return m.category === 'cross_check' && checked != null && this.cvLinesMoved()
      ? cvLinesMovedNotice(checked)
      : LINES_MOVED_NOTICE;
  }

  /**
   * Queues a request that changes the copy. The request is made when it is its turn, so it names the revision of
   * the answer before it; unless a request that changes the layout was queued when it was made (and it names lines),
   * then it names the revision of that moment. `reload` makes the views request their pages again (they did not
   * make the change).
   */
  private enqueue(
    request: (revision: number, changeId: string, generation: string | undefined) => Observable<WorkingCopyInfo>,
    {
      reload = false,
      layout = false,
      linesNamed = true,
      readAt = null,
      sentAt = null,
      outOfDate,
    }: {
      reload?: boolean;
      layout?: boolean;
      linesNamed?: boolean;
      readAt?: number | null;
      /** The revision the request names, when it does not take the one it is given (a previewed batch's). */
      sentAt?: number | null;
      /** Asked at its turn: true drops the request with {@link PreviewOutOfDate}, before anything is sent. */
      outOfDate?: () => boolean;
    },
  ): Observable<WorkingCopyInfo> {
    const result = new AsyncSubject<WorkingCopyInfo>();
    const issued = this.revision();
    const reloads = this.reloads;
    const pinned = linesNamed && this.layoutChangesQueued > 0;
    if (layout) this.layoutChangesQueued++;
    this.inFlight.update((n) => n + 1);
    this.jobs.next(() => {
      const now = this.revision();
      // decided when it is sent: a page read before the last layout change has numbers that may name other lines now
      const stale = readAt != null && this.linesMovedSince(readAt);
      const revision = stale ? readAt : readAt == null && pinned ? issued : now;
      // refused because the layout changed under it, not because of another tab
      const moved = revision !== now;
      const changeId = newChangeId();
      // the copy this view read: a copy deleted and created again refuses it (409)
      const generation = this.info()?.generation ?? undefined;
      // the copy was read again under it, and the user told: it was made on the text before
      const send =
        reloads !== this.reloads
          ? throwError(() => new NotSentError())
          : outOfDate?.()
            ? throwError(() => new PreviewOutOfDate())
            : defer(() => request(revision, changeId, generation)).pipe(
                // before the next queued request is sent: it must name the revision this one produced, if it did.
                // A timeout of a change that was not applied sends it once more, as it was: the same change id and
                // revision, so the server refuses it (409) if the first one was committed after all. Only a first send
                // committed by the time the second is answered (the second waits up to 5 s for the copy's lock) is
                // then found in the copy and counts as saved; a later commit leaves the resend refused as a conflict.
                catchError((err: unknown) =>
                  this.committedOrFailed(err, sentAt ?? revision, changeId, generation, () =>
                    defer(() => request(revision, changeId, generation)).pipe(
                      catchError((again: unknown) => this.committedOrFailed(again, sentAt ?? revision, changeId, generation)),
                      // refused because the first send was committed meanwhile (the usual case: a slow edit)
                      catchError((again: unknown) => this.resendRefused(again, changeId, generation)),
                    ),
                  ),
                ),
                // a conflict reads the copy again in this turn, so the requests queued behind it see the reload
                catchError((err: unknown) => {
                  // the store has read the copy again and said so already
                  const handled = err instanceof MaybeSavedError || err instanceof NotSentError || err instanceof CopyReplacedError;
                  if (statusOf(err) !== 409 || handled) return throwError(() => err);
                  const code = err instanceof HttpErrorResponse ? err.error?.error : undefined;
                  // the copy is the one this tab knows: nothing changed elsewhere, the requests behind it are sent
                  if (code === NOTHING_TO_UNDO_CODE) return this.quietReread().pipe(switchMap(() => throwError(() => err)));
                  const replaced = code === COPY_REPLACED_CODE;
                  return this.conflict(replaced ? COPY_REPLACED_NOTICE : moved ? LAYOUT_CHANGED_NOTICE : CONFLICT_NOTICE).pipe(
                    switchMap(() => throwError(() => err)),
                  );
                }),
              );
      return send.pipe(
        tap({
          next: (info) => {
            if (layout) this.layoutChanged(info);
            this.setInfo(info);
            this.idlePending.set(true);
            this.idle.next(true);
            if (reload) this.changed.next();
            result.next(info);
            result.complete();
          },
          error: (err: unknown) => {
            if (statusOf(err) === 404) this.view.set('notFound');
            result.error(err);
          },
          finalize: () => {
            this.inFlight.update((n) => n - 1);
            if (layout) this.layoutChangesQueued--;
          },
        }),
      );
    });
    return result.asObservable();
  }

  /**
   * A request failed in a way that does not say whether the server applied it (a 503 after the commit, a lost
   * answer, a 500): the copy is read again before anything is said, and before the next queued request is sent. Each
   * change carries a `changeId` the server keeps with the change (`lastChangeId`), so the answer is certain:
   * - `lastChangeId` is this change's: it was applied; the info is its answer (revision, canUndo ...).
   * - else a copy still at `sent` was not changed: the failure is reported as it is.
   * - else the copy moved on, and the change may or may not be in it (another tab wrote meanwhile): the info is taken
   *   (the next edit must name it), the views reload, the notice says "may", and the error is a
   *   {@link MaybeSavedError}, which nobody answers with "could not be saved".
   * - a copy of another generation (deleted and created again), whatever its revision, does not hold the change: it is
   *   taken, the views reload, {@link COPY_REPLACED_NOTICE} is shown once and the error is a {@link CopyReplacedError}.
   *   When a poll (or a reload) took the new copy first, while the change was in flight, it has said so already: the
   *   error is a {@link CopyReplacedError}, and nothing more is said.
   * A 4xx was refused before changing anything, and a copy that cannot be read (after the retries of a read) leaves
   * the failure as it was. A lost answer whose re-read comes before the server's delayed commit reports a failure
   * (the safe direction): the next edit is then refused (409) and the copy reloaded.
   *
   * A timeout of a change that was not applied (`lastChangeId` is not its own, the copy is still at `sent` and of the
   * `generation` it was sent to) is sent once more by `resend`, unchanged: if the first one was committed after the
   * re-read, the server refuses the second (409). It is never applied twice. {@link resendRefused} finds the change
   * in the copy, and counts it as saved, only when the first send has committed by the time the second is answered
   * (or while the second waits, up to 5 s, for the copy's lock); a first send committed later still leaves the
   * resend refused, on the conflict path.
   */
  private committedOrFailed(
    err: unknown,
    sent: number,
    changeId: string,
    generation: string | undefined,
    resend?: () => Observable<WorkingCopyInfo>,
  ): Observable<WorkingCopyInfo> {
    const status = statusOf(err);
    if (status === undefined || (status !== 0 && status < 500)) return throwError(() => err);
    return this.api.getWorkingCopy({ id: this.id() }, editorRequest()).pipe(
      retryReads(),
      catchError(() => throwError(() => err)),
      switchMap((info) => {
        if (info.lastChangeId === changeId) return of(info);
        // a late answer about a copy that was replaced since tells nothing about this change
        if (this.isRetired(info)) return throwError(() => err);
        // the copy the change was sent to was replaced, and a poll (or a reload) took the new one first and said so
        // (as conflict() does): it cannot hold the change, and nothing more is said
        const otherCopy = generation !== undefined && !!info.generation && info.generation !== generation;
        if (otherCopy && !this.isReplacement(info)) return throwError(() => new CopyReplacedError());
        const now = info.revision ?? 0;
        const sameCopy = !this.isReplacement(info) && (info.generation ?? undefined) === generation;
        if (resend && isTimeout(err) && now === sent && sameCopy) return resend();
        // a copy created again counts its revisions anew: it is taken whatever its revision
        if (now <= sent && !this.isReplacement(info)) return throwError(() => err);
        const replaced = this.setInfo(info, { force: true, reloading: true });
        this.lastLayoutRevision = Math.max(this.lastLayoutRevision, now);
        this.reloads++;
        this.changed.next();
        // a copy created again does not hold the change, whatever the old one did
        this.notices.next(replaced ? COPY_REPLACED_NOTICE : MAYBE_SAVED_NOTICE);
        return throwError(() => (replaced ? new CopyReplacedError() : new MaybeSavedError()));
      }),
    );
  }

  /**
   * A resend refused (409): the first send may have been committed after the re-read that let it be resent (a slow
   * change). The copy is read again: if it names this change as its last, of the generation it was sent to, the
   * change was applied once, by the first send, and that is its answer (no notice, the queue goes on). Anything else,
   * and a failure the store has already handled or a copy that cannot be read, goes on to the conflict path.
   */
  private resendRefused(err: unknown, changeId: string, generation: string | undefined): Observable<WorkingCopyInfo> {
    const handled = err instanceof MaybeSavedError || err instanceof NotSentError || err instanceof CopyReplacedError;
    const code = err instanceof HttpErrorResponse ? err.error?.error : undefined;
    if (statusOf(err) !== 409 || handled || code === COPY_REPLACED_CODE || code === NOTHING_TO_UNDO_CODE) {
      return throwError(() => err);
    }
    return this.api.getWorkingCopy({ id: this.id() }, editorRequest()).pipe(
      retryReads(),
      catchError(() => throwError(() => err)),
      switchMap((info) =>
        info.lastChangeId === changeId && (info.generation ?? undefined) === generation && !this.isReplacement(info)
          ? of(info)
          : throwError(() => err),
      ),
    );
  }

  /** Undo or redo failed: a conflict reloads and a vanished copy shows "not found"; else the server's reason. */
  private failed(err: unknown): void {
    const status = statusOf(err);
    if (status === 409 || status === 404) return;
    this.notices.next((status === 400 && serverMessage(err)) || SAVE_FAILED);
  }

  /**
   * Reads the copy again in the turn of an undo or redo that had nothing to do (two quick clicks, the second queued
   * before the first was answered): `canUndo` and `canRedo` follow the server. The copy is the one this tab knows,
   * so the views do not reload, nothing is said and the requests queued behind it are sent. Completes also when the
   * copy cannot be read (a vanished one shows "not found"); the buttons then stay as they were.
   */
  private quietReread(): Observable<void> {
    return this.api.getWorkingCopy({ id: this.id() }, editorRequest()).pipe(
      retryReads(),
      map((info) => {
        this.setInfo(info);
      }),
      catchError((err: unknown) => {
        if (statusOf(err) === 404) this.view.set('notFound');
        return of(undefined);
      }),
    );
  }

  /** Lines may have moved up to this answer: pages read before it are stale for edits that name lines. */
  private layoutChanged(info: WorkingCopyInfo): void {
    this.lastLayoutRevision = Math.max(this.lastLayoutRevision, info.revision ?? 0);
  }

  /**
   * The copy changed elsewhere, or under a queued edit: read it again and let the views reload, with one notice (a
   * copy created again says so, whatever the refusal said). Completes when that is done, also when the copy cannot
   * be read (the page then shows "not found" or the error).
   */
  private conflict(notice: string): Observable<void> {
    return this.api.getWorkingCopy({ id: this.id() }, editorRequest()).pipe(
      retryReads(),
      map((info) => {
        // a late answer about a copy that was replaced since: the replacement was taken and announced
        if (this.isRetired(info)) return;
        const replaced = this.setInfo(info, { force: true, reloading: true });
        // the copy shown was already the new one (a poll found it first, and said so)
        if (!replaced && notice === COPY_REPLACED_NOTICE) return;
        // what changed elsewhere is not known: any line may have moved
        this.lastLayoutRevision = Math.max(this.lastLayoutRevision, info.revision ?? 0);
        this.reloads++;
        this.changed.next();
        this.notices.next(replaced ? COPY_REPLACED_NOTICE : notice);
      }),
      catchError((err: unknown) => {
        this.view.set(statusOf(err) === 404 ? 'notFound' : 'error');
        return of(undefined);
      }),
    );
  }

  /** Reads the copy again, e.g. after a download was refused because it changed. */
  reload(): void {
    this.api
      .getWorkingCopy({ id: this.id() }, editorRequest())
      .pipe(retryReads(), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (info) => this.setInfo(info, { force: true }),
        error: (err: unknown) => {
          if (statusOf(err) === 404) this.view.set('notFound');
        },
      });
  }

  // ---- validation ----

  /**
   * Requests a run (semantic: also the CV rules of the session's profile) and follows it until it ends. Its
   * answer restarts polling, so a read sent before it (which may still show the previous run) cannot end the
   * poll. A failed request leaves {@link checkFailed} set until the next request, with the server's reason in
   * {@link checkFailure}. A request refused as busy (503) is made again after the Retry-After the server gave, up
   * to {@link BUSY_RETRIES} times; a request of the user (Retry) starts the count anew. A CV check cancels the
   * idle wait (it checks the structure too), and any request made before the server accepted it is sent as one.
   */
  validate(semantic: boolean): void {
    this.busyRetries = 0;
    if (semantic) {
      this.cvCheckPending = true;
      if (this.idlePending()) this.idle.next(false);
    }
    this.request(semantic);
  }

  private request(asked: boolean): void {
    const semantic = asked || this.cvCheckPending;
    clearTimeout(this.busyTimer);
    this.checkFailed.set(false);
    this.checkFailure.set(null);
    this.api
      .validateWorkingCopy({ id: this.id(), semantic }, editorRequest())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (info) => {
          const accepted = () => {
            if (!semantic) return;
            this.cvCheckPending = false;
            this.cvRequestedAt = info.validation?.revision ?? info.revision ?? 0;
          };
          accepted();
          // a copy created again forgets what was asked of the old one, not this request
          if (this.setInfo(info, { checkAnswer: true })) accepted();
        },
        error: (err: unknown) => {
          if (statusOf(err) === 404) {
            this.view.set('notFound');
            return;
          }
          this.checkFailed.set(true);
          const wait = retryAfterMs(err);
          this.checkFailure.set(wait === null ? null : serverMessage(err));
          if (wait !== null && this.busyRetries < BUSY_RETRIES) {
            this.busyRetries++;
            this.busyTimer = setTimeout(() => this.request(semantic), wait);
          }
        },
      });
  }

  /**
   * Previews a quick fix for message `messageIndex` of the last run. A 409 means the run is not of the current
   * text: a run is requested (unless one is coming) and the preview fails with {@link NotCheckedYet}.
   */
  previewFix(fixId: string, messageIndex: number, all: boolean): Observable<QuickFixPreview> {
    return this.api
      .previewWorkingCopyQuickFix({ id: this.id(), fixId, message: messageIndex, all }, editorRequest())
      .pipe(
        catchError((err: unknown) => {
          if (statusOf(err) !== 409) return throwError(() => err);
          const v = this.validation();
          if (!this.idlePending() && v?.status !== 'RUNNING') this.validate(false);
          return throwError(() => new NotCheckedYet());
        }),
      );
  }

  /**
   * Takes a new state of the copy, unless it is older than the one shown, which a slow answer can be:
   * - a lower copy revision (a poll that crossed an edit);
   * - for the same copy revision, an ended run of a lower revision than the running one shown (a poll that
   *   crossed the request for the next run).
   * `force` (a reload after a conflict) takes it anyway, as it may go back. Starts polling while a run is
   * RUNNING (anew for `checkAnswer`, the answer to a request for a check), and announces a version change when a
   * run has ended.
   *
   * A copy of another generation (deleted and created again) is taken whatever its revision, which counts anew:
   * what was known of the old copy is dropped ({@link forget}), the views reload and {@link COPY_REPLACED_NOTICE} is
   * shown, unless the caller (`reloading`: a reload that says so itself) does. It is checked as an opened copy is
   * ({@link checkOnOpen}), once: not when this is the answer to a check, which was of the new copy. A late answer
   * about the old copy is not taken. Returns whether the copy was replaced.
   */
  private setInfo(
    info: WorkingCopyInfo,
    { force = false, checkAnswer = false, reloading = false }: { force?: boolean; checkAnswer?: boolean; reloading?: boolean } = {},
  ): boolean {
    const shown = this.info();
    if (this.isRetired(info)) return false;
    const replaced = this.isReplacement(info);
    if (!force && !replaced && shown && this.isOlder(info, shown)) return false;
    if (replaced) {
      this.retired.add(shown!.generation!);
      this.forget();
      this.lastVersion = info.mzTabVersion ?? null;
      // queued requests name the old copy: they are not sent
      this.reloads++;
    }
    this.info.set(info);
    // a layout change of an earlier session (or another tab) is known from the first answer on
    this.lastLayoutRevision = Math.max(this.lastLayoutRevision, info.layoutRevision ?? 0);
    const v = info.validation;
    if (info.reportRevision != null || v?.status === 'DONE') this.reportReady.set(true);
    // only a run of the CV check asked for here, or a later one; not one that an earlier session left
    const requested = this.cvRequestedAt;
    if (v?.status === 'DONE' && v.semantic && requested !== null && (v.revision ?? -1) >= requested) {
      this.cvCheckedHere.set(true);
    }
    if (v?.status === 'RUNNING') {
      if (checkAnswer || !this.polling) this.polls.next();
    } else if (v) {
      this.announceVersion(info.mzTabVersion ?? null);
    }
    if (replaced && !reloading) {
      this.changed.next();
      this.notices.next(COPY_REPLACED_NOTICE);
    }
    if (replaced && !checkAnswer) {
      // the idle wait after an edit of the old copy would ask for a second check
      if (this.idlePending()) this.idle.next(false);
      // its answer restarts the poll if the check makes the copy RUNNING
      this.checkOnOpen(info);
    }
    return replaced;
  }

  /** Whether this answer is about another copy than the one shown: it was deleted and created again. */
  private isReplacement(info: WorkingCopyInfo): boolean {
    const shown = this.info()?.generation;
    return !!shown && !!info.generation && info.generation !== shown;
  }

  /** Whether this answer is about a copy that was deleted and created again since. */
  private isRetired(info: WorkingCopyInfo): boolean {
    return !!info.generation && this.retired.has(info.generation);
  }

  private isOlder(info: WorkingCopyInfo, shown: WorkingCopyInfo): boolean {
    if ((info.revision ?? 0) !== (shown.revision ?? 0)) return (info.revision ?? 0) < (shown.revision ?? 0);
    const now = shown.validation;
    const next = info.validation;
    if (now?.status !== 'RUNNING' || !next || next.status === 'RUNNING') return false;
    return (next.revision ?? -1) < (now.revision ?? -1);
  }

  private announceVersion(version: string | null): void {
    const before = this.lastVersion;
    this.lastVersion = version;
    if (before === undefined || version === null || version === before) return;
    if (lineOf(version) !== null && lineOf(version) === lineOf(before)) return;
    this.notices.next(this.versionNotice(version));
  }

  /** "This file is now checked as mzTab-M 2.1.", or why it cannot be: the form's unsupported and unavailable texts. */
  private versionNotice(version: string): string {
    const line = lineOf(version) ?? version;
    const engines = this.appInfo.info()?.engines;
    const engine = engines?.find((e) => e.line === line);
    if (engines && !engine) return `mzTab-M ${line} is not supported by this validator.`;
    if (engine && engine.available === false) {
      return `The mzTab-M ${line} validator is temporarily unavailable. Please try again later.`;
    }
    return `This file is now checked as mzTab-M ${line}.`;
  }

  // ---- downloads ----

  /**
   * The JSON of the current text. A 409 (no longer valid) reads the copy again, which disables the button; a
   * 404 shows "not found"; any other failure is said with the server's reason when it gives one.
   */
  downloadJson(): Observable<Blob> {
    // no client timeout: the JSON of a large file can take long to arrive
    return this.api.downloadWorkingCopy$Json({ id: this.id(), format: 'json' }, quietRequest()).pipe(
      catchError((err: unknown) => {
        const status = statusOf(err);
        if (status === 409) {
          this.notices.next(JSON_NOT_READY);
          this.reload();
        } else if (status === 404) {
          this.view.set('notFound');
        } else {
          blobMessage(err).subscribe((m) => this.notices.next(m ?? JSON_FAILED));
        }
        return throwError(() => err);
      }),
    );
  }
}

/** The server's reason in an error body that came as a Blob (a download's error is read as one). */
function blobMessage(err: unknown): Observable<string | null> {
  const body = err instanceof HttpErrorResponse ? err.error : null;
  if (!(body instanceof Blob)) return of(serverMessage(err));
  return defer(() => from(body.text())).pipe(
    map((text) => {
      try {
        const m = (JSON.parse(text) as { message?: unknown }).message;
        return typeof m === 'string' && m.trim() ? m : null;
      } catch {
        return null;
      }
    }),
    catchError(() => of(null)),
  );
}
