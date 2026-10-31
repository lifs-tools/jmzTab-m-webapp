import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

import { QuickFixPreview } from '../../api/models/quick-fix-preview';
import { WorkingCopyInfo } from '../../api/models/working-copy-info';
import { WorkingCopyStore, serverMessage } from './working-copy.store';

/** One changed line of the preview, its tabs shown as →; null text: the line is added or removed. */
interface Change {
  lineNumber: number;
  before: string | null;
  after: string | null;
}

const show = (text: string | null | undefined) => (text == null ? null : text.replaceAll('\t', '→'));

/** The server lists at most this many changed lines of a preview. */
export const MAX_PREVIEW_CHANGES = 200;
/** Operations that change every row of a table: one of them can change more lines than are listed. */
const ROW_WIDE = new Set(['InsertColumn', 'DeleteColumn', 'MergeColumns']);

/**
 * The before/after preview of a quick fix. "Apply" posts the previewed batch through the store, which validates
 * after the pause like after any edit; the dialog closes with the copy's new info, or with nothing.
 */
@Component({
  selector: 'app-quick-fix-dialog',
  imports: [MatDialogModule, MatButtonModule],
  templateUrl: './quick-fix-dialog.component.html',
  styleUrl: './quick-fix-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QuickFixDialogComponent {
  protected readonly preview = inject<QuickFixPreview>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<QuickFixDialogComponent, WorkingCopyInfo | null>>(MatDialogRef);
  private readonly store = inject(WorkingCopyStore);

  protected readonly applying = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly changes = computed<Change[]>(() =>
    (this.preview.changes ?? []).map((c) => ({ lineNumber: c.lineNumber ?? 0, before: show(c.before), after: show(c.after) })),
  );
  protected readonly count = computed(() => {
    const n = this.preview.occurrences ?? 0;
    return n === 1 ? 'Fixes 1 problem' : `Fixes ${n.toLocaleString('en')} problems`;
  });
  /**
   * The server lists at most {@link MAX_PREVIEW_CHANGES} lines. A full list was cut when the batch does more than
   * it shows: more operations or problems than lines, or an operation on every row.
   */
  protected readonly more = computed(() => {
    const shown = this.changes().length;
    if (shown < MAX_PREVIEW_CHANGES) return null;
    const ops = this.preview.batch?.operations ?? [];
    const cut =
      ops.length > shown || (this.preview.occurrences ?? 0) > shown || ops.some((o) => ROW_WIDE.has(o.op ?? ''));
    return cut ? 'Only the first changed lines are shown.' : null;
  });

  protected apply(): void {
    if (this.applying()) return;
    this.applying.set(true);
    this.error.set(null);
    this.store.applyFix(this.preview).subscribe({
      next: (info) => this.ref.close(info),
      error: (err: unknown) => {
        this.applying.set(false);
        // a conflict: the store reloads the copy and says so; the preview is out of date
        if (err instanceof HttpErrorResponse && err.status === 409) {
          this.ref.close(null);
          return;
        }
        const reason = err instanceof HttpErrorResponse && err.status === 400 ? serverMessage(err) : null;
        this.error.set(reason ?? 'The fix could not be applied. Please try again.');
      },
    });
  }
}
