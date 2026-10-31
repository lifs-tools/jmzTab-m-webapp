import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { EMPTY, Observable, Subject, catchError, filter, map, of, switchMap, timer } from 'rxjs';

import { DocEdit, DocSection, DocTablePage, DocTableRow, DocumentSource, invalidCellText } from './document-source';
import { ColumnPickerComponent, MAX_COLUMNS } from './column-picker.component';

/** The user-controlled state, restored when a request fails so the controls match the rows still shown. */
interface Snapshot {
  page: number;
  size: number;
  sort: string | null;
  descending: boolean;
  draft: Record<string, string>;
  filters: Record<string, string>;
  windowStart: number;
  picked: string[] | null;
}

type Outcome = { page: DocTablePage; snapshot: Snapshot; key: string } | { error: string };

/** Rows (0-based model-list positions), a column and/or its header that a validation message points at. */
export interface TableHighlight {
  rows: readonly number[];
  focusRow: number | null;
  column: string | null;
  header: boolean;
  /** Whether the jump takes the focus; a ‹ › step leaves it on the stepper. */
  moveFocus: boolean;
}

/** A cell being edited: its position on the page, the text typed so far and why it cannot be saved. */
interface Editing {
  row: number;
  col: number;
  error: string | null;
}

const FILTER_DEBOUNCE_MS = 300;
const SAVE_ERROR = 'This change could not be saved.';
const nf = new Intl.NumberFormat('en');

/** Source-paged, sortable, filterable view of one table section (SML, SMF or SME). */
@Component({
  selector: 'mztabm-table-view',
  imports: [MatProgressBarModule, ColumnPickerComponent],
  templateUrl: './table-view.component.html',
  styleUrl: './table-view.component.scss',
  host: { '[attr.data-readonly]': 'isReadonly()' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TableViewComponent {
  private readonly source = inject(DocumentSource);
  readonly section = input.required<DocSection>();
  /** Whether the view only shows the document; an editable host sets it to false. */
  readonly isReadonly = input(true, { alias: 'readonly' });
  /** An event, not a value: each new object jumps to its target again, even when its fields are unchanged. */
  readonly highlight = input<TableHighlight | null>(null);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly pageSizes = [25, 50, 100];
  private readonly pageIndex = signal(0);
  protected readonly size = signal(50);
  protected readonly sort = signal<string | null>(null);
  protected readonly descending = signal(false);
  /** Text typed into the filter inputs (immediate) and the debounced subset that is actually sent. */
  protected readonly draftFilters = signal<Record<string, string>>({});
  private readonly filters = signal<Record<string, string>>({});
  private readonly windowStart = signal(0);
  private readonly picked = signal<string[] | null>(null);

  protected readonly data = signal<DocTablePage | undefined>(undefined);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly pickerOpen = signal(false);

  /** Editable mode: the cell that holds the table's one tab stop (page positions), the cell being edited. */
  protected readonly editable = computed(() => !this.isReadonly() && !!this.source.applyEdits);
  protected readonly active = signal<{ row: number; col: number }>({ row: 0, col: 0 });
  protected readonly editing = signal<Editing | null>(null);
  protected readonly saveError = signal<string | null>(null);

  protected readonly allColumns = computed(() => this.data()?.allColumns ?? []);
  protected readonly columns = computed(() => this.data()?.columns ?? []);
  protected readonly rows = computed(() => this.data()?.rows ?? []);
  protected readonly total = computed(() => this.data()?.totalRows ?? 0);
  protected readonly showWindow = computed(() => this.picked() === null && this.allColumns().length > MAX_COLUMNS);
  protected readonly windowLabel = computed(() => {
    const start = this.windowStart();
    const total = this.allColumns().length;
    return `Columns ${nf.format(start + 1)}–${nf.format(Math.min(start + MAX_COLUMNS, total))} of ${nf.format(total)}`;
  });
  protected readonly hasPrevWindow = computed(() => this.windowStart() > 0);
  protected readonly hasNextWindow = computed(() => this.windowStart() + MAX_COLUMNS < this.allColumns().length);
  protected readonly pickerSeed = computed(() => this.columns().slice(0, MAX_COLUMNS));

  protected readonly highlightedRows = computed(() => new Set(this.highlight()?.rows ?? []));
  protected readonly otherPages = computed(() => {
    const here = new Set(this.rows().map((r) => r.index ?? -1));
    return this.highlight()?.rows.filter((r) => !here.has(r)).length ?? 0;
  });

  protected readonly range = computed(() => {
    const total = this.total();
    if (!total) return '0 of 0';
    const start = (this.data()?.page ?? this.pageIndex()) * this.size();
    return `${nf.format(start + 1)}–${nf.format(Math.min(total, start + this.size()))} of ${nf.format(total)}`;
  });
  protected readonly hasPrev = computed(() => this.pageIndex() > 0);
  protected readonly hasNext = computed(() => (this.pageIndex() + 1) * this.size() < this.total());

  private readonly filterEdits = new Subject<boolean>();
  private readonly retryNonce = signal(0);
  private lastGood: Snapshot | undefined;
  private shownKey: string | undefined;
  private inFlightKey: string | undefined;
  /** A highlight that arrived before the column list was known, and a jump still waiting for its page. */
  private pendingHighlight: TableHighlight | undefined;
  private awaitingFocus = false;

  /** The columns to request: nothing for the first window (the server default), otherwise explicit. */
  private readonly columnsParam = computed(() => {
    const picked = this.picked();
    if (picked) return picked;
    const start = this.windowStart();
    const all = this.allColumns();
    if (start === 0 || !all.length) return undefined;
    return [all[0], ...all.slice(start, start + MAX_COLUMNS)];
  });

  private readonly query = computed(() => ({
    section: this.section(),
    page: this.pageIndex(),
    size: this.size(),
    sort: this.sort(),
    descending: this.descending(),
    columns: this.columnsParam(),
    filters: Object.entries(this.filters()).map(([column, text]) => ({ column, text })),
    retry: this.retryNonce(),
  }));

  constructor() {
    effect(() => {
      const h = this.highlight();
      if (h) untracked(() => this.jump(h));
    });

    toObservable(this.query)
      .pipe(
        // Skip a query equal to the one in flight, or else to the last shown one (including a restored state).
        filter((q) => JSON.stringify(q) !== (this.inFlightKey ?? this.shownKey)),
        switchMap((q): Observable<Outcome> => {
          this.loading.set(true);
          const key = JSON.stringify(q);
          this.inFlightKey = key;
          const snapshot = this.snapshot();
          return this.source
            .tablePage(q.section, {
              page: q.page,
              size: q.size,
              sort: q.sort,
              descending: q.descending,
              columns: q.columns,
              filters: q.filters,
            })
            .pipe(
              map((page): Outcome => ({ page, snapshot, key })),
              catchError((err: unknown): Observable<Outcome> => of({ error: this.describe(err) })),
            );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((o) => {
        this.loading.set(false);
        this.inFlightKey = undefined;
        if ('page' in o) {
          this.error.set(null);
          this.lastGood = o.snapshot;
          this.shownKey = o.key;
          this.data.set(o.page);
          this.editing.set(null);
          this.clampActive();
          const pending = this.pendingHighlight;
          if (pending && o.page.allColumns?.length) {
            this.pendingHighlight = undefined;
            this.jump(pending);
          } else if (this.awaitingFocus) {
            this.awaitingFocus = false;
            this.focusTarget();
          }
        } else {
          this.error.set(o.error);
          this.awaitingFocus = false;
          if (this.lastGood) {
            this.restore(this.lastGood);
          }
        }
      });

    this.filterEdits
      .pipe(
        // true starts (or restarts) the debounce, false cancels a pending one
        switchMap((start) => (start ? timer(FILTER_DEBOUNCE_MS) : EMPTY)),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        const next: Record<string, string> = {};
        for (const [col, text] of Object.entries(this.draftFilters())) if (text !== '') next[col] = text;
        this.filters.set(next);
        this.pageIndex.set(0);
      });

    // the document changed elsewhere (undo, redo, a reload): show the page as it is now
    this.source.changed?.pipe(takeUntilDestroyed()).subscribe(() => {
      this.editing.set(null);
      this.retry();
    });
  }

  private snapshot(): Snapshot {
    return {
      page: this.pageIndex(),
      size: this.size(),
      sort: this.sort(),
      descending: this.descending(),
      draft: this.draftFilters(),
      filters: this.filters(),
      windowStart: this.windowStart(),
      picked: this.picked(),
    };
  }

  private restore(s: Snapshot): void {
    this.pageIndex.set(s.page);
    this.size.set(s.size);
    this.sort.set(s.sort);
    this.descending.set(s.descending);
    this.draftFilters.set(s.draft);
    this.filters.set(s.filters);
    this.windowStart.set(s.windowStart);
    this.picked.set(s.picked);
  }

  protected retry(): void {
    this.retryNonce.update((n) => n + 1);
  }

  private describe(err: unknown): string {
    const status = typeof err === 'object' && err !== null ? (err as { status?: unknown }).status : undefined;
    if (status === 400) return 'The table could not be shown for these columns or filters.';
    if (status === 404) return 'This table is not available.';
    return 'The table could not be loaded. Please try again.';
  }

  protected filterValue(col: string): string {
    return this.draftFilters()[col] ?? '';
  }

  protected cell(row: { cells?: string[] }, i: number): string {
    return row.cells?.[i] ?? '';
  }

  protected ariaSort(col: string): 'ascending' | 'descending' | 'none' {
    if (this.sort() !== col) return 'none';
    return this.descending() ? 'descending' : 'ascending';
  }

  protected toggleSort(col: string): void {
    this.awaitingFocus = false;
    if (this.sort() !== col) {
      this.sort.set(col);
      this.descending.set(false);
    } else if (!this.descending()) {
      this.descending.set(true);
    } else {
      this.sort.set(null);
      this.descending.set(false);
    }
    this.pageIndex.set(0);
  }

  protected onFilter(col: string, event: Event): void {
    this.draftFilters.update((f) => ({ ...f, [col]: (event.target as HTMLInputElement).value }));
    this.awaitingFocus = false;
    this.filterEdits.next(true);
  }

  protected setSize(event: Event): void {
    this.awaitingFocus = false;
    this.size.set(Number((event.target as HTMLSelectElement).value));
    this.pageIndex.set(0);
  }

  protected go(delta: number): void {
    this.awaitingFocus = false;
    this.pageIndex.update((p) => Math.max(0, p + delta));
  }

  /** Changing the visible columns clears filters, which may refer to columns that are no longer shown. */
  private resetColumnsState(): void {
    this.filterEdits.next(false); // a typed filter still in its debounce must not fire after this
    this.awaitingFocus = false; // a jump sets it again after the reset
    this.draftFilters.set({});
    this.filters.set({});
    this.sort.set(null);
    this.descending.set(false);
    this.pageIndex.set(0);
  }

  /** Clear what could hide the target, show its column, open its page, and show (and maybe focus) it once rendered. */
  private jump(h: TableHighlight): void {
    const all = this.allColumns();
    if (h.column && !all.length) {
      this.pendingHighlight = h;
      return;
    }
    this.pendingHighlight = undefined;
    this.resetColumnsState();
    const c = h.column ? all.indexOf(h.column) : -1;
    if (c >= 0) {
      const picked = this.picked();
      if (picked && !picked.includes(h.column!)) this.picked.set(null);
      this.windowStart.set(c < MAX_COLUMNS ? 0 : Math.floor(c / MAX_COLUMNS) * MAX_COLUMNS);
    }
    if (h.focusRow !== null) this.pageIndex.set(Math.floor(h.focusRow / this.size()));
    if (JSON.stringify(this.query()) === (this.inFlightKey ?? this.shownKey)) {
      this.awaitingFocus = false;
      this.focusTarget();
    } else {
      this.awaitingFocus = true;
    }
  }

  private focusTarget(): void {
    afterNextRender(
      () => {
        const el = this.host.nativeElement.querySelector<HTMLElement>('[data-hl-focus]');
        const pos = el?.dataset['pos']?.split(',').map(Number);
        if (pos && this.editable()) this.active.set({ row: pos[0], col: pos[1] });
        if (this.highlight()?.moveFocus) el?.focus({ preventScroll: true });
        el?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' }); // jsdom has no scrollIntoView
      },
      { injector: this.injector },
    );
  }

  /** The cell that takes focus: the highlighted column's cell in the focus row, else that row's first cell. */
  protected isFocusCell(rowIndex: number, col: string | undefined, i: number): boolean {
    const h = this.highlight();
    if (!h || h.focusRow !== rowIndex) return false;
    return h.column && this.columns().includes(h.column) ? col === h.column : i === 0;
  }

  protected shiftWindow(delta: number): void {
    this.windowStart.update((s) => Math.max(0, s + delta * MAX_COLUMNS));
    this.resetColumnsState();
  }

  protected applyPick(cols: string[]): void {
    this.picked.set(cols);
    this.pickerOpen.set(false);
    this.resetColumnsState();
  }

  protected resetPick(): void {
    this.picked.set(null);
    this.windowStart.set(0);
    this.pickerOpen.set(false);
    this.resetColumnsState();
  }

  // ---- editing (only when not read-only and the source can apply edits) ----

  /** The cell's tabindex: in editable mode a roving tab stop; otherwise only the jump target is focusable. */
  protected tabIndexOf(r: number, i: number, focusCell: boolean): number | null {
    if (this.editable()) {
      const a = this.active();
      return a.row === r && a.col === i ? 0 : -1;
    }
    return focusCell ? -1 : null;
  }

  protected isEditing(r: number, i: number): boolean {
    const e = this.editing();
    return !!e && e.row === r && e.col === i;
  }

  private clampActive(): void {
    const rows = this.rows().length;
    const cols = this.columns().length;
    const a = this.active();
    this.active.set({ row: Math.max(0, Math.min(a.row, rows - 1)), col: Math.max(0, Math.min(a.col, cols - 1)) });
  }

  protected onCellClick(r: number, i: number): void {
    if (!this.editable() || this.isEditing(r, i)) return;
    this.startEdit(r, i);
  }

  /** Arrow keys, Home and End move the tab stop; Enter or F2 edits the cell. */
  protected onCellKey(event: KeyboardEvent, r: number, i: number): void {
    if (!this.editable() || (event.target as HTMLElement).tagName === 'INPUT') return;
    const rows = this.rows().length;
    const cols = this.columns().length;
    let next: { row: number; col: number } | null = null;
    switch (event.key) {
      case 'ArrowRight':
        next = { row: r, col: Math.min(cols - 1, i + 1) };
        break;
      case 'ArrowLeft':
        next = { row: r, col: Math.max(0, i - 1) };
        break;
      case 'ArrowDown':
        next = { row: Math.min(rows - 1, r + 1), col: i };
        break;
      case 'ArrowUp':
        next = { row: Math.max(0, r - 1), col: i };
        break;
      case 'Home':
        next = { row: r, col: 0 };
        break;
      case 'End':
        next = { row: r, col: cols - 1 };
        break;
      case 'Enter':
      case 'F2':
        event.preventDefault();
        this.startEdit(r, i);
        return;
      default:
        return;
    }
    event.preventDefault();
    this.active.set(next);
    this.focusActive();
  }

  private startEdit(r: number, i: number): void {
    if (this.rows()[r]?.lineNumber == null) return;
    this.saveError.set(null);
    this.active.set({ row: r, col: i });
    this.editing.set({ row: r, col: i, error: null });
    afterNextRender(
      () => {
        const input = this.host.nativeElement.querySelector<HTMLInputElement>('input.cell-input');
        input?.focus();
        input?.select();
      },
      { injector: this.injector },
    );
  }

  protected onInputKey(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      this.save((event.target as HTMLInputElement).value);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.editing.set(null);
      this.focusActive();
    }
  }

  /** Saves the typed text as one SetCell; the cell shows it at once and keeps it once the source accepts it. */
  private save(text: string): void {
    const e = this.editing();
    const page = this.data();
    const row = page?.rows?.[e?.row ?? -1];
    if (!e || !page || !row || row.lineNumber == null || !this.source.applyEdits) return;
    const error = invalidCellText(text);
    if (error) {
      this.editing.set({ ...e, error });
      return;
    }
    this.editing.set(null);
    this.focusActive();
    if (text === this.cell(row, e.col)) return;
    // cell 0 of a line is its prefix, so header column n is cell n + 1, counted over all columns
    const column = this.columns()[e.col];
    const cell = this.allColumns().indexOf(column) + 1;
    if (cell < 1) return;
    const op: DocEdit = { op: 'SetCell', line: row.lineNumber, cell, text };
    this.patch(row, e.col, text);
    this.source.applyEdits([op]).subscribe({
      error: (err: unknown) => {
        // a conflict: the host reloads the document and says so
        if ((err as { status?: unknown } | null)?.status === 409) return;
        this.saveError.set(SAVE_ERROR);
        this.retry();
      },
    });
  }

  private patch(row: DocTableRow, col: number, text: string): void {
    const page = this.data();
    if (!page) return;
    const rows = (page.rows ?? []).map((r) =>
      r === row ? { ...r, cells: (r.cells ?? []).map((c, j) => (j === col ? text : c)) } : r,
    );
    this.data.set({ ...page, rows });
  }

  private focusActive(): void {
    afterNextRender(
      () => {
        const { row, col } = this.active();
        this.host.nativeElement
          .querySelectorAll('tbody tr')
          [row]?.querySelectorAll<HTMLElement>('td')
          [col]?.focus();
      },
      { injector: this.injector },
    );
  }
}
