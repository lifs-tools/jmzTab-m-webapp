import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router } from '@angular/router';
import { catchError, of } from 'rxjs';

import { ExampleDto } from '../../api/models/example-dto';
import { ExamplesService } from '../../api/services/examples.service';
import { quietRequest } from '../../core/http-error.interceptor';
import { DEFAULT_VALIDATION_PROFILE } from '../../core/validation-defaults';
import { validationErrorMessage } from '../../core/validation-error';

/** Same defaults as the landing page's example action. */
const DEFAULTS = { level: 'INFO', maxErrors: 100, validationProfile: DEFAULT_VALIDATION_PROFILE } as const;

/** All example files and example mapping files from `rest/v2/examples`. */
@Component({
  selector: 'app-examples-page',
  imports: [MatButtonModule, MatProgressSpinnerModule],
  templateUrl: './examples.page.html',
  styleUrl: './examples.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExamplesPage {
  private readonly api = inject(ExamplesService);
  private readonly router = inject(Router);

  private readonly loaded = toSignal(
    this.api.listExamples(undefined, quietRequest()).pipe(catchError(() => of(null))),
    { initialValue: undefined },
  );

  protected readonly loading = computed(() => this.loaded() === undefined);
  protected readonly failed = computed(() => this.loaded() === null);
  protected readonly examples = computed(() =>
    (this.loaded() ?? []).filter((e) => e.section === 'example'),
  );
  protected readonly mappings = computed(() =>
    (this.loaded() ?? []).filter((e) => e.section === 'mapping'),
  );

  protected readonly submitting = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected validate(example: ExampleDto): void {
    if (this.submitting() || !example.id) return;
    this.submitting.set(example.id);
    this.error.set(null);
    this.api.validateExample({ id: example.id, body: { ...DEFAULTS } }).subscribe({
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
