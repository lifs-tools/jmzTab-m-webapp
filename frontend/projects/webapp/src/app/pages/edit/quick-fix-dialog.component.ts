import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

import { QuickFixPreview } from '../../api/models/quick-fix-preview';
import { WorkingCopyInfo } from '../../api/models/working-copy-info';
import { WorkingCopyStore } from './working-copy.store';

/** One changed line of the preview, its tabs shown as →; null text: the line is added or removed. */
interface Change {
  lineNumber: number;
  before: string | null;
  after: string | null;
}

const show = (text: string | null | undefined) => (text == null ? null : text.replaceAll('\t', '→'));

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
  /** The preview lists at most 200 lines; the batch may change more. */
  protected readonly more = computed(() => {
    const ops = this.preview.batch?.operations?.length ?? 0;
    return ops > this.changes().length ? 'Only the first changed lines are shown.' : null;
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
        this.error.set('The fix could not be applied. Please try again.');
      },
    });
  }
}
