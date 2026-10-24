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
  linkedSignal,
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
import { DocTab, DocTarget, MessageGroup, occurrenceLabel, targetOf } from './message-groups';
import { MessagesTabComponent, ShowRequest } from './messages-tab.component';
import { MetadataHighlight, MetadataTabComponent } from './metadata-tab.component';
import { TableHighlight, TableTabComponent } from './table-tab.component';
import { ResultBannerComponent } from './result-banner.component';
import { ResultHeaderComponent } from './result-header.component';

type Poll = { kind: 'record'; record: ValidationRecord } | { kind: 'error'; err: unknown };
type View = 'loading' | 'ready' | 'notFound' | 'error';
type TabId = 'messages' | DocTab;

/** The occurrence a message group shows in the document: `index` into `targets` (and their `labels`). */
interface Focus {
  group: MessageGroup;
  targets: DocTarget[];
  labels: string[];
  index: number;
  all: boolean;
  /** A chip or action takes the focus into the document; a ‹ › step keeps it on the stepper. */
  moveFocus: boolean;
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
  /**
   * Whether the current jump is still to be played. A new focus (chip click, ‹ ›) makes it live; a manual
   * tab change or a layout switch consumes it, since both re-create the document tab, which would replay
   * the jump (reset the table, re-open the panel, take the focus).
   */
  private readonly jumpLive = linkedSignal<{ focus: Focus | null; split: boolean }, boolean>({
    source: () => ({ focus: this.focus(), split: this.split() }),
    computation: (src, prev) => {
      if (!prev || src.focus !== prev.source.focus) return src.focus !== null;
      return prev.value && src.split === prev.source.split;
    },
  });
  private readonly enabledTabs = computed(
    () => new Set(this.tabs().filter((t) => !t.disabled).map((t) => t.id)),
  );
  /**
   * A message whose target tab is disabled or absent (e.g. any target of an unparsed file) has nothing
   * to link to, so it loses its location and renders no chip.
   */
  protected readonly messages = computed<readonly ValidationMessage[]>(() => {
    const enabled = this.enabledTabs();
    return (this.record()?.messages ?? []).map((m) => {
      const t = targetOf(m);
      if (!t || enabled.has(t.tab)) return m;
      const { location: _, ...rest } = m;
      return rest;
    });
  });
  protected readonly profileLabel = computed(() => {
    const p = this.record()?.validationProfile;
    if (p?.kind === 'CATALOG') return `${p.id} v${p.version}`;
    if (p?.kind === 'CUSTOM') return 'custom mapping';
    return null;
  });

  /** Derived from `focus` only, so a new highlight object (a new jump) exists exactly when the focus changes. */
  private readonly metadataJump = computed<MetadataHighlight | null>(() => {
    const f = this.focus();
    const t = f?.targets[f.index];
    if (!f || !t || t.tab !== 'metadata' || !t.group) return null;
    const keys = f.all
      ? f.targets.filter((o) => o.tab === 'metadata' && o.group === t.group && o.key).map((o) => o.key!)
      : t.key
        ? [t.key]
        : [];
    return { group: t.group, keys, focusKey: t.key ?? null, moveFocus: f.moveFocus };
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
      highlight: {
        rows,
        focusRow: t.rowIndex ?? null,
        column: t.column ?? null,
        header: !!t.header,
        moveFocus: f.moveFocus,
      },
    };
  });
  /** The live jump's metadata highlight; null once the jump is consumed. */
  protected readonly metadataHighlight = computed(() => (this.jumpLive() ? this.metadataJump() : null));
  /** The live jump's highlight for the table of `docTab`; null once the jump is consumed. */
  protected readonly tableHighlight = computed<TableHighlight | null>(() => {
    const j = this.tableJump();
    return j && this.jumpLive() && j.tab === this.docTab() ? j.highlight : null;
  });
  /** The tab on screen is the one holding the shown occurrence: the back bar / stepper apply to it. */
  private readonly onTarget = computed(() => {
    const f = this.focus();
    return !!f && this.current() === f.targets[f.index].tab;
  });
  protected readonly showBackBar = computed(() => this.onTarget() && !this.split());
  protected readonly showStepper = computed(() => this.onTarget() && this.split());

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

  /**
   * Selects a tab; a document tab is also what the right pane shows when the layout widens.
   * Changing the tab on screen consumes the jump: the document tab shown later must not replay it.
   */
  protected select(id: TabId): void {
    if (id !== this.current()) this.jumpLive.set(false);
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
    // A safety net: `messages()` already drops the locations of messages without an enabled tab.
    const enabled = this.enabledTabs();
    const linked = (m: ValidationMessage) => {
      const t = targetOf(m);
      return t && enabled.has(t.tab) ? t : null;
    };
    const shown = req.group.messages.filter((m) => linked(m) !== null);
    if (!shown.length) return;
    // `req.index` counts all messages of the group; the focus counts only those with a target.
    const before = req.group.messages.slice(0, req.index).filter((m) => linked(m) !== null).length;
    this.showTarget({
      group: req.group,
      targets: shown.map((m) => linked(m)!),
      labels: shown.map(occurrenceLabel),
      index: Math.min(before, shown.length - 1),
      all: req.all,
      moveFocus: true,
    });
  }

  /**
   * ‹ › : the previous or next occurrence of the shown group. The focus stays on the stepper; when the
   * pressed button becomes disabled (first or last occurrence), it moves to the other one.
   */
  protected step(delta: 1 | -1, pressed: HTMLButtonElement, other: HTMLButtonElement): void {
    const f = this.focus();
    if (!f) return;
    const index = f.index + delta;
    if (index < 0 || index >= f.targets.length) return;
    const hadFocus = pressed.ownerDocument.activeElement === pressed;
    this.showTarget({ ...f, index, moveFocus: false });
    if (hadFocus && (index === 0 || index === f.targets.length - 1)) {
      afterNextRender(() => other.focus(), { injector: this.injector });
    }
  }

  private showTarget(f: Focus): void {
    const tab = f.targets[f.index].tab;
    this.focus.set(f);
    this.docTab.set(tab);
    if (!this.split()) this.active.set(tab);
  }

  /** Layout A: back to the Messages tab, with focus on the shown group's card. */
  protected backToMessage(): void {
    this.select('messages');
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('.grp[aria-current="true"] button')?.focus(),
      { injector: this.injector },
    );
  }
}
