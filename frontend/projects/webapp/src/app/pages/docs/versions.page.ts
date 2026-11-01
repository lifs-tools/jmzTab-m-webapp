import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';

import { AppInfoStore } from '../../core/app-info.store';
import { QUICK_FIX_PREVIEW_LABEL, QUICK_FIX_TITLES } from '../../core/quick-fix-labels';
import { TutorialStore } from '../../core/tutorial.store';
import { ORIGINALS_FOLDER } from './excerpt';
import { ExcerptComponent } from './excerpt.component';

/** A row of "What changed in 2.1". */
export interface VerifiedChange {
  readonly change: string;
  readonly meaning: string;
  /** Whether, and when, a 2.0 file has to change for 2.1. */
  readonly mustChange: string;
  /** The test of TwoOneChangesTest (engine-mztabm-2.1) that checks this row against jmzTab-m 2.1. */
  readonly test: string;
}

/**
 * What changed in mzTab-M 2.1, only as far as the 2.1 engine shows it: each row names the test that checks it
 * on a small file, and TwoOneChangesTest checks that every test named here exists. A change no test shows is
 * left out (no claims without a source).
 */
export const VERIFIED_CHANGES: readonly VerifiedChange[] = [
  {
    change: 'mzTab-version 2.1.0-M',
    meaning: 'A 2.1 file declares the version 2.1.0-M; the validator reads it and checks the file with jmzTab-m 2.1.',
    mustChange: 'Yes: the version line names 2.1.0-M.',
    test: 'acceptsVersion210M',
  },
  {
    change: 'mzTab-profile',
    meaning:
      'The profile states which sections the file has, for example M+S+F+E for metadata, summary, features and ' +
      'evidence, or M+S for metadata and a summary only.',
    mustChange: 'No: without a profile, the validator infers it from the sections present (Info-2052).',
    test: 'reportsMissingProfileAsInfo2052',
  },
  {
    change: 'Fields of absent sections',
    meaning:
      'A metadata field that belongs to a section the profile leaves out, such as ' +
      'small_molecule_feature-quantification_unit in an M+S file, is reported (Info-2057).',
    mustChange: 'No: the message is an Info; removing the field makes the file match its profile.',
    test: 'reportsFieldOfAbsentSectionAsInfo2057',
  },
  {
    change: 'study_variable_group',
    meaning:
      'Describes the factors of a study design: a group is a factor, such as a treatment, and the study ' +
      'variables it lists are its levels.',
    mustChange: 'No: it is optional.',
    test: 'readsStudyVariableGroups',
  },
  {
    change: 'study_variable[n]-factors',
    meaning: 'Replaced by study_variable_group; the validator warns about it in a 2.1 file (Warn-2067).',
    mustChange: 'Yes, if the file has it: describe the factors with study_variable_group instead.',
    test: 'reportsStudyVariableFactorsAsWarn2067',
  },
  {
    change: 'Feature charge may be null',
    meaning: 'A feature (SMF) whose charge is not known reports null in the charge column.',
    mustChange: 'No.',
    test: 'acceptsNullSmfCharge',
  },
];

/**
 * The tools known to write mzTab-M 2.1, each with the source that says so; empty until a tool has adopted it.
 */
export const TOOLS_WRITING_21: readonly { readonly name: string; readonly url: string; readonly source: string }[] =
  [];

const TEACHING_21 = 'teaching-lipidomics-2.1.mztab';

/**
 * mzTab-M 2.0 and 2.1: the versions this instance validates and the engine behind each, what changed in 2.1,
 * converting a 2.0 file and validating 2.1.
 */
@Component({
  selector: 'app-versions-page',
  imports: [ExcerptComponent, RouterLink],
  templateUrl: './versions.page.html',
  styleUrl: './versions.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VersionsPage {
  private readonly info = inject(AppInfoStore).info;
  /** The mzTab-M version lines this instance is configured for, with their engines. */
  protected readonly engines = computed(() => this.info()?.engines ?? []);
  /** True when the 2.1 engine is configured and answering. */
  protected readonly validates21 = computed(() =>
    this.engines().some((e) => e.line === '2.1' && e.available),
  );
  /** Where 2.1 files can be validated when this instance cannot. */
  protected readonly hintUrl = computed(() => this.info()?.unavailableHintUrl?.trim() || null);

  protected readonly changes = VERIFIED_CHANGES;
  protected readonly tools = TOOLS_WRITING_21;
  protected readonly removeFieldTitle = QUICK_FIX_TITLES['remove-mismatched-field'];
  protected readonly previewLabel = QUICK_FIX_PREVIEW_LABEL;

  protected readonly teachingFile = TEACHING_21;
  /** Relative, so that it resolves under the context path (<base href>). */
  protected readonly contentHref = `rest/v2/examples/${TEACHING_21}/content`;
  private readonly store = inject(TutorialStore);
  /** The profile step of the 2.1 teaching file, with the file's lines; null if they could not be loaded. */
  protected readonly profile = toSignal(
    forkJoin({ tour: this.store.tour(TEACHING_21), lines: this.store.lines(TEACHING_21) }).pipe(
      map(({ tour, lines }) => {
        const step = tour.steps?.find((s) => s.id === 'profile');
        return step ? { step, lines } : null;
      }),
      catchError(() => of(null)),
    ),
    { initialValue: undefined },
  );
  protected readonly originalHref = computed(
    () => this.store.examples()()?.get(TEACHING_21)?.originalUri || ORIGINALS_FOLDER,
  );
}
