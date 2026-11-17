/*
 * Copyright 2026 LIFS Tools Consortium.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { Subscription } from 'rxjs';

import {
  ImportPreviewView,
  ImportSource,
  ImportSuggestionState,
  ImportSuggestionView,
  VOCABULARY_INSTANCE,
  importErrorOf,
} from './import-source';

/** The most an import may be: the host's server refuses a larger request (413). */
export const MAX_IMPORT_BYTES = 5 * 1024 * 1024;
export const STALE_NOTICE = 'The file changed since the preview. Here is the preview for the current file.';
export const OUT_OF_DATE_NOTICE = 'The file changed since this preview. Preview again before applying.';
export const PREPARING = 'Preparing the preview…';
/** Rows of the suggestion table shown at most; the rest are counted. */
export const MAX_ROWS = 200;

/** The name of each kind of import the library knows, as the panel offers it. */
export const IMPORT_KIND_LABELS: Readonly<Record<string, string>> = {
  [VOCABULARY_INSTANCE]: 'A vocabulary file (JSON)',
};

/** How each state of a suggestion is named. */
export const STATE_LABELS: Readonly<Record<ImportSuggestionState, string>> = {
  NEW: 'new',
  REPLACES_EXISTING: 'replaces existing',
  UNCHANGED: 'unchanged',
  CONFLICTS: 'kept (differs)',
};

const TOO_LARGE = 'The file is larger than 5 MiB, the most an import takes.';
const NOT_JSON = 'This file is not JSON.';
const NOT_READ = 'The file could not be read.';
const PREVIEW_FAILED = 'The preview could not be made. Please try again.';
const APPLY_FAILED = 'The import could not be applied. Please try again.';

interface Dataset {
  label: string;
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const text = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v.trim() : null);

/**
 * Previews an import into the document and applies it whole. The researcher chooses the file; for an instance
 * with several datasets, the dataset (one document is one dataset); and the study id, prefilled from the
 * instance's `study.lcs_id`, else from the document's file name. **Keep existing values** (on by default) keeps
 * the values the document already has. Any change of these previews again. A preview of an older revision than
 * the document's (`revision`) is out of date: Apply waits for **Preview again**. A preview that is stale when it is
 * applied (the document changed) is made again for the document as it is now, and the panel says so. What it is
 * doing is said in one live region that is always there.
 */
@Component({
  selector: 'mztabm-import-preview',
  templateUrl: './import-preview.component.html',
  styleUrl: './import-preview.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportPreviewComponent {
  private readonly source = inject(ImportSource);
  private static next = 0;
  protected readonly uid = `mztabm-import-${ImportPreviewComponent.next++}`;

  readonly kind = input.required<string>();
  /** The document's file name: its stem is the study id when the instance has none. */
  readonly fileName = input<string | null>(null);
  /** The document's revision as the host knows it now, or null if it does not say: a preview of another is out of date. */
  readonly revision = input<number | null>(null);
  /** The import was applied. */
  readonly applied = output<void>();
  /** Closed without applying. */
  readonly closed = output<void>();

  protected readonly title = computed(() => IMPORT_KIND_LABELS[this.kind()] ?? this.kind());
  protected readonly instance = signal<unknown>(null);
  protected readonly datasets = computed<Dataset[]>(() => {
    const i = this.instance();
    const list = isObject(i) && Array.isArray(i['datasets']) ? i['datasets'] : [];
    return list.map((d: unknown, n: number) => {
      const title = (isObject(d) && text(d['dataset_title'])) || `Dataset ${n + 1}`;
      const id = isObject(d) ? text(d['dataset_id']) : null;
      return { label: id ? `${title} (${id})` : title };
    });
  });
  protected readonly datasetIndex = signal<number | null>(null);
  protected readonly studyId = signal('');
  protected readonly keepExisting = signal(true);
  protected readonly needStudyId = signal(false);
  protected readonly fileError = signal<string | null>(null);

  protected readonly preview = signal<ImportPreviewView | null>(null);
  protected readonly busy = signal(false);
  protected readonly applying = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly suggestions = computed(() => this.preview()?.suggestions ?? []);
  protected readonly shown = computed(() => this.suggestions().slice(0, MAX_ROWS));
  protected readonly counts = computed(() => {
    const n: Record<ImportSuggestionState, number> = { NEW: 0, REPLACES_EXISTING: 0, UNCHANGED: 0, CONFLICTS: 0 };
    for (const s of this.suggestions()) if (s.state) n[s.state]++;
    return (Object.keys(STATE_LABELS) as ImportSuggestionState[])
      .filter((k) => n[k] > 0)
      .map((k) => `${n[k].toLocaleString('en')} ${STATE_LABELS[k]}`)
      .join(' · ');
  });
  /** Something would change: a suggestion is new or replaces a value. */
  protected readonly changes = computed(() =>
    this.suggestions().some((s) => s.state === 'NEW' || s.state === 'REPLACES_EXISTING'),
  );
  /** The document changed since the preview was made. */
  protected readonly outOfDate = computed(() => {
    const p = this.preview();
    const now = this.revision();
    return !!p && now !== null && p.revision !== undefined && p.revision !== now;
  });
  protected readonly canApply = computed(
    () => !!this.preview() && this.changes() && !this.outOfDate() && !this.busy() && !this.applying(),
  );
  /** The text of the live region: what is being done, or what changed. */
  protected readonly status = computed(() => {
    if (this.busy()) return PREPARING;
    if (this.outOfDate()) return OUT_OF_DATE_NOTICE;
    return this.notice() ?? '';
  });
  private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');

  /** Moves the focus to the heading, e.g. when the host has just opened the preview. */
  focusHeading(): void {
    this.heading().nativeElement.focus();
  }

  private request: Subscription | undefined;
  /** Counts the file choices: a file read after a newer one was chosen is dropped. */
  private reads = 0;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.request?.unsubscribe());
  }

  protected stateLabel(s: ImportSuggestionView): string {
    return s.state ? STATE_LABELS[s.state] : '';
  }

  protected async onFile(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    const read = ++this.reads;
    this.reset();
    if (!file) return;
    if (file.size > MAX_IMPORT_BYTES) {
      this.fileError.set(TOO_LARGE);
      return;
    }
    let content: string;
    try {
      content = await file.text();
    } catch {
      if (read === this.reads) this.fileError.set(NOT_READ);
      return;
    }
    if (read !== this.reads) return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(content);
    } catch {
      this.fileError.set(NOT_JSON);
      return;
    }
    if (!isObject(parsed)) {
      this.fileError.set(NOT_JSON);
      return;
    }
    const study = parsed['study'];
    this.studyId.set((isObject(study) && text(study['lcs_id'])) || this.stem());
    this.instance.set(parsed);
    // one dataset is the one; several are chosen from; none is the server's to refuse
    this.datasetIndex.set(this.datasets().length === 1 ? 1 : null);
    this.previewAgain();
  }

  private stem(): string {
    // the extension, then a trailing "-NN" (a copy number: LCS-99999-01 is the study LCS-99999)
    return (this.fileName() ?? '').replace(/\.[^.]*$/, '').replace(/-\d{2}$/, '').trim();
  }

  private reset(): void {
    this.request?.unsubscribe();
    this.instance.set(null);
    this.datasetIndex.set(null);
    this.studyId.set('');
    this.needStudyId.set(false);
    this.fileError.set(null);
    this.preview.set(null);
    this.busy.set(false);
    this.error.set(null);
    this.notice.set(null);
  }

  protected chooseDataset(index: number): void {
    this.datasetIndex.set(index);
    this.previewAgain();
  }

  protected onStudyId(event: Event): void {
    this.studyId.set((event.target as HTMLInputElement).value.trim());
    this.previewAgain();
  }

  protected onKeep(event: Event): void {
    this.keepExisting.set((event.target as HTMLInputElement).checked);
    this.previewAgain();
  }

  /** Previews with the choices as they are now, once they are complete; `stale`: after a stale apply. */
  private previewAgain(stale = false): void {
    this.request?.unsubscribe();
    this.notice.set(null);
    this.error.set(null);
    this.preview.set(null);
    this.busy.set(false);
    const instance = this.instance();
    if (instance === null) return;
    if (this.datasets().length > 1 && this.datasetIndex() === null) return;
    const studyId = this.studyId().trim();
    this.needStudyId.set(!studyId);
    if (!studyId) return;
    this.busy.set(true);
    this.request = this.source
      .previewImport({
        kind: this.kind(),
        instance,
        datasetIndex: this.datasetIndex(),
        studyId,
        keepExisting: this.keepExisting(),
      })
      .subscribe({
        next: (p) => {
          this.busy.set(false);
          this.preview.set(p);
          if (stale) this.notice.set(STALE_NOTICE);
        },
        error: (err: unknown) => {
          this.busy.set(false);
          this.error.set(importErrorOf(err, PREVIEW_FAILED));
        },
      });
  }

  protected again(): void {
    this.previewAgain();
  }

  protected apply(): void {
    const p = this.preview();
    if (!p || !this.canApply()) return;
    this.applying.set(true);
    this.error.set(null);
    this.source.applyImport(p).subscribe({
      next: (outcome) => {
        this.applying.set(false);
        if (outcome === 'applied') this.applied.emit();
        else this.previewAgain(true);
      },
      error: (err: unknown) => {
        this.applying.set(false);
        this.error.set(importErrorOf(err, APPLY_FAILED));
      },
    });
  }

  protected cancel(): void {
    this.request?.unsubscribe();
    this.closed.emit();
  }
}
