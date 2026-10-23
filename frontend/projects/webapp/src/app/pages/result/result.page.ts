import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, catchError, exhaustMap, filter, map, of, scan, switchMap, takeWhile, timer } from 'rxjs';

import { ValidationRecord } from '../../api/models/validation-record';
import { ValidationsService } from '../../api/services/validations.service';
import { AppInfoStore } from '../../core/app-info.store';
import { MessagesTabComponent } from './messages-tab.component';
import { ResultBannerComponent } from './result-banner.component';
import { ResultHeaderComponent } from './result-header.component';

type Poll = { kind: 'record'; record: ValidationRecord } | { kind: 'error'; err: unknown };
type View = 'loading' | 'ready' | 'notFound' | 'error';
type TabId = 'messages' | 'metadata' | 'summary' | 'features' | 'evidence';

const POLL_MS = 2000;
const MAX_FAILURES = 3;
const DELETE_ERROR = 'The result could not be deleted. Please try again.';

const inProgress = (r: ValidationRecord) => r.status === 'QUEUED' || r.status === 'RUNNING';
const isNotFound = (p: Poll) => p.kind === 'error' && p.err instanceof HttpErrorResponse && p.err.status === 404;
const count = (n: number | undefined) => (n ?? 0).toLocaleString('en');

/** Result of a validation: polls while it runs, then shows banner, messages and section tabs. */
@Component({
  selector: 'app-result-page',
  imports: [
    MatProgressBarModule,
    RouterLink,
    MessagesTabComponent,
    ResultBannerComponent,
    ResultHeaderComponent,
  ],
  templateUrl: './result.page.html',
  styleUrl: './result.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResultPage {
  private readonly api = inject(ValidationsService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly appInfo = inject(AppInfoStore);
  protected readonly retentionDays = computed(() => this.appInfo.info()?.retentionDays);

  protected readonly view = signal<View>('loading');
  protected readonly record = signal<ValidationRecord | undefined>(undefined);
  protected readonly deleteError = signal<string | null>(null);
  protected readonly active = signal<TabId>('messages');
  protected readonly running = computed(() => {
    const r = this.record();
    return !!r && inProgress(r);
  });

  protected readonly tabs = computed(() => {
    const r = this.record();
    if (!r || r.status !== 'FINISHED') return [];
    const messages = { id: 'messages' as TabId, label: 'Messages', count: String(r.messages?.length ?? 0), disabled: false };
    if (r.outcome === 'NOT_PARSED') return [messages];
    const s = r.sections;
    const table = (id: TabId, label: string, size?: { rows?: number; columns?: number } | null) => ({
      id,
      label,
      count: size ? `${count(size.rows)} × ${count(size.columns)}` : '—',
      disabled: !size,
    });
    return [
      messages,
      { id: 'metadata' as TabId, label: 'Metadata', count: `${count(s?.metadataLines)} lines`, disabled: false },
      table('summary', 'Summary', s?.sml),
      table('features', 'Features', s?.smf),
      table('evidence', 'Evidence', s?.sme),
    ];
  });

  private confirmHandled = false;
  private dialogPending = false;
  protected readonly deleting = signal(false);

  constructor() {
    this.route.paramMap
      .pipe(
        map((p) => p.get('id') ?? ''),
        switchMap((id) => {
          this.view.set('loading');
          this.record.set(undefined);
          this.active.set('messages');
          return this.poll(id);
        }),
        takeUntilDestroyed(),
      )
      .subscribe((p) => this.apply(p));
  }

  /** Polls every 2 s while queued/running; tolerates up to 3 consecutive network failures. */
  private poll(id: string): Observable<Poll> {
    return timer(0, POLL_MS).pipe(
      exhaustMap(() =>
        this.api.getValidation({ id }).pipe(
          map((record): Poll => ({ kind: 'record', record })),
          catchError((err: unknown) => of<Poll>({ kind: 'error', err })),
        ),
      ),
      scan((acc, p) => ({ p, failures: p.kind === 'error' ? acc.failures + 1 : 0 }), {
        p: undefined as Poll | undefined,
        failures: 0,
      }),
      filter((a) => a.p!.kind === 'record' || isNotFound(a.p!) || a.failures >= MAX_FAILURES),
      map((a) => a.p!),
      takeWhile((p) => p.kind === 'record' && inProgress(p.record), true),
    );
  }

  private apply(p: Poll): void {
    if (p.kind === 'error') {
      this.view.set(isNotFound(p) ? 'notFound' : 'error');
      return;
    }
    this.record.set(p.record);
    this.view.set('ready');
    if (!this.confirmHandled && p.record.status !== undefined && !inProgress(p.record)) {
      this.confirmHandled = true;
      if (this.route.snapshot.queryParamMap.get('confirmDelete') === 'true') {
        this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { confirmDelete: null },
          replaceUrl: true,
        });
        void this.askDelete();
      }
    }
  }

  /** The dialog stack is loaded on demand to keep it out of the initial bundle. */
  protected async askDelete(): Promise<void> {
    if (this.deleting() || this.dialogPending) return;
    this.dialogPending = true;
    this.deleteError.set(null);
    try {
      const [{ MatDialog }, { DeleteConfirmDialogComponent }] = await Promise.all([
        import('@angular/material/dialog'),
        import('./delete-confirm-dialog.component'),
      ]);
      const ref = this.injector.get(MatDialog).open(DeleteConfirmDialogComponent, {
        id: 'delete-result-dialog',
        width: '440px',
        maxWidth: 'calc(100vw - 32px)',
      });
      ref.afterClosed().subscribe((confirmed) => {
        this.dialogPending = false;
        if (confirmed === true) this.delete();
      });
    } catch {
      this.dialogPending = false;
      this.deleteError.set(DELETE_ERROR);
    }
  }

  private delete(): void {
    const id = this.record()?.id;
    if (!id || this.deleting()) return;
    this.deleteError.set(null);
    this.deleting.set(true);
    this.api.deleteValidation({ id }).subscribe({
      // 404: the result is already gone, which is what the user wanted.
      next: () => void this.router.navigate(['/']),
      error: (err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 404) {
          void this.router.navigate(['/']);
          return;
        }
        this.deleting.set(false);
        this.deleteError.set(DELETE_ERROR);
      },
    });
  }

  protected select(id: TabId): void {
    this.active.set(id);
  }

  /** Roving-tabindex keyboard support for the tablist (arrows, Home, End); disabled tabs are skipped. */
  protected onTabKey(event: KeyboardEvent): void {
    const enabled = this.tabs().filter((t) => !t.disabled);
    const i = enabled.findIndex((t) => t.id === this.active());
    let next = -1;
    if (event.key === 'ArrowRight') next = (i + 1) % enabled.length;
    else if (event.key === 'ArrowLeft') next = (i - 1 + enabled.length) % enabled.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = enabled.length - 1;
    if (next < 0) return;
    event.preventDefault();
    const id = enabled[next].id;
    this.active.set(id);
    this.host.nativeElement.querySelector<HTMLElement>(`#tab-${id}`)?.focus();
  }
}
