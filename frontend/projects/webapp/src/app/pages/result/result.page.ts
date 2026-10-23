import { BreakpointObserver } from '@angular/cdk/layout';
import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, catchError, exhaustMap, filter, map, of, scan, switchMap, takeWhile, timer } from 'rxjs';

import { ValidationMessage } from '../../api/models/validation-message';
import { ValidationRecord } from '../../api/models/validation-record';
import { ValidationsService } from '../../api/services/validations.service';
import { quietRequest } from '../../core/http-error.interceptor';
import { AppInfoStore } from '../../core/app-info.store';
import { DocTab, DocTarget, MessageGroup, targetOf } from './message-groups';
import { MessagesTabComponent, ShowRequest } from './messages-tab.component';
import { MetadataHighlight, MetadataTabComponent } from './metadata-tab.component';
import { TableHighlight, TableTabComponent } from './table-tab.component';
import { ResultBannerComponent } from './result-banner.component';
import { ResultHeaderComponent } from './result-header.component';

type Poll = { kind: 'record'; record: ValidationRecord } | { kind: 'error'; err: unknown };
type View = 'loading' | 'ready' | 'notFound' | 'error';
type TabId = 'messages' | DocTab;

/** The occurrence a message group shows in the document: `index` into `targets`. */
interface Focus {
  group: MessageGroup;
  targets: DocTarget[];
  index: number;
  all: boolean;
}

const POLL_MS = 2000;
const MAX_FAILURES = 3;
const DELETE_ERROR = 'The result could not be deleted. Please try again.';

const inProgress = (r: ValidationRecord) => r.status === 'QUEUED' || r.status === 'RUNNING';
const isNotFound = (p: Poll) => p.kind === 'error' && p.err instanceof HttpErrorResponse && p.err.status === 404;
const count = (n: number | undefined) => (n ?? 0).toLocaleString('en');
const WIDE_QUERY = '(min-width: 1100px)';

/**
 * Result of a validation: polls while it runs, then shows banner, messages and section tabs.
 * On wide screens messages and document sit side by side; otherwise a message click jumps to its tab.
 */
@Component({
  selector: 'app-result-page',
  imports: [
    NgTemplateOutlet,
    MatProgressBarModule,
    RouterLink,
    MessagesTabComponent,
    MetadataTabComponent,
    TableTabComponent,
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

  protected readonly wide = toSignal(
    inject(BreakpointObserver)
      .observe(WIDE_QUERY)
      .pipe(map((s) => s.matches)),
    { initialValue: false },
  );
  /** Layout B: messages left, document right; needs a parsed document. */
  protected readonly split = computed(() => this.wide() && this.tabs().length > 1);
  /** The right pane's tabs in layout B. */
  protected readonly docTabs = computed(() => this.tabs().filter((t) => t.id !== 'messages'));
  protected readonly docTab = signal<DocTab>('metadata');
  protected readonly focus = signal<Focus | null>(null);
  /** Unparsed files have no document to link to, so their messages carry no locations. */
  protected readonly messages = computed<readonly ValidationMessage[]>(() => {
    const r = this.record();
    const ms = r?.messages ?? [];
    return r?.outcome === 'NOT_PARSED' ? ms.map(({ location: _, ...m }) => m) : ms;
  });
  protected readonly profileLabel = computed(() => {
    const p = this.record()?.validationProfile;
    if (p?.kind === 'CATALOG') return `${p.id} v${p.version}`;
    if (p?.kind === 'CUSTOM') return 'custom mapping';
    return null;
  });

  /** Derived from `focus` only, so a new highlight object (a new jump) exists exactly when the focus changes. */
  protected readonly metadataHighlight = computed<MetadataHighlight | null>(() => {
    const f = this.focus();
    const t = f?.targets[f.index];
    if (!f || !t || t.tab !== 'metadata' || !t.group) return null;
    const keys = f.all
      ? f.targets.filter((o) => o.tab === 'metadata' && o.group === t.group && o.key).map((o) => o.key!)
      : t.key
        ? [t.key]
        : [];
    return { group: t.group, keys, focusKey: t.key ?? null };
  });
  private readonly tableJump = computed<{ tab: DocTab; highlight: TableHighlight } | null>(() => {
    const f = this.focus();
    const t = f?.targets[f.index];
    if (!f || !t || t.tab === 'metadata') return null;
    const rows = f.all
      ? f.targets.filter((o) => o.tab === t.tab && o.rowIndex != null).map((o) => o.rowIndex!)
      : t.rowIndex != null
        ? [t.rowIndex]
        : [];
    return {
      tab: t.tab,
      highlight: { rows, focusRow: t.rowIndex ?? null, column: t.column ?? null, header: !!t.header },
    };
  });
  /** For the table of `docTab`; switching tabs back and forth returns the same object, not a new jump. */
  protected readonly tableHighlight = computed<TableHighlight | null>(() => {
    const j = this.tableJump();
    return j && j.tab === this.docTab() ? j.highlight : null;
  });
  protected readonly showBackBar = computed(() => !!this.focus() && !this.split() && this.active() !== 'messages');

  private tabHandled = false;
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
          this.docTab.set('metadata');
          this.focus.set(null);
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
        this.api.getValidation({ id }, quietRequest()).pipe(
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
    if (!this.tabHandled && p.record.status === 'FINISHED') {
      this.tabHandled = true;
      const wanted = this.route.snapshot.queryParamMap.get('tab');
      if (this.tabs().some((t) => t.id === wanted && !t.disabled)) this.select(wanted as TabId);
    }
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

  protected sectionOf(tab: TabId): 'sml' | 'smf' | 'sme' {
    return tab === 'features' ? 'smf' : tab === 'evidence' ? 'sme' : 'sml';
  }

  /** The selected tab of the tablist on screen: the right pane's in layout B, else the single one. */
  protected readonly current = computed<TabId>(() => (this.split() ? this.docTab() : this.active()));

  /** Selects a tab; a document tab is also what the right pane shows when the layout widens. */
  protected select(id: TabId): void {
    this.active.set(id);
    if (id !== 'messages') this.docTab.set(id);
  }

  /** Roving-tabindex keyboard support for the tablist (arrows, Home, End); disabled tabs are skipped. */
  protected onTabKey(event: KeyboardEvent): void {
    const enabled = (this.split() ? this.docTabs() : this.tabs()).filter((t) => !t.disabled);
    const i = enabled.findIndex((t) => t.id === this.current());
    let next = -1;
    if (event.key === 'ArrowRight') next = (i + 1) % enabled.length;
    else if (event.key === 'ArrowLeft') next = (i - 1 + enabled.length) % enabled.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = enabled.length - 1;
    if (next < 0) return;
    event.preventDefault();
    const id = enabled[next].id;
    this.select(id);
    this.host.nativeElement.querySelector<HTMLElement>(`#tab-${id}`)?.focus();
  }

  /** Shows an occurrence of a group: in the right pane (layout B) or by jumping to its tab (layout A). */
  protected onShow(req: ShowRequest): void {
    const enabled = new Set(this.tabs().filter((t) => !t.disabled).map((t) => t.id));
    const linked = (m: ValidationMessage) => {
      const t = targetOf(m);
      return t && enabled.has(t.tab) ? t : null;
    };
    const targets = req.group.messages.map(linked).filter((t): t is DocTarget => t !== null);
    if (!targets.length) return;
    // `req.index` counts all messages of the group; the focus counts only those with a target.
    const before = req.group.messages.slice(0, req.index).filter((m) => linked(m) !== null).length;
    this.showTarget({ group: req.group, targets, index: Math.min(before, targets.length - 1), all: req.all });
  }

  /** ‹ › : the previous or next occurrence of the shown group. */
  protected step(delta: 1 | -1): void {
    const f = this.focus();
    if (!f) return;
    const index = f.index + delta;
    if (index < 0 || index >= f.targets.length) return;
    this.showTarget({ ...f, index });
  }

  private showTarget(f: Focus): void {
    const tab = f.targets[f.index].tab;
    this.focus.set(f);
    this.docTab.set(tab);
    if (!this.split()) this.active.set(tab);
  }

  /** Layout A: back to the Messages tab, with focus on the shown group's card. */
  protected backToMessage(): void {
    this.active.set('messages');
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('.grp[aria-current="true"] button')?.focus(),
      { injector: this.injector },
    );
  }
}
