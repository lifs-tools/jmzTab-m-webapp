import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Observable, Subject, catchError, debounceTime, map, of, switchMap } from 'rxjs';

import { TablePage } from '../../api/models/table-page';
import { ValidationsService } from '../../api/services/validations.service';
import { ColumnPickerComponent, MAX_COLUMNS } from './column-picker.component';

type Outcome = { page: TablePage } | { error: string };

const FILTER_DEBOUNCE_MS = 300;
const nf = new Intl.NumberFormat('en');

/** Server-paged, sortable, filterable view of one table section (SML, SMF or SME). */
@Component({
  selector: 'app-table-tab',
  imports: [MatProgressBarModule, ColumnPickerComponent],
  templateUrl: './table-tab.component.html',
  styleUrl: './table-tab.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TableTabComponent {
  private readonly api = inject(ValidationsService);
  readonly validationId = input.required<string>();
  readonly section = input.required<'sml' | 'smf' | 'sme'>();

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

  protected readonly data = signal<TablePage | undefined>(undefined);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly pickerOpen = signal(false);

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

  protected readonly range = computed(() => {
    const total = this.total();
    if (!total) return '0 of 0';
    const start = (this.data()?.page ?? this.pageIndex()) * this.size();
    return `${nf.format(start + 1)}–${nf.format(Math.min(total, start + this.size()))} of ${nf.format(total)}`;
  });
  protected readonly hasPrev = computed(() => this.pageIndex() > 0);
  protected readonly hasNext = computed(() => (this.pageIndex() + 1) * this.size() < this.total());

  private readonly filterEdits = new Subject<void>();

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
    id: this.validationId(),
    section: this.section(),
    page: this.pageIndex(),
    size: this.size(),
    sort: this.sort(),
    descending: this.descending(),
    columns: this.columnsParam(),
    filter: Object.entries(this.filters()).map(([col, text]) => `${col}:${text}`),
  }));

  constructor() {
    toObservable(this.query)
      .pipe(
        switchMap((q): Observable<Outcome> => {
          this.loading.set(true);
          return this.api
            .getTablePage({
              id: q.id,
              section: q.section,
              page: q.page,
              size: q.size,
              ...(q.sort ? { sort: q.sort, descending: q.descending } : {}),
              // one comma-joined value: the server splits it, and the URL stays short
              ...(q.columns ? { columns: [q.columns.join(',')] } : {}),
              ...(q.filter.length ? { filter: q.filter } : {}),
            })
            .pipe(
              map((page): Outcome => ({ page })),
              catchError((err: unknown): Observable<Outcome> => of({ error: this.describe(err) })),
            );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((o) => {
        this.loading.set(false);
        if ('page' in o) {
          this.error.set(null);
          this.data.set(o.page);
        } else {
          this.error.set(o.error);
        }
      });

    this.filterEdits
      .pipe(debounceTime(FILTER_DEBOUNCE_MS), takeUntilDestroyed())
      .subscribe(() => {
        const next: Record<string, string> = {};
        for (const [col, text] of Object.entries(this.draftFilters())) if (text !== '') next[col] = text;
        this.filters.set(next);
        this.pageIndex.set(0);
      });
  }

  private describe(err: unknown): string {
    if (err instanceof HttpErrorResponse && err.status === 400) return 'The table could not be shown for these columns or filters.';
    if (err instanceof HttpErrorResponse && err.status === 404) return 'This table is not available.';
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
    this.filterEdits.next();
  }

  protected setSize(event: Event): void {
    this.size.set(Number((event.target as HTMLSelectElement).value));
    this.pageIndex.set(0);
  }

  protected go(delta: number): void {
    this.pageIndex.update((p) => Math.max(0, p + delta));
  }

  /** Changing the visible columns clears filters, which may refer to columns that are no longer shown. */
  private resetColumnsState(): void {
    this.draftFilters.set({});
    this.filters.set({});
    this.sort.set(null);
    this.descending.set(false);
    this.pageIndex.set(0);
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
}
