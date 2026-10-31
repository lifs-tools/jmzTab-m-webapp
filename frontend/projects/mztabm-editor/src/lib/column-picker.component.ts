import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

export const MAX_COLUMNS = 50;

/** Searchable checklist over all columns; the first column is always selected, at most 50 in total. */
@Component({
  selector: 'mztabm-column-picker',
  templateUrl: './column-picker.component.html',
  styleUrl: './column-picker.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ColumnPickerComponent {
  readonly allColumns = input.required<readonly string[]>();
  /** The columns currently shown; they seed the draft selection. */
  readonly selected = input.required<readonly string[]>();
  readonly applied = output<string[]>();
  readonly reset = output<void>();
  readonly closed = output<void>();

  protected readonly max = MAX_COLUMNS;
  protected readonly search = signal('');
  private readonly edits = signal<ReadonlySet<string> | undefined>(undefined);
  protected readonly draft = computed(() => this.edits() ?? new Set(this.selected()));
  protected readonly full = computed(() => this.draft().size >= MAX_COLUMNS);
  protected readonly visible = computed(() => {
    const needle = this.search().trim().toLowerCase();
    const all = this.allColumns();
    return needle ? all.filter((c) => c.toLowerCase().includes(needle)) : all;
  });

  protected isFirst(col: string): boolean {
    return col === this.allColumns()[0];
  }

  protected toggle(col: string, checked: boolean): void {
    if (this.isFirst(col)) return;
    const next = new Set(this.draft());
    if (checked) {
      if (next.size >= MAX_COLUMNS) return;
      next.add(col);
    } else {
      next.delete(col);
    }
    this.edits.set(next);
  }

  protected apply(): void {
    const draft = this.draft();
    const first = this.allColumns()[0];
    this.applied.emit(this.allColumns().filter((c) => c === first || draft.has(c)));
  }
}
