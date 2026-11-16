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
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Subscription } from 'rxjs';

import { IMPORT_KIND_LABELS, ImportPreviewComponent } from './import-preview.component';
import { ImportSource } from './import-source';
import { StillMissingComponent } from './still-missing.component';

export const APPLIED_NOTICE = 'Added to the file. Undo takes the whole import back.';

/**
 * "Add from…": one entry per kind of file the document takes, each opening its import preview, and the "still
 * missing" checklist of the document's repository rules. The kinds are asked for again when the document's
 * version changes; the checklist follows the checks of the document (`checkedRevision`).
 */
@Component({
  selector: 'mztabm-add-from-panel',
  imports: [ImportPreviewComponent, StillMissingComponent],
  templateUrl: './add-from-panel.component.html',
  styleUrl: './add-from-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AddFromPanelComponent {
  private readonly source = inject(ImportSource);

  /** The document's mzTab-M version (or its line), as the panel names it. */
  readonly version = input<string | null>(null);
  /** The document's file name: the study id of an import defaults to its stem. */
  readonly fileName = input<string | null>(null);
  /** The revision of the document's last finished check, or null while none is done. */
  readonly checkedRevision = input<number | null>(null);

  /** The kinds the source offers that the library can import; null while they are asked for. */
  protected readonly kinds = signal<string[] | null>(null);
  protected readonly kindsFailed = signal(false);
  protected readonly offered = computed(() =>
    (this.kinds() ?? []).filter((k) => k in IMPORT_KIND_LABELS).map((k) => ({ kind: k, label: IMPORT_KIND_LABELS[k] })),
  );
  protected readonly open = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  private request: Subscription | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.request?.unsubscribe());
    effect(() => {
      this.version();
      untracked(() => this.loadKinds());
    });
  }

  private loadKinds(): void {
    this.request?.unsubscribe();
    this.kinds.set(null);
    this.kindsFailed.set(false);
    this.request = this.source.importKinds().subscribe({
      next: (kinds) => {
        const list = Array.isArray(kinds) ? kinds : [];
        this.kinds.set(list);
        // a kind the document no longer takes closes its preview
        const open = this.open();
        if (open && !list.includes(open)) this.open.set(null);
      },
      error: () => {
        this.kinds.set([]);
        this.kindsFailed.set(true);
      },
    });
  }

  protected choose(kind: string): void {
    this.notice.set(null);
    this.open.set(kind);
  }

  protected onApplied(): void {
    this.open.set(null);
    this.notice.set(APPLIED_NOTICE);
  }
}
