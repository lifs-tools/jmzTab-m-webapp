import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  PendingTasks,
  computed,
  inject,
  linkedSignal,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { Router } from '@angular/router';
import { Observable, catchError, exhaustMap, filter, of, switchMap, take, timer } from 'rxjs';

import { ValidationProfile } from '../../api/models/validation-profile';
import { ValidationRecord } from '../../api/models/validation-record';
import { ExamplesService } from '../../api/services/examples.service';
import { ValidationProfilesService } from '../../api/services/validation-profiles.service';
import { ValidationsService } from '../../api/services/validations.service';
import { AppInfoStore } from '../../core/app-info.store';
import { canFix } from '../../core/fixable';
import { quietRequest } from '../../core/http-error.interceptor';
import { detectMzTabVersion, lineOf } from '../../core/mztab-version';
import { defaultProfileFor } from '../../core/validation-defaults';
import {
  MAPPING_TOO_LARGE,
  MAX_MAPPING_BYTES,
  fileTooLargeMessage,
  validationErrorMessage,
} from '../../core/validation-error';

type Level = 'INFO' | 'WARN' | 'ERROR';

const NONE = 'none';
/** "Open a file to fix" reads the validation at this interval until its result is final. */
export const FIX_POLL_MS = 1000;
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
  private readonly destroyRef = inject(DestroyRef);
  private readonly appInfo = inject(AppInfoStore);
  private readonly pendingTasks = inject(PendingTasks);

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
  /** The version the chosen file declares, as read in the browser; null if none. */
  readonly fileVersion = signal<string | null>(null);
  readonly level = signal<Level>('INFO');
  readonly maxErrors = signal<number | null>(100);
  readonly mappingFile = signal<File | null>(null);

  protected readonly info = this.appInfo.info;
  /** The version line the file is validated as: its own, else the instance's default line. */
  readonly activeLine = computed(() => lineOf(this.fileVersion()) ?? this.info()?.defaultLine ?? null);
  /** Profiles of the active line; the server would reject the others for this file. */
  protected readonly lineProfiles = computed(() => {
    const line = this.activeLine();
    return this.profiles().filter((p) => p.mzTabVersions?.some((v) => lineOf(v) === line));
  });
  /** Changes when the line or its profiles change, which resets the choice below. */
  private readonly profileChoices = computed(
    () => `${this.activeLine()}|${this.lineProfiles().map((p) => p.id).join(',')}`,
  );
  /** The chosen profile id, or null for none; reset to the line's default when the line changes. */
  readonly validationProfile = linkedSignal<string, string | null>({
    source: this.profileChoices,
    computation: () => {
      const line = this.activeLine();
      const preferred = line ? defaultProfileFor(line) : null;
      return this.lineProfiles().some((p) => p.id === preferred) ? preferred : null;
    },
  });

  protected readonly dragging = signal(false);
  protected readonly submitting = signal(false);
  /** Which button submitted: "Open a file to fix" waits for the result, then opens the editor. */
  protected readonly opening = signal(false);
  protected readonly error = signal<string | null>(null);
  /** True while the chosen file's version is being read. */
  protected readonly detecting = signal(false);
  private detection = 0;

  /** Badge next to the file name, once its version has been read. */
  protected readonly versionBadge = computed(() => {
    if (!this.file() || this.detecting()) return null;
    const declared = this.fileVersion();
    const line = lineOf(declared);
    if (line) return `mzTab-M ${line}`;
    if (declared) return `mzTab-M ${declared}`;
    const fallback = this.info()?.defaultLine;
    return fallback ? `No version declared · validated as mzTab-M ${fallback}` : 'No version declared';
  });
  /**
   * The active line when no engine for it is available, for the warning callout; the form still
   * submits, as the server decides. `unsupported`: no engine is configured for the line;
   * `unavailable`: its engine is configured but did not answer the last time it was asked.
   */
  protected readonly engineIssue = computed<{ line: string; kind: 'unsupported' | 'unavailable' } | null>(() => {
    const info = this.info();
    const line = this.activeLine();
    if (!info || !line || this.detecting()) return null;
    const engine = info.engines?.find((e) => e.line === line);
    if (!engine) return { line, kind: 'unsupported' };
    return engine.available ? null : { line, kind: 'unavailable' };
  });
  /**
   * Where a line without engine can be tried instead; never for legacy mzTab 1.x, which no
   * validator takes, and never for an engine that is only down.
   */
  protected readonly unavailableHint = computed(() => {
    const issue = this.engineIssue();
    const url = this.info()?.unavailableHintUrl?.trim();
    return issue?.kind === 'unsupported' && url && !issue.line.startsWith('1.') ? url : null;
  });
  protected readonly maxUploadMb = computed(() => {
    const bytes = this.info()?.maxUploadBytes;
    return bytes ? Math.round(bytes / 1048576) : null;
  });
  protected readonly maxErrorsValid = computed(() => {
    const n = this.maxErrors();
    return n !== null && Number.isInteger(n) && n >= 1 && n <= 500;
  });
  /** Not while the file's version is being read: the profile may still belong to the previous line. */
  protected readonly canSubmit = computed(
    () => this.file() !== null && this.maxErrorsValid() && !this.submitting() && !this.detecting(),
  );
  /** Value shown by the profile select: a sentinel for "none"/"custom", since option values are strings. */
  protected readonly profileSelection = computed(() =>
    this.mappingFile() ? CUSTOM : (this.validationProfile() ?? NONE),
  );
  protected readonly profileHint = computed(
    () => this.lineProfiles().find((p) => p.id === this.validationProfile())?.description ?? '',
  );

  protected onFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.setFile(input.files?.[0] ?? null);
    input.value = '';
  }

  protected onMappingInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.setMappingFile(input.files?.[0] ?? null);
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

  /**
   * Files over the limit are refused here: the server would close the
   * connection on a body far over the limit, and the upload would fail
   * without a useful message.
   */
  protected setFile(file: File | null): void {
    if (file && this.overUploadLimit(file)) {
      this.file.set(null);
      this.detectVersion(null);
      this.error.set(fileTooLargeMessage(this.maxUploadMb() ?? undefined));
      return;
    }
    this.file.set(file);
    this.error.set(null);
    this.detectVersion(file);
  }

  /**
   * Reads the declared version of the file. The previous version stays until the new one is
   * known, so a quick change between files of the same line keeps the chosen profile; a result
   * that arrives for a file no longer chosen is dropped.
   */
  private detectVersion(file: File | null): void {
    const run = ++this.detection;
    if (!file) {
      this.detecting.set(false);
      this.fileVersion.set(null);
      return;
    }
    this.detecting.set(true);
    this.pendingTasks.run(async () => {
      const version = await detectMzTabVersion(file).catch(() => null);
      if (run !== this.detection) return;
      this.fileVersion.set(version);
      this.detecting.set(false);
    });
  }

  private setMappingFile(file: File | null): void {
    if (file && file.size > MAX_MAPPING_BYTES) {
      this.mappingFile.set(null);
      this.error.set(MAPPING_TOO_LARGE);
      return;
    }
    this.mappingFile.set(file);
    this.error.set(null);
  }

  private overUploadLimit(file: File): boolean {
    const max = this.info()?.maxUploadBytes;
    return !!max && file.size > max;
  }

  protected onMaxErrors(event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    this.maxErrors.set(raw.trim() === '' ? null : Number(raw));
  }

  protected onProfile(value: string): void {
    this.validationProfile.set(value === NONE ? null : value);
  }

  protected submit(): void {
    this.submitAs(false);
  }

  /** "Open a file to fix": submits like Validate, then opens the editor once the result is final. */
  protected openToFix(): void {
    this.submitAs(true);
  }

  private submitAs(fix: boolean): void {
    const file = this.file();
    if (!file || !this.canSubmit()) return;
    const mapping = this.mappingFile();
    // the limit may have arrived after the file was chosen
    if (this.overUploadLimit(file)) {
      this.error.set(fileTooLargeMessage(this.maxUploadMb() ?? undefined));
      return;
    }
    if (mapping && mapping.size > MAX_MAPPING_BYTES) {
      this.error.set(MAPPING_TOO_LARGE);
      return;
    }
    const body: { file: Blob; mappingFile?: Blob } = { file };
    if (mapping) body.mappingFile = mapping;
    this.run(
      this.validations.createValidation({
        level: this.level(),
        maxErrors: this.maxErrors() ?? undefined,
        validationProfile: mapping ? undefined : this.validationProfile(),
        body,
      }),
      fix,
    );
  }

  /**
   * Validates a catalog example with the form's current options. The chosen profile belongs to
   * the active line; an example of another line gets its own line's default profile instead.
   */
  validateExample(id: string, mzTabVersion?: string): void {
    if (this.submitting() || !this.maxErrorsValid()) return;
    const line = lineOf(mzTabVersion) ?? this.info()?.defaultLine ?? null;
    const profile = line === this.activeLine() ? this.validationProfile() : line ? defaultProfileFor(line) : null;
    this.run(
      this.examples.validateExample({
        id,
        body: {
          level: this.level(),
          maxErrors: this.maxErrors(),
          validationProfile: profile,
        },
      }),
    );
  }

  private run(request: Observable<ValidationRecord>, fix = false): void {
    this.submitting.set(true);
    this.opening.set(fix);
    this.error.set(null);
    request
      .pipe(
        switchMap((record) => (fix ? this.awaitFinal(record) : of(record))),
        // leaving the page stops the wait
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
      next: (record) => {
        // a result that cannot be opened (failed without messages) is shown as after Validate
        this.router.navigate(fix && canFix(record) ? ['/edit', record.id] : ['/result', record.id]);
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.opening.set(false);
        this.error.set(this.messageFor(err));
      },
    });
  }

  /**
   * The record once FINISHED or FAILED; network failures while waiting are tolerated. A validation that is gone
   * (404) ends the wait as a failed one, so the result page says it was not found.
   */
  private awaitFinal(created: ValidationRecord): Observable<ValidationRecord> {
    const id = created.id ?? '';
    return timer(FIX_POLL_MS, FIX_POLL_MS).pipe(
      exhaustMap(() =>
        this.validations.getValidation({ id }, quietRequest()).pipe(
          catchError((err: unknown) =>
            of(err instanceof HttpErrorResponse && err.status === 404 ? ({ ...created, status: 'FAILED', messages: [] } as ValidationRecord) : undefined),
          ),
        ),
      ),
      filter((r): r is ValidationRecord => r?.status === 'FINISHED' || r?.status === 'FAILED'),
      take(1),
    );
  }

  private messageFor(err: unknown): string {
    return validationErrorMessage(err, this.maxUploadMb() ?? undefined);
  }

  protected size(file: File): string {
    return formatBytes(file.size);
  }
}
