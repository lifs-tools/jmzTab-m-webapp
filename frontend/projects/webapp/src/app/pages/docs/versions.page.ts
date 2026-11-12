import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';

import { AppInfoStore } from '../../core/app-info.store';
import { QUICK_FIX_PREVIEW_LABEL, QUICK_FIX_TITLES } from '../../core/quick-fix-labels';
import { TutorialStore } from '../../core/tutorial.store';
import { ORIGINALS_FOLDER } from './excerpt';
import { ExcerptComponent } from './excerpt.component';
import { scrollToFragmentOnceRendered } from './scroll-to-fragment';

/** A row of "What changed in 2.1". */
export interface VerifiedChange {
  readonly change: string;
  /** What the 2.1 validator does; a difference from 2.0 only where `source.contrast` shows it. */
  readonly meaning: string;
  /** Whether, and when, a 2.0 file has to change for 2.1. */
  readonly mustChange: string;
  readonly source: {
    /** The validator class of jmzTab-m 2.1 that the row rests on; the page links its source file. */
    readonly basis: string;
    /** The test of TwoOneChangesTest (engine-mztabm-2.1) that checks the row against jmzTab-m 2.1. */
    readonly test: string;
    /** `Class.method` of the engine-mztabm-2.0 test that shows the 2.0 side of a claimed difference. */
    readonly contrast?: string;
  };
}

/**
 * What changed in mzTab-M 2.1, only as far as the 2.1 engine shows it: each row names its source and the test
 * that checks it on a small file, and TwoOneChangesTest checks that every test named here exists. The page shows
 * the source only; the tests are for the build. A change no test and no source show is left out (no claims without
 * a source).
 */
export const VERIFIED_CHANGES: readonly VerifiedChange[] = [
  {
    change: 'mzTab-version 2.1.0-M',
    meaning: 'The 2.1 validator reads a file that declares mzTab-version 2.1.0-M and checks it with jmzTab-m 2.1.',
    mustChange: 'Yes: a 2.1 file declares 2.1.0-M in its version line.',
    source: { basis: 'MzTabVersionValidator', test: 'acceptsVersion210M' },
  },
  {
    change: 'mzTab-profile',
    meaning:
      'The profile states which sections the file has, for example M+S+F+E for metadata, summary, features and ' +
      'evidence, or M+S for metadata and a summary only. For a file that declares none, the 2.1 validator infers ' +
      'the profile and reports it (Info-2052).',
    mustChange: 'No: a file without a profile stays valid.',
    source: { basis: 'MzTabProfileValidator', test: 'reportsMissingProfileAsInfo2052' },
  },
  {
    change: 'Fields of absent sections',
    meaning:
      'The 2.1 validator reports a metadata field that belongs to a section the profile leaves out, such as ' +
      'small_molecule_feature-quantification_unit in an M+S file (Info-2057).',
    mustChange: 'No: the file stays valid; the field can be removed.',
    source: { basis: 'MzTabProfileValidator', test: 'reportsFieldOfAbsentSectionAsInfo2057' },
  },
  {
    change: 'study_variable_group',
    meaning:
      'The 2.1 validator reads study variable groups, which describe the factors of a study design: a group is ' +
      'a factor, such as a treatment, and the study variables it lists are its levels.',
    mustChange: 'No: it is optional.',
    source: { basis: 'StudyVariableGroupValidator', test: 'readsStudyVariableGroups' },
  },
  {
    change: 'study_variable[n]-factors',
    meaning:
      'The 2.1 validator warns about study_variable[n]-factors (Warn-2067): in 2.1, study_variable_group ' +
      'describes the factors. The 2.0 validator accepts them without this warning.',
    mustChange: 'Yes, if the file has it: describe the factors with study_variable_group instead.',
    source: {
      basis: 'StudyVariableFactorsValidator',
      test: 'reportsStudyVariableFactorsAsWarn2067',
      contrast: 'FactorsTwoZeroTest.acceptsFactorsWithoutWarn2067',
    },
  },
];

/**
 * The tools known to write mzTab-M 2.1, each with the source that says so; empty until a tool has adopted it.
 */
export const TOOLS_WRITING_21: readonly { readonly name: string; readonly url: string; readonly source: string }[] =
  [];

const TEACHING_21 = 'teaching-lipidomics-2.1.mztab';

/** The validators of jmzTab-m 2.1 on GitHub; its master branch is the 2.1 line. */
const JMZTABM_21_VALIDATORS =
  'https://github.com/lifs-tools/jmzTab-m/blob/master/io/src/main/java/org/lifstools/mztab2/io/validators/';

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
  /** False while app-info is loading and when it failed: then the page says nothing about 2.1 on this instance. */
  protected readonly known = computed(() => this.info() !== undefined);
  /** True when the 2.1 engine is configured and answering. */
  protected readonly validates21 = computed(() =>
    this.engines().some((e) => e.line === '2.1' && e.available),
  );
  /** Where 2.1 files can be validated when this instance cannot. */
  protected readonly hintUrl = computed(() => this.info()?.unavailableHintUrl?.trim() || null);

  protected readonly changes = VERIFIED_CHANGES;
  protected sourceOf(c: VerifiedChange): string {
    return `${JMZTABM_21_VALIDATORS}${c.source.basis}.java`;
  }
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

  constructor() {
    // A deep link below the excerpt (#converting, #validating-21, #tools-21): scroll again once the excerpt, or
    // the note that it could not be loaded, is in the page. The engines table above it depends on app-info,
    // which the app requests at start (app.ts), before this page's own requests.
    scrollToFragmentOnceRendered(() => this.profile() !== undefined);
  }
}
