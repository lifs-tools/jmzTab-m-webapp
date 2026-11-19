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
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, linkedSignal, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Router, RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';

import { ValidationProfile } from '../../api/models/validation-profile';
import { ValidationProfilesService } from '../../api/services/validation-profiles.service';
import { WorkingCopiesService } from '../../api/services/working-copies.service';
import { AppInfoStore } from '../../core/app-info.store';
import { quietRequest } from '../../core/http-error.interceptor';
import { lineOf } from '../../core/mztab-version';
import { defaultProfileFor } from '../../core/validation-defaults';

/** The content profiles (`mzTab-profile`) of mzTab-M 2.1, each with its meaning in one line. */
export const CONTENT_PROFILES: readonly { value: string; meaning: string }[] = [
  { value: 'M', meaning: 'Metadata only.' },
  { value: 'M+S', meaning: 'Metadata and the summary table (SML).' },
  { value: 'M+F', meaning: 'Metadata and the feature table (SMF).' },
  { value: 'M+F+E', meaning: 'Metadata, features (SMF) and evidence (SME).' },
  { value: 'M+S+F+E', meaning: 'All four sections.' },
];
/** The version line whose files have no content profile; every other line needs one (as the server decides). */
const NO_CONTENT_PROFILE_LINE = '2.0';
/** The `mzTab-ID` the server takes: a letter or digit, then up to 63 letters, digits, dots, underscores or hyphens. */
export const MZTAB_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const CREATE_FAILED = 'The file could not be created. Please try again.';

interface VersionOption {
  value: string;
  label: string;
}
interface RulesOption {
  /** `id@version`: a profile has several versions. */
  value: string;
  label: string;
  profile: ValidationProfile;
}

/** Orders profile versions numerically, part by part: 1.10.0 after 1.9.0. */
function compareVersions(a: string | undefined, b: string | undefined): number {
  const pa = (a ?? '').split('.').map((p) => parseInt(p, 10) || 0);
  const pb = (b ?? '').split('.').map((p) => parseInt(p, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d) return d;
  }
  return 0;
}

/**
 * New file (`new`): a short form with the three choices kept apart in its words (N11): the **mzTab-M version**
 * (those of the configured engines, the server's default preselected), for 2.1 the **content profile**, and the
 * **repository rules** (the catalog profiles that list the version). With the mzTab-ID, the server makes the file
 * from the engine's skeleton, and the editor opens on it.
 */
@Component({
  selector: 'app-new-file-page',
  imports: [MatButtonModule, MatFormFieldModule, MatInputModule, RouterLink],
  templateUrl: './new-file.page.html',
  styleUrl: './new-file.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewFilePage {
  private readonly api = inject(WorkingCopiesService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly info = inject(AppInfoStore).info;

  protected readonly contentProfiles = CONTENT_PROFILES;
  /** The catalog's profiles; undefined while they load, null if they could not be read. */
  private readonly profiles = toSignal(
    inject(ValidationProfilesService)
      .listValidationProfiles(undefined, quietRequest())
      .pipe(catchError(() => of(null as ValidationProfile[] | null))),
  );
  protected readonly profilesLoading = computed(() => this.profiles() === undefined);
  protected readonly profilesFailed = computed(() => this.profiles() === null);
  /** The app info (the engines) is not in yet; the store keeps it undefined also when it could not be read. */
  protected readonly infoLoading = computed(() => this.info() === undefined);

  /** The declared versions of the configured engines; one that did not answer lately says so. */
  protected readonly versions = computed<VersionOption[]>(() =>
    (this.info()?.engines ?? []).flatMap((e) =>
      (e.mzTabVersions ?? []).map((v) => ({
        value: v,
        label: e.available === false ? `${v} (temporarily unavailable)` : v,
      })),
    ),
  );
  readonly version = linkedSignal<VersionOption[], string | null>({
    source: this.versions,
    computation: (versions, previous) => {
      if (previous?.value && versions.some((v) => v.value === previous.value)) return previous.value;
      const line = this.info()?.defaultLine;
      return (versions.find((v) => lineOf(v.value) === line) ?? versions[0])?.value ?? null;
    },
  });
  protected readonly line = computed(() => lineOf(this.version()));
  protected readonly needsContentProfile = computed(() => !!this.line() && this.line() !== NO_CONTENT_PROFILE_LINE);
  /** Chosen anew for each version: the meanings are of its line. */
  readonly contentProfile = linkedSignal<string | null, string | null>({
    source: this.version,
    computation: () => null,
  });

  protected readonly rulesOptions = computed<RulesOption[]>(() => {
    const version = this.version();
    return (this.profiles() ?? [])
      .filter((p) => !!version && (p.mzTabVersions ?? []).includes(version))
      .map((p) => ({ value: `${p.id}@${p.version}`, label: `${p.title ?? p.id} ${p.version}`, profile: p }));
  });
  /** The latest version of the line's default profile, else the first one listed. */
  readonly rules = linkedSignal<RulesOption[], string | null>({
    source: this.rulesOptions,
    computation: (options, previous) => {
      if (previous?.value && options.some((o) => o.value === previous.value)) return previous.value;
      const line = this.line();
      const defaults = options
        .filter((o) => !!line && o.profile.id === defaultProfileFor(line))
        .sort((a, b) => compareVersions(b.profile.version, a.profile.version));
      return (defaults[0] ?? options[0])?.value ?? null;
    },
  });
  protected readonly rulesHint = computed(
    () => this.rulesOptions().find((o) => o.value === this.rules())?.profile.description ?? '',
  );

  readonly mzTabId = signal('');
  protected readonly idValid = computed(() => MZTAB_ID.test(this.mzTabId()));
  protected readonly idInvalid = computed(() => this.mzTabId() !== '' && !this.idValid());

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Why Create file is disabled, where the form does not show it already: a 2.1 file needs a content profile. */
  protected readonly waitingFor = computed(() =>
    this.needsContentProfile() && !this.contentProfile() ? 'Choose a content profile.' : null,
  );
  protected readonly canSubmit = computed(
    () =>
      !!this.version() &&
      !!this.rules() &&
      (!this.needsContentProfile() || !!this.contentProfile()) &&
      this.idValid() &&
      !this.submitting(),
  );

  protected onVersion(event: Event): void {
    this.version.set((event.target as HTMLSelectElement).value);
    this.error.set(null);
  }

  protected onRules(event: Event): void {
    this.rules.set((event.target as HTMLSelectElement).value);
    this.error.set(null);
  }

  protected onContentProfile(value: string): void {
    this.contentProfile.set(value);
    this.error.set(null);
  }

  protected onId(event: Event): void {
    this.mzTabId.set((event.target as HTMLInputElement).value.trim());
  }

  protected submit(): void {
    if (!this.canSubmit()) return;
    const rules = this.rulesOptions().find((o) => o.value === this.rules())!.profile;
    this.submitting.set(true);
    this.error.set(null);
    this.api
      .createNewWorkingCopy(
        {
          body: {
            mzTabVersion: this.version()!,
            contentProfile: this.needsContentProfile() ? this.contentProfile() : null,
            profileId: rules.id,
            profileVersion: rules.version,
            mzTabId: this.mzTabId(),
          },
        },
        quietRequest(),
      )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (copy) => {
          this.submitting.set(false);
          void this.router.navigate(['/edit', copy.id]);
        },
        error: (err: unknown) => {
          this.submitting.set(false);
          this.error.set(reasonOf(err) ?? CREATE_FAILED);
        },
      });
  }
}

/** The server's reason for a refusal (422), a bad request, or a full or busy server (503). */
function reasonOf(err: unknown): string | null {
  if (!(err instanceof HttpErrorResponse) || ![400, 422, 503].includes(err.status)) return null;
  const message = typeof err.error === 'object' && err.error ? (err.error as { message?: unknown }).message : null;
  return typeof message === 'string' && message.trim() ? message : null;
}
