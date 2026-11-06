import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

import {
  DocDuplicateColumn,
  DocEdit,
  DocTableHeader,
  DocumentSource,
  saveErrorOf,
} from './document-source';

/** Which duplicate the dialog resolves. */
export interface DuplicateColumnData {
  header: DocTableHeader;
  name: string;
}

type Mode = 'keep' | 'merge';

const APPLY_FAILED = 'The columns could not be changed. Please try again.';
const nf = new Intl.NumberFormat('en');

/**
 * Resolves a column name that a header holds more than once: keep one of the columns and delete the others, or
 * merge them into one, choosing which column wins where the rows disagree. Closes with `true` once the edit is
 * applied, `false` after a conflict (the host reloads), and `null` when cancelled.
 */
@Component({
  selector: 'mztabm-duplicate-column-dialog',
  imports: [MatDialogModule, MatButtonModule],
  templateUrl: './duplicate-column-dialog.component.html',
  styleUrl: './duplicate-column-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DuplicateColumnDialogComponent {
  protected readonly data = inject<DuplicateColumnData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<DuplicateColumnDialogComponent, boolean | null>>(MatDialogRef);
  private readonly source = inject(DocumentSource);

  protected readonly duplicate = signal<DocDuplicateColumn | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly mode = signal<Mode>('keep');
  /** The column to keep, or the one that wins where the rows disagree: the first by default. */
  protected readonly pick = signal<number | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly positions = computed(() => this.duplicate()?.positions ?? []);
  protected readonly conflicts = computed(() => this.duplicate()?.conflictingRows ?? 0);
  protected readonly samples = computed(() => this.duplicate()?.samples ?? []);
  protected readonly conflictText = computed(() => {
    const n = this.conflicts();
    if (!n) return 'The columns never hold different values, so merging them loses no value.';
    return `In ${nf.format(n)} ${n === 1 ? 'row' : 'rows'} the columns hold different values; there the chosen column's value is kept.`;
  });
  protected readonly applyLabel = computed(() =>
    this.mode() === 'keep' ? 'Delete the other columns' : 'Merge the columns',
  );

  constructor() {
    const name = this.data.name;
    if (!this.source.duplicateColumns) {
      this.loading.set(false);
      this.loadError.set('This document cannot be changed here.');
      return;
    }
    this.source
      .duplicateColumns(this.data.header)
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe({
        next: (all) => {
          this.loading.set(false);
          const found = all.find((d) => d.name === name && (d.positions?.length ?? 0) > 1) ?? null;
          this.duplicate.set(found);
          this.pick.set(found?.positions?.[0] ?? null);
          if (!found) this.loadError.set(`The column ${name} is no longer used more than once.`);
        },
        error: () => {
          this.loading.set(false);
          this.loadError.set('The columns could not be read. Please try again.');
        },
      });
  }

  protected ordinal(i: number): string {
    return ['first', 'second', 'third', 'fourth', 'fifth'][i] ?? `${i + 1}th`;
  }

  /** The edits of the chosen resolution; deletes go from the right, so the positions still to delete stay put. */
  protected edits(): DocEdit[] {
    const keep = this.pick();
    const { header, name } = this.data;
    if (keep === null) return [];
    const others = this.positions().filter((p) => p !== keep);
    if (this.mode() === 'merge') return [{ op: 'MergeColumns', header, name, keep, others }];
    return [...others]
      .sort((a, b) => b - a)
      .map((position): DocEdit => ({ op: 'DeleteColumn', header, name, position }));
  }

  protected apply(): void {
    const ops = this.edits();
    if (!ops.length || !this.source.applyEdits || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.source.applyEdits(ops).subscribe({
      next: () => this.ref.close(true),
      error: (err: unknown) => {
        this.busy.set(false);
        // a conflict: the host reloads the document and says so
        if ((err as { status?: unknown } | null)?.status === 409) {
          this.ref.close(false);
          return;
        }
        this.error.set(saveErrorOf(err, APPLY_FAILED));
      },
    });
  }
}
