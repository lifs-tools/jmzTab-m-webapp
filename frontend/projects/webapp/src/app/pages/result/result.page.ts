import { BreakpointObserver } from '@angular/cdk/layout';
import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  linkedSignal,
  Signal,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, ParamMap, Router, RouterLink } from '@angular/router';
import { Observable, catchError, exhaustMap, filter, map, of, scan, switchMap, takeWhile, timer } from 'rxjs';

import { ExampleDto } from '../../api/models/example-dto';
import { TourDto } from '../../api/models/tour-dto';
import { TourTargetDto } from '../../api/models/tour-target-dto';
import { ValidationMessage } from '../../api/models/validation-message';
import { ValidationRecord } from '../../api/models/validation-record';
import { ValidationsService } from '../../api/services/validations.service';
import { quietRequest } from '../../core/http-error.interceptor';
import { validationErrorMessage } from '../../core/validation-error';
import { AppInfoStore } from '../../core/app-info.store';
import { lineOf } from '../../core/mztab-version';
import { TOUR_ID_PATTERN, TourService } from '../../core/tour.service';
import { TutorialStore } from '../../core/tutorial.store';
import { DocTab, DocTarget, MessageGroup, groupOf, occurrenceLabel, targetOf } from './message-groups';
import { MessagesTabComponent, MessagesViewState, ShowRequest } from './messages-tab.component';
import { DocumentSource, MetadataHighlight, MetadataViewComponent, TableHighlight, TableViewComponent } from '@lifs-tools/mztabm-editor';
import { ResultDocumentSource } from './result-document-source';
import { ResultBannerComponent } from './result-banner.component';
import { ResultHeaderComponent } from './result-header.component';
import { TourPanelComponent } from './tour-panel.component';

type Poll = { kind: 'record'; record: ValidationRecord } | { kind: 'error'; err: unknown };
type View = 'loading' | 'ready' | 'notFound' | 'error';
type TabId = 'messages' | DocTab;

/**
 * The occurrence a message group shows in the document: `index` into `targets` (and their `labels`).
 * A tour step shows its target without a group: no stepper, no back bar.
 */
interface Focus {
  group: MessageGroup | null;
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
const DOC_TABS: ReadonlySet<string> = new Set<DocTab>(['metadata', 'summary', 'features', 'evidence']);

/** `?tour=<example id>&step=<n>`; null without a tour or with an id outside the pattern. */
function tourParams(q: ParamMap): { id: string; step: number | null } | null {
  const id = q.get('tour');
  if (!id || !TOUR_ID_PATTERN.test(id)) return null;
  const step = q.get('step') ?? '';
  return { id, step: /^[1-9]\d{0,3}$/.test(step) ? Number(step) : null };
}

/** A step's target as the viewer's highlight target; a metadata key without group gets the server's group. */
function docTargetOf(t: TourTargetDto | undefined): DocTarget | null {
  if (!t?.tab || !DOC_TABS.has(t.tab)) return null;
  const tab = t.tab as DocTab;
  if (tab === 'metadata') {
    const group = t.group ?? (t.key ? groupOf(t.key) : null);
    return group ? { tab, group, key: t.key ?? undefined } : null;
  }
  return { tab, rowIndex: t.rowIndex ?? undefined, column: t.column ?? undefined, header: t.header ?? undefined };
}

/**
 * Result of a validation: polls while it runs, then shows banner, messages and section tabs.
 * On wide screens messages and document sit side by side; otherwise a message click jumps to its tab.
 * With `?tour=<example id>&step=<n>` the tour panel takes the place of the messages (wide) or sits above
 * the tabs (narrow), and each step drives the document as a message does.
 */
@Component({
  selector: 'app-result-page',
  imports: [
    NgTemplateOutlet,
    MatProgressBarModule,
    RouterLink,
    MessagesTabComponent,
    MetadataViewComponent,
    TableViewComponent,
    ResultBannerComponent,
    ResultHeaderComponent,
    TourPanelComponent,
  ],
  templateUrl: './result.page.html',
  styleUrl: './result.page.scss',
  providers: [MessagesViewState, ResultDocumentSource, { provide: DocumentSource, useExisting: ResultDocumentSource }],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResultPage {
  private readonly api = inject(ValidationsService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly appInfo = inject(AppInfoStore);
  /** Kept here so the messages tab's filters, page and view survive its re-creation. */
  private readonly messagesView = inject(MessagesViewState);
  /** What the library's document views read from: this result. Set before the views exist. */
  private readonly docSource = inject(ResultDocumentSource);
  private readonly tutorial = inject(TutorialStore);
  private readonly tours = inject(TourService);
  /** A restart or a version switch still validating when the user leaves must not navigate any more. */
  private readonly destroyRef = inject(DestroyRef);
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
  protected readonly showBackBar = computed(() => this.onTarget() && !this.split() && !!this.focus()?.group);
  protected readonly showStepper = computed(() => this.onTarget() && this.split() && !!this.focus()?.group);

  /** The tour of `?tour=`, once it has loaded; null without one. */
  protected readonly tour = signal<TourDto | null>(null);
  /** 0-based index of the tour step shown. */
  protected readonly tourIndex = signal(0);
  /**
   * The tutorial examples and the ids this instance offers, asked for only once a tour is shown (the list is a
   * request of its own). A counterpart whose engine is not available here is not offered: no version switch.
   */
  private readonly tutorialList = signal<{
    examples: Signal<ReadonlyMap<string, ExampleDto> | undefined>;
    offered: Signal<ReadonlySet<string> | undefined>;
  } | null>(null);
  protected readonly counterpartOffered = computed(() => {
    const id = this.tour()?.counterpart;
    return !!id && !!this.tutorialList()?.offered()?.has(id);
  });
  /** The version line of the tour's counterpart, for the switch's label. */
  protected readonly counterpartVersion = computed(() => {
    const id = this.tour()?.counterpart;
    return id ? lineOf(this.tutorialList()?.examples()?.get(id)?.mzTabVersion) : null;
  });
  /** Why the switch to the other version failed. */
  protected readonly tourError = signal<string | null>(null);
  /** The validation shown, for the tour's restart guard. */
  private validationId = '';
  /** A version switch is validating the other file: a second press starts no second validation. */
  private switching = false;

  private tabHandled = false;
  private confirmHandled = false;
  private dialogPending = false;
  protected readonly deleting = signal(false);

  constructor() {
    this.route.paramMap
      .pipe(
        map((p) => p.get('id') ?? ''),
        switchMap((id) => {
          this.validationId = id;
          this.view.set('loading');
          this.record.set(undefined);
          this.active.set('messages');
          this.docTab.set('metadata');
          this.focus.set(null);
          this.messagesView.reset();
          return this.poll(id);
        }),
        takeUntilDestroyed(),
      )
      .subscribe((p) => this.apply(p));
    // The tour loads beside the poll; a tour that does not load leaves the normal result page.
    this.route.paramMap
      .pipe(
        map(() => tourParams(this.route.snapshot.queryParamMap)),
        switchMap((req) => {
          this.tour.set(null);
          this.tourError.set(null);
          if (!req) return of(null);
          return this.tutorial.tour(req.id).pipe(
            map((tour) => ({ tour, step: req.step })),
            catchError(() => of(null)),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((loaded) => {
        const count = loaded?.tour.steps?.length ?? 0;
        if (!loaded || !count) return;
        this.tutorialList.set({ examples: this.tutorial.examples(), offered: this.tutorial.offered() });
        this.tourIndex.set(loaded.step && loaded.step <= count ? loaded.step - 1 : 0);
        this.tour.set(loaded.tour);
        this.showTourStep();
      });
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
      if (isNotFound(p) && this.restartTour()) return;
      this.view.set(isNotFound(p) ? 'notFound' : 'error');
      return;
    }
    this.docSource.validationId = p.record.id ?? '';
    this.record.set(p.record);
    this.view.set('ready');
    if (!this.tabHandled && p.record.status === 'FINISHED') {
      this.tabHandled = true;
      const wanted = this.route.snapshot.queryParamMap.get('tab');
      if (this.tabs().some((t) => t.id === wanted && !t.disabled)) this.select(wanted as TabId);
    }
    if (p.record.status === 'FINISHED') this.showTourStep();
    if (!this.confirmHandled && p.record.status !== undefined && !inProgress(p.record)) {
      this.confirmHandled = true;
      if (this.route.snapshot.queryParamMap.get('confirmDelete') === 'true') {
        this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { confirmDelete: null },
          replaceUrl: true,
          // in-page: the app-wide scroll restoration would move the page to the top
          scroll: 'manual',
        });
        void this.askDelete();
      }
    }
  }

  /**
   * A tour link whose result has expired (reload, bookmark, shared link): validates the example again and
   * opens the same step. Not when this session's tour created the result: its loss is shown, not looped.
   */
  private restartTour(): boolean {
    const req = tourParams(this.route.snapshot.queryParamMap);
    if (!req || this.tours.createdByTour(this.validationId)) return false;
    this.tours
      .start(req.id, req.step ?? 1, true)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ error: () => this.view.set('notFound') });
    return true;
  }

  /**
   * Shows the tour step in the document, once both the tour and the parsed document are there. A step
   * without a target (or whose tab is absent) shows the metadata without a highlight.
   */
  private showTourStep(): void {
    const tour = this.tour();
    if (!tour || this.record()?.status !== 'FINISHED' || this.tabs().length < 2) return;
    const step = tour.steps?.[this.tourIndex()];
    const target = docTargetOf(step?.target);
    if (target && this.enabledTabs().has(target.tab)) {
      this.showTarget({
        group: null,
        targets: [target],
        labels: [step?.title ?? ''],
        index: 0,
        all: false,
        moveFocus: false,
      });
    } else {
      this.focus.set(null);
      this.docTab.set('metadata');
    }
    // Also in layout B, so that the single-pane layout opens on the step's tab when the screen narrows.
    this.active.set(this.docTab());
  }

  /** Back / Next: shows the step and keeps it in the address, so that a reload or a shared link opens it. */
  protected moveTour(delta: 1 | -1): void {
    const count = this.tour()?.steps?.length ?? 0;
    const index = this.tourIndex() + delta;
    if (index < 0 || index >= count) return;
    this.tourIndex.set(index);
    this.showTourStep();
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { step: index + 1 },
      queryParamsHandling: 'merge',
      replaceUrl: true,
      // in-page: the app-wide scroll restoration would move the page to the top, after the viewer has scrolled
      // to the step's row
      scroll: 'manual',
    });
  }

  protected closeTour(): void {
    void this.router.navigate(['/']);
  }

  /** The same step of the tour on the other version: by its id, else the first step. */
  protected switchTourVersion(): void {
    const id = this.tour()?.counterpart;
    if (!id || this.switching) return;
    this.switching = true;
    this.tourError.set(null);
    const stepId = this.tour()?.steps?.[this.tourIndex()]?.id;
    this.tutorial
      .tour(id)
      .pipe(
        map((t) => (t.steps ?? []).findIndex((s) => s.id === stepId) + 1 || 1),
        catchError(() => of(1)),
        switchMap((step) => this.tours.start(id, step)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        complete: () => (this.switching = false),
        error: (err: unknown) => {
          this.switching = false;
          this.tourError.set(validationErrorMessage(err));
        },
      });
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

  /** Layout A: back to the Messages tab, which brings the shown group's card into view and focuses it. */
  protected backToMessage(): void {
    const key = this.focus()?.group?.key;
    this.select('messages');
    if (key) this.messagesView.reveal.set(key);
  }
}
