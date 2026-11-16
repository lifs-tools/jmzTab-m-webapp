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

import { FieldRuleView, ImportSource, StillMissingView, importErrorOf } from './import-source';

export const NOTHING_MISSING = 'Nothing is missing for these repository rules.';
export const NOT_CHECKED_TEXT =
  'The file cannot be read into a model yet, so these rules could not be checked against it. Fix the errors in the messages first.';
const LOAD_FAILED = 'The checklist could not be made. It is made again after the next check.';

/** A rule as the checklist names it: its name, else its path. */
export const ruleLabel = (r: FieldRuleView) => r.name?.trim() || r.path || r.id || '';

/**
 * "Still missing": the MUST and SHOULD rules of the document's repository rules that its text does not meet yet.
 * It is read again each time a check of a newer revision is done (`checkedRevision`); before the first check is
 * done it asks nothing. A text that cannot be read into a model lists every rule under "Not yet checked", never
 * "nothing is missing".
 */
@Component({
  selector: 'mztabm-still-missing',
  templateUrl: './still-missing.component.html',
  styleUrl: './still-missing.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StillMissingComponent {
  private readonly source = inject(ImportSource);
  protected readonly NOTHING = NOTHING_MISSING;
  protected readonly NOT_CHECKED = NOT_CHECKED_TEXT;
  /** The revision of the document's last finished check, or null while none is done. */
  readonly checkedRevision = input<number | null>(null);

  protected readonly result = signal<StillMissingView | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(false);
  private asked: number | null = null;
  private request: Subscription | undefined;

  protected readonly required = computed(() => this.rules('MUST'));
  protected readonly recommended = computed(() => this.rules('SHOULD'));
  protected readonly unchecked = computed(() => {
    const r = this.result();
    return r && !r.checked ? (r.missing ?? []) : [];
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.request?.unsubscribe());
    effect(() => {
      const revision = this.checkedRevision();
      if (revision === null || revision === this.asked) return;
      this.asked = revision;
      untracked(() => this.load());
    });
  }

  private rules(requirement: string): FieldRuleView[] {
    const r = this.result();
    return r?.checked ? (r.missing ?? []).filter((m) => m.requirement === requirement) : [];
  }

  protected label(r: FieldRuleView): string {
    return ruleLabel(r);
  }

  private load(): void {
    this.request?.unsubscribe();
    this.loading.set(true);
    this.request = this.source.stillMissing().subscribe({
      next: (r) => {
        this.loading.set(false);
        this.error.set(null);
        this.result.set(r);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.result.set(null);
        this.error.set(importErrorOf(err, LOAD_FAILED));
      },
    });
  }
}
