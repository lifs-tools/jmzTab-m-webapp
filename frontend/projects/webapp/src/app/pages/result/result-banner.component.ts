import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { ValidationRecord } from '../../api/models/validation-record';

type Tone = 'ok' | 'warn' | 'error';

const TITLES: Record<NonNullable<ValidationRecord['outcome']>, { title: string; tone: Tone }> = {
  PASSED: { title: 'Validation passed', tone: 'ok' },
  PASSED_WITH_NOTES: { title: 'Validation passed with notes', tone: 'warn' },
  FAILED: { title: 'Validation failed', tone: 'error' },
  NOT_PARSED: { title: 'The file could not be read', tone: 'error' },
};

/** Outcome banner: status colour, title, optional failure reason and per-level counts. */
@Component({
  selector: 'app-result-banner',
  templateUrl: './result-banner.component.html',
  styleUrl: './result-banner.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResultBannerComponent {
  readonly record = input.required<ValidationRecord>();

  protected readonly view = computed(() => {
    const r = this.record();
    if (r.outcome) return TITLES[r.outcome];
    return { title: 'Validation could not be completed', tone: 'error' as Tone };
  });
  protected readonly chips = computed(() => {
    const c = this.record().counts ?? {};
    const defs = [
      { key: 'ERROR', cls: 'error', one: 'ERROR', many: 'ERRORS' },
      { key: 'WARN', cls: 'warn', one: 'WARNING', many: 'WARNINGS' },
      { key: 'INFO', cls: 'info', one: 'INFO', many: 'INFO' },
    ];
    return defs
      .filter((d) => (c[d.key] ?? 0) > 0)
      .map((d) => ({ cls: d.cls, text: `${c[d.key]} ${c[d.key] === 1 ? d.one : d.many}` }));
  });
}
