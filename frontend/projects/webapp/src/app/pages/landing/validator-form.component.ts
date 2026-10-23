import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { Router } from '@angular/router';
import { Observable, catchError, of } from 'rxjs';

import { ValidationProfile } from '../../api/models/validation-profile';
import { ValidationRecord } from '../../api/models/validation-record';
import { ExamplesService } from '../../api/services/examples.service';
import { ValidationProfilesService } from '../../api/services/validation-profiles.service';
import { ValidationsService } from '../../api/services/validations.service';
import { AppInfoStore } from '../../core/app-info.store';
import { DEFAULT_VALIDATION_PROFILE } from '../../core/validation-defaults';
import { validationErrorMessage } from '../../core/validation-error';

type Level = 'INFO' | 'WARN' | 'ERROR';

const NONE = 'none';
const CUSTOM = '__custom__';

/** Human-readable size, e.g. "1.2 MB". */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1048576).toFixed(1)} MB`;
}

/** The "Validate a file" card: file drop zone, options, and submit. */
@Component({
  selector: 'app-validator-form',
  imports: [MatButtonModule, MatFormFieldModule, MatInputModule, MatProgressSpinnerModule, MatSelectModule],
  templateUrl: './validator-form.component.html',
  styleUrl: './validator-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ValidatorFormComponent {
  private readonly validations = inject(ValidationsService);
  private readonly examples = inject(ExamplesService);
  private readonly router = inject(Router);
  private readonly appInfo = inject(AppInfoStore);

  protected readonly NONE = NONE;
  protected readonly CUSTOM = CUSTOM;
  protected readonly levels: readonly { value: Level; label: string }[] = [
    { value: 'INFO', label: 'Info' },
    { value: 'WARN', label: 'Warning' },
    { value: 'ERROR', label: 'Error' },
  ];

  protected readonly profiles = toSignal(
    inject(ValidationProfilesService)
      .listValidationProfiles()
      .pipe(catchError(() => of([] as ValidationProfile[]))),
    { initialValue: [] as ValidationProfile[] },
  );

  readonly file = signal<File | null>(null);
  readonly level = signal<Level>('INFO');
  readonly maxErrors = signal<number | null>(100);
  readonly validationProfile = signal<string | null>(DEFAULT_VALIDATION_PROFILE);
  readonly mappingFile = signal<File | null>(null);

  protected readonly dragging = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly info = this.appInfo.info;
  protected readonly maxUploadMb = computed(() => {
    const bytes = this.info()?.maxUploadBytes;
    return bytes ? Math.round(bytes / 1048576) : null;
  });
  protected readonly maxErrorsValid = computed(() => {
    const n = this.maxErrors();
    return n !== null && Number.isInteger(n) && n >= 1 && n <= 500;
  });
  protected readonly canSubmit = computed(
    () => this.file() !== null && this.maxErrorsValid() && !this.submitting(),
  );
  /** Value shown by the profile select: a sentinel for "none"/"custom", since option values are strings. */
  protected readonly profileSelection = computed(() =>
    this.mappingFile() ? CUSTOM : (this.validationProfile() ?? NONE),
  );
  protected readonly profileHint = computed(
    () => this.profiles().find((p) => p.id === this.validationProfile())?.description ?? '',
  );

  protected onFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.setFile(input.files?.[0] ?? null);
    input.value = '';
  }

  protected onMappingInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.mappingFile.set(input.files?.[0] ?? null);
    input.value = '';
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    this.setFile(event.dataTransfer?.files?.[0] ?? null);
  }

  protected setFile(file: File | null): void {
    this.file.set(file);
    this.error.set(null);
  }

  protected onMaxErrors(event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    this.maxErrors.set(raw.trim() === '' ? null : Number(raw));
  }

  protected onProfile(value: string): void {
    this.validationProfile.set(value === NONE ? null : value);
  }

  protected submit(): void {
    const file = this.file();
    if (!file || !this.canSubmit()) return;
    const mapping = this.mappingFile();
    const body: { file: Blob; mappingFile?: Blob } = { file };
    if (mapping) body.mappingFile = mapping;
    this.run(
      this.validations.createValidation({
        level: this.level(),
        maxErrors: this.maxErrors() ?? undefined,
        validationProfile: mapping ? undefined : this.validationProfile(),
        body,
      }),
    );
  }

  /** Validates a catalog example with the form's current options. */
  validateExample(id: string): void {
    if (this.submitting() || !this.maxErrorsValid()) return;
    this.run(
      this.examples.validateExample({
        id,
        body: {
          level: this.level(),
          maxErrors: this.maxErrors(),
          validationProfile: this.validationProfile(),
        },
      }),
    );
  }

  private run(request: Observable<ValidationRecord>): void {
    this.submitting.set(true);
    this.error.set(null);
    request.subscribe({
      next: (record) => {
        this.router.navigate(['/result', record.id]);
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.error.set(this.messageFor(err));
      },
    });
  }

  private messageFor(err: unknown): string {
    return validationErrorMessage(err, this.maxUploadMb() ?? undefined);
  }

  protected size(file: File): string {
    return formatBytes(file.size);
  }
}
