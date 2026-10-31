import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, input, output, signal, viewChild } from '@angular/core';

import { DocEdit, DocumentSource, saveErrorOf } from './document-source';

/** How a tab is shown in the editor: as an arrow, so that it can be seen and typed. */
const TAB_MARK = '→';

/**
 * Edits one line of the document as raw text, for lines the metadata and table views cannot show: a line
 * without a valid prefix, a blank line inside a section, an MTD line with the wrong number of parts. Tabs are
 * shown as → and saved as tabs again (a line that already contains → keeps its tabs as they are, so that saving
 * never changes text the user did not touch). Saves as SetRawLine; "Delete this line" as DeleteLine.
 */
@Component({
  selector: 'mztabm-raw-line-editor',
  templateUrl: './raw-line-editor.component.html',
  styleUrl: './raw-line-editor.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RawLineEditorComponent {
  private readonly source = inject(DocumentSource);
  /** 1-based line of the document. */
  readonly lineNumber = input.required<number>();
  /** The line's text without its line ending. */
  readonly text = input.required<string>();
  /** The line was changed or deleted. */
  readonly saved = output<void>();
  /** Closed without a change. */
  readonly cancelled = output<void>();

  private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');
  /** Whether tabs are shown as →; not for a line that already contains the arrow itself. */
  protected readonly arrows = computed(() => !this.text().includes(TAB_MARK));
  protected readonly shown = computed(() => (this.arrows() ? this.text().replaceAll('\t', TAB_MARK) : this.text()));
  protected readonly draft = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.draft.set(this.shown());
      this.error.set(null);
    });
  }

  private toText(shown: string): string {
    return this.arrows() ? shown.replaceAll(TAB_MARK, '\t') : shown;
  }

  protected onInput(event: Event): void {
    this.draft.set((event.target as HTMLInputElement).value);
  }

  protected onKey(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      this.save();
    } else if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.cancel();
    }
  }

  /** Puts a tab where the cursor is (the Tab key itself moves the focus, as everywhere). */
  protected insertTab(): void {
    const el = this.field().nativeElement;
    const value = el.value;
    const start = el.selectionStart ?? value.length;
    const end = el.selectionEnd ?? start;
    const mark = this.arrows() ? TAB_MARK : '\t';
    const next = value.slice(0, start) + mark + value.slice(end);
    el.value = next;
    this.draft.set(next);
    el.focus();
    el.setSelectionRange(start + mark.length, start + mark.length);
  }

  protected save(): void {
    if (this.busy()) return;
    const value = this.field().nativeElement.value;
    const text = this.toText(value);
    if (/[\r\n]/.test(text)) {
      this.error.set('A line cannot contain a line break.');
      return;
    }
    if (text === this.text()) {
      this.cancelled.emit();
      return;
    }
    this.apply([{ op: 'SetRawLine', line: this.lineNumber(), text }]);
  }

  protected deleteLine(): void {
    if (!this.busy()) this.apply([{ op: 'DeleteLine', line: this.lineNumber() }]);
  }

  protected cancel(): void {
    this.field().nativeElement.value = this.shown();
    this.draft.set(this.shown());
    this.error.set(null);
    this.cancelled.emit();
  }

  private apply(ops: DocEdit[]): void {
    const apply = this.source.applyEdits;
    if (!apply) return;
    this.busy.set(true);
    this.error.set(null);
    apply.call(this.source, ops).subscribe({
      complete: () => {
        this.busy.set(false);
        this.saved.emit();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        // a conflict: the host reloads the document and says so
        if ((err as { status?: unknown } | null)?.status !== 409) this.error.set(saveErrorOf(err, 'This change could not be saved.'));
      },
    });
  }
}
