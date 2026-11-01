import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';

import { TourButtonComponent } from '../../core/tour-button.component';
import { TRY_IT } from '../../core/tour-labels';
import { TutorialStore } from '../../core/tutorial.store';
import { ORIGINALS_FOLDER } from './excerpt';
import { ExcerptComponent } from './excerpt.component';
import { scrollToFragmentOnceRendered } from './scroll-to-fragment';

/**
 * A page of Understanding mzTab-M: the steps of one tour chapter, each with its lines of the teaching file.
 * The route data names the chapter and the file; the steps come from the same list that drives the tour.
 */
@Component({
  selector: 'app-format-chapter-page',
  imports: [ExcerptComponent, MatProgressSpinnerModule, RouterLink, TourButtonComponent],
  templateUrl: './format-chapter.page.html',
  styleUrl: './format-chapter.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormatChapterPage {
  private readonly route = inject(ActivatedRoute).snapshot;
  protected readonly chapter: string = this.route.data['chapter'];
  protected readonly exampleId: string = this.route.data['exampleId'];
  /** Relative, so that it resolves under the context path (<base href>). */
  protected readonly contentHref = `rest/v2/examples/${this.exampleId}/content`;

  private readonly store = inject(TutorialStore);
  private readonly loaded = toSignal(
    forkJoin({ tour: this.store.tour(this.exampleId), lines: this.store.lines(this.exampleId) }).pipe(
      map((l) => ({ steps: l.tour.steps ?? [], lines: l.lines })),
      catchError(() => of(null)),
    ),
    { initialValue: undefined },
  );
  protected readonly loading = computed(() => this.loaded() === undefined);
  protected readonly failed = computed(() => this.loaded() === null);
  protected readonly lines = computed(() => this.loaded()?.lines ?? []);
  protected readonly steps = computed(() =>
    (this.loaded()?.steps ?? []).filter((s) => s.chapter === this.chapter),
  );
  /** "Try it" opens the tour at this chapter's first step (1-based), when this instance offers the example. */
  private readonly offered = this.store.offered();
  protected readonly tryItLabel = TRY_IT;
  protected readonly tryItStep = computed(() => {
    if (!this.offered()?.has(this.exampleId)) return null;
    const i = (this.loaded()?.steps ?? []).findIndex((s) => s.chapter === this.chapter);
    return i < 0 ? null : i + 1;
  });
  /**
   * Where the file was adapted from: its own original when this instance lists the example, else the
   * examples of HUPO-PSI/mzTab-M, so that the attribution never depends on the list.
   */
  protected readonly originalHref = computed(
    () => this.store.examples()()?.get(this.exampleId)?.originalUri || ORIGINALS_FOLDER,
  );

  constructor() {
    // A deep link (/docs/format/metadata#step-what-file): scroll again once the steps are in the page.
    scrollToFragmentOnceRendered(() => this.steps().length > 0);
  }
}
