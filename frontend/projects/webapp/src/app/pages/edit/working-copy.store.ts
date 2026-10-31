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
import { QuickFixPreview } from '../../api/models/quick-fix-preview';
import { ValidationRecord } from '../../api/models/validation-record';
import { WorkingCopyInfo } from '../../api/models/working-copy-info';
import { ValidationsService } from '../../api/services/validations.service';
import { WorkingCopiesService } from '../../api/services/working-copies.service';
import { AppInfoStore } from '../../core/app-info.store';
import { quietRequest } from '../../core/http-error.interceptor';
import { lineOf } from '../../core/mztab-version';

/** After an accepted batch, structural validation starts once the user has been idle this long [my call]. */
export const IDLE_MS = 1500;
/** While a run is RUNNING, the copy is read again at this interval. */
export const POLL_MS = 1000;

export const CONFLICT_NOTICE = 'This file was changed in another tab. It was reloaded.';
export const SAVE_FAILED = 'This change could not be saved. Please try again.';
export const CHECK_FAILED = 'The file could not be checked.';
export const JSON_NOT_READY = 'JSON is offered once the latest changes are checked and have no errors.';
export const JSON_FAILED = 'The JSON could not be downloaded. Please try again.';

export type EditOps = NonNullable<EditBatch['operations']>;
type View = 'loading' | 'ready' | 'notFound' | 'notUtf8' | 'error';

/** A quick-fix preview was refused because the last check is not of the current text; a new one was started. */
export class NotCheckedYet extends Error {
  constructor() {
    super('Validate the current revision first.');
  }
}

const statusOf = (err: unknown) => (err instanceof HttpErrorResponse ? err.status : undefined);

/** The server's reason in a JSON error body, e.g. why an edit was refused (400). */
export function serverMessage(err: unknown): string | null {
  const body = err instanceof HttpErrorResponse ? err.error : null;
  const message = typeof body === 'object' && body ? (body as { message?: unknown }).message : undefined;
  return typeof message === 'string' && message.trim() ? message : null;
}

/** A poll that found the copy gone (404). */
const GONE = 'gone' as const;

/**
 * The state of one working copy and every request on it. The edit page provides it; the document views reach it
 * through WorkingCopySource.
 *
 * - Edits, undo, redo and applied quick fixes are queued: each request names the revision of the latest answer,
 *   so batches the user makes quickly one after another never conflict with each other.
 * - After each accepted batch, a structural run is requested once the user has been idle for {@link IDLE_MS}.
 *   While a run is RUNNING, `GET /{id}` is polled every {@link POLL_MS}.
 * - A 409 from an edit, undo or redo means the copy changed elsewhere (another tab): the info is read again,
 *   `changed` tells the views to reload, and the conflict notice is shown.
 */
@Injectable()
export class WorkingCopyStore {
  private readonly api = inject(WorkingCopiesService);
  private readonly validations = inject(ValidationsService);
  private readonly appInfo = inject(AppInfoStore);
  private readonly destroyRef = inject(DestroyRef);

  readonly id = signal('');
  readonly view = signal<View>('loading');
  /** The server's message for a file that is not UTF-8 (it names the first such line). */
  readonly notUtf8 = signal<string | null>(null);
  readonly info = signal<WorkingCopyInfo | undefined>(undefined);
  /** The validation the copy was made from: its profile decides whether CV rules can be checked. */
  readonly record = signal<ValidationRecord | undefined>(undefined);

  private readonly inFlight = signal(0);
  private readonly idlePending = signal(false);
  readonly saving = computed(() => this.inFlight() > 0);
  /** The last request for a check failed (not a run that failed: the request itself); Retry asks again. */
  readonly checkFailed = signal(false);

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
  readonly hasProfile = computed(() => {
    const kind = this.record()?.validationProfile?.kind;
    return kind === 'CATALOG' || kind === 'CUSTOM';
  });

  /** The document changed under the views (undo, redo, a quick fix, a reload): they request again what they show. */
  readonly changed = new Subject<void>();
  /** Short texts for the snackbar. */
  readonly notices = new Subject<string>();

  private readonly jobs = new Subject<() => Observable<unknown>>();
  private readonly idle = new Subject<boolean>();
  /** Each value starts polling anew: the previous poll, and a read it has in flight, are cancelled. */
  private readonly polls = new Subject<void>();
  private polling = false;
  /** The version the last ended run checked the text as; a change is announced. */
  private lastVersion: string | null | undefined;

  constructor() {
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
                this.api.getWorkingCopy({ id: this.id() }, quietRequest()).pipe(
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
    this.notUtf8.set(null);
    this.lastVersion = undefined;
    this.create('UTF_8');
    this.validations
      .getValidation({ id }, quietRequest())
      .pipe(
        catchError(() => of(undefined)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((r) => this.record.set(r));
  }

  /** Creates the copy again, reading the upload as Windows-1252 ("Re-encode from Windows-1252"). */
  reencode(): void {
    this.view.set('loading');
    this.create('WINDOWS_1252');
  }

  private create(encoding: 'UTF_8' | 'WINDOWS_1252'): void {
    this.api
      .createWorkingCopy({ validation: this.id(), encoding }, quietRequest())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (info) => {
          this.lastVersion = info.mzTabVersion ?? null;
          this.setInfo(info);
          this.view.set('ready');
          // a new copy has no run yet: check it, so that its messages and quick fixes refer to its lines
          if (!info.validation) this.validate(false);
        },
        error: (err: unknown) => {
          const body = err instanceof HttpErrorResponse ? err.error : null;
          if (statusOf(err) === 422 && body?.error === 'not_utf8') {
            this.notUtf8.set(typeof body.message === 'string' ? body.message : 'The file is not encoded as UTF-8.');
            this.view.set('notUtf8');
          } else {
            this.view.set(statusOf(err) === 404 ? 'notFound' : 'error');
          }
        },
      });
  }

  // ---- edits ----

  /**
   * Applies one batch; completes when it is accepted. A 409 reloads (and the error is passed on). `reload` tells
   * every view to read again (the batch changed more than the view that made it shows, e.g. the columns).
   */
  edit(ops: EditOps, reload = false): Observable<WorkingCopyInfo> {
    return this.enqueue(
      () => this.api.editWorkingCopy({ id: this.id(), body: { revision: this.revision(), operations: ops } }),
      reload,
    );
  }

  undo(): void {
    if (!this.info()?.canUndo) return;
    this.enqueue(() => this.api.undoWorkingCopy({ id: this.id(), revision: this.revision() }), true).subscribe({
      error: (err: unknown) => this.failed(err),
    });
  }

  redo(): void {
    if (!this.info()?.canRedo) return;
    this.enqueue(() => this.api.redoWorkingCopy({ id: this.id(), revision: this.revision() }), true).subscribe({
      error: (err: unknown) => this.failed(err),
    });
  }

  /** Applies a quick fix's batch as it was previewed: a newer revision is refused (409) and reloads. */
  applyFix(preview: QuickFixPreview): Observable<WorkingCopyInfo> {
    return this.enqueue(() => this.api.editWorkingCopy({ id: this.id(), body: preview.batch ?? {} }), true);
  }

  /** The user is still typing or moving around: a pending check waits for the pause. */
  activity(): void {
    if (this.idlePending()) this.idle.next(true);
  }

  private revision(): number {
    return this.info()?.revision ?? 0;
  }

  /**
   * Queues a request that changes the copy. The request is made when it is its turn, so it names the revision of
   * the answer before it. `reload` makes the views request their pages again (they did not make the change).
   */
  private enqueue(request: () => Observable<WorkingCopyInfo>, reload = false): Observable<WorkingCopyInfo> {
    const result = new AsyncSubject<WorkingCopyInfo>();
    this.inFlight.update((n) => n + 1);
    this.jobs.next(() =>
      defer(request).pipe(
        tap({
          next: (info) => {
            this.setInfo(info);
            this.idlePending.set(true);
            this.idle.next(true);
            if (reload) this.changed.next();
            result.next(info);
            result.complete();
          },
          error: (err: unknown) => {
            if (statusOf(err) === 409) this.conflict();
            if (statusOf(err) === 404) this.view.set('notFound');
            result.error(err);
          },
          finalize: () => this.inFlight.update((n) => n - 1),
        }),
      ),
    );
    return result.asObservable();
  }

  /** Undo or redo failed: a conflict reloads and a vanished copy shows "not found"; else the server's reason. */
  private failed(err: unknown): void {
    const status = statusOf(err);
    if (status === 409 || status === 404) return;
    this.notices.next((status === 400 && serverMessage(err)) || SAVE_FAILED);
  }

  /** The copy changed elsewhere: read it again and let the views reload. */
  private conflict(): void {
    this.api
      .getWorkingCopy({ id: this.id() }, quietRequest())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (info) => {
          this.setInfo(info, true);
          this.changed.next();
          this.notices.next(CONFLICT_NOTICE);
        },
        error: (err: unknown) => this.view.set(statusOf(err) === 404 ? 'notFound' : 'error'),
      });
  }

  /** Reads the copy again, e.g. after a download was refused because it changed. */
  reload(): void {
    this.api
      .getWorkingCopy({ id: this.id() }, quietRequest())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (info) => this.setInfo(info, true),
        error: (err: unknown) => {
          if (statusOf(err) === 404) this.view.set('notFound');
        },
      });
  }

  // ---- validation ----

  /**
   * Requests a run (semantic: also the CV rules of the session's profile) and follows it until it ends. Its
   * answer restarts polling, so a read sent before it (which may still show the previous run) cannot end the
   * poll. A failed request leaves {@link checkFailed} set until the next request.
   */
  validate(semantic: boolean): void {
    this.checkFailed.set(false);
    this.api
      .validateWorkingCopy({ id: this.id(), semantic }, quietRequest())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (info) => this.setInfo(info, false, true),
        error: (err: unknown) => {
          if (statusOf(err) === 404) this.view.set('notFound');
          else this.checkFailed.set(true);
        },
      });
  }

  /**
   * Previews a quick fix for message `messageIndex` of the last run. A 409 means the run is not of the current
   * text: a run is requested (unless one is coming) and the preview fails with {@link NotCheckedYet}.
   */
  previewFix(fixId: string, messageIndex: number, all: boolean): Observable<QuickFixPreview> {
    return this.api
      .previewWorkingCopyQuickFix({ id: this.id(), fixId, message: messageIndex, all }, quietRequest())
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
   * RUNNING (`restartPoll`: anew), and announces a version change when a run has ended.
   */
  private setInfo(info: WorkingCopyInfo, force = false, restartPoll = false): void {
    const shown = this.info();
    if (!force && shown && this.isOlder(info, shown)) return;
    this.info.set(info);
    const v = info.validation;
    if (v?.status === 'RUNNING') {
      if (restartPoll || !this.polling) this.polls.next();
    } else if (v) {
      this.announceVersion(info.mzTabVersion ?? null);
    }
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
