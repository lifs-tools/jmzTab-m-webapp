import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router } from '@angular/router';
import { catchError, of } from 'rxjs';

import { ExampleDto } from '../../api/models/example-dto';
import { ValidationProfile } from '../../api/models/validation-profile';
import { ExamplesService } from '../../api/services/examples.service';
import { ValidationProfilesService } from '../../api/services/validation-profiles.service';
import { AppInfoStore } from '../../core/app-info.store';
import { quietRequest } from '../../core/http-error.interceptor';
import { lineOf } from '../../core/mztab-version';
import { defaultProfileFor } from '../../core/validation-defaults';
import { validationErrorMessage } from '../../core/validation-error';

/** Same level and limit as the landing page's example action. */
const DEFAULTS = { level: 'INFO', maxErrors: 100 } as const;
const ALL = 'all';

/** Newest line first: `2.10` before `2.9` before `2.1`. */
export function compareLinesNewestFirst(a: string, b: string): number {
  const [aMajor, aMinor] = a.split('.').map(Number);
  const [bMajor, bMinor] = b.split('.').map(Number);
  return bMajor - aMajor || bMinor - aMinor;
}

/**
 * All example files and mapping files: the validation profile catalog first, then the
 * external mapping files from `rest/v2/examples`; filterable by mzTab-M version line.
 */
@Component({
  selector: 'app-examples-page',
  imports: [MatButtonModule, MatButtonToggleModule, MatProgressSpinnerModule],
  templateUrl: './examples.page.html',
  styleUrl: './examples.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExamplesPage {
  private readonly api = inject(ExamplesService);
  private readonly router = inject(Router);
  private readonly info = inject(AppInfoStore).info;

  protected readonly ALL = ALL;

  private readonly loaded = toSignal(
    this.api.listExamples(undefined, quietRequest()).pipe(catchError(() => of(null))),
    { initialValue: undefined },
  );
  private readonly profiles = toSignal(
    inject(ValidationProfilesService)
      .listValidationProfiles(undefined, quietRequest())
      .pipe(catchError(() => of([] as ValidationProfile[]))),
    { initialValue: [] as ValidationProfile[] },
  );

  /** `all` or a version line. */
  protected readonly filter = signal<string>(ALL);
  /** The lines with an available engine, newest first. */
  protected readonly lines = computed(() =>
    [...new Set((this.info()?.engines ?? []).filter((e) => e.available && e.line).map((e) => e.line!))].sort(
      compareLinesNewestFirst,
    ),
  );

  protected readonly loading = computed(() => this.loaded() === undefined);
  protected readonly failed = computed(() => this.loaded() === null);
  protected readonly examples = computed(() =>
    (this.loaded() ?? []).filter((e) => e.section === 'example' && this.matches(e.mzTabVersion)),
  );
  protected readonly catalogProfiles = computed(() =>
    this.profiles().filter((p) => (p.mzTabVersions ?? []).some((v) => this.matches(v))),
  );
  protected readonly mappings = computed(() =>
    (this.loaded() ?? []).filter((e) => e.section === 'mapping' && this.matches(e.mzTabVersion)),
  );

  protected readonly submitting = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  private matches(version: string | undefined): boolean {
    const filter = this.filter();
    return filter === ALL || lineOf(version) === filter;
  }

  /** Relative to `<base href>`, so it works under any servlet context path. */
  protected mappingUrl(p: ValidationProfile): string {
    return `rest/v2/validation-profiles/${encodeURIComponent(p.id ?? '')}/${encodeURIComponent(p.version ?? '')}/mapping`;
  }

  protected onFilter(value: string | null | undefined): void {
    this.filter.set(value || ALL);
  }

  protected validate(example: ExampleDto): void {
    if (this.submitting() || !example.id) return;
    const line = lineOf(example.mzTabVersion) ?? this.info()?.defaultLine;
    this.submitting.set(example.id);
    this.error.set(null);
    this.api
      .validateExample({
        id: example.id,
        body: { ...DEFAULTS, validationProfile: line ? defaultProfileFor(line) : null },
      })
      .subscribe({
        next: (record) => {
          this.router.navigate(['/result', record.id]);
        },
        error: (err: unknown) => {
          this.submitting.set(null);
          this.error.set(validationErrorMessage(err));
        },
      });
  }
}
