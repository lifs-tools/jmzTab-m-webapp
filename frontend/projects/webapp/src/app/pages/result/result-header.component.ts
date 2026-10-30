import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';

import { ValidationRecord } from '../../api/models/validation-record';

const LEVELS = { INFO: 'Info', WARN: 'Warning', ERROR: 'Error' } as const;

/** Eyebrow, file name, parameter badges and the Download / Delete actions. */
@Component({
  selector: 'app-result-header',
  imports: [DatePipe, MatButtonModule, MatMenuModule, MatTooltipModule],
  templateUrl: './result-header.component.html',
  styleUrl: './result-header.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResultHeaderComponent {
  readonly record = input.required<ValidationRecord>();
  readonly deleting = input(false);
  readonly deleteRequested = output<void>();

  /** The engine that validated the file, e.g. `mzTab-M 2.1 · jmzTab-m 2.1.0 (build …)`; '' if unknown. */
  protected readonly engine = computed(() => this.record().engine ?? '');
  protected readonly profileBadge = computed(() => {
    const p = this.record().validationProfile;
    if (p?.kind === 'CATALOG') return `${p.id} · v${p.version}`;
    if (p?.kind === 'CUSTOM') return 'custom mapping';
    return 'structural only';
  });
  protected readonly levelBadge = computed(() => {
    const r = this.record();
    return `level ${r.level ? LEVELS[r.level] : '—'} · max ${r.maxErrors ?? '—'}`;
  });
  protected readonly done = computed(() => {
    const s = this.record().status;
    return s === 'FINISHED' || s === 'FAILED';
  });
  /** Relative to `<base href>`, so it works under any servlet context path. */
  protected readonly reportBase = computed(() => `rest/v2/validations/${this.record().id}/report`);
}
