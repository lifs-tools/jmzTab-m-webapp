import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AppInfoStore } from '../../core/app-info.store';
import { TourButtonComponent } from '../../core/tour-button.component';
import { INTRO_TOUR } from '../../core/tour.service';
import { TutorialStore } from '../../core/tutorial.store';

/** Getting started: which route is yours, depending on what you have. */
@Component({
  selector: 'app-getting-started-page',
  imports: [RouterLink, TourButtonComponent],
  templateUrl: './getting-started.page.html',
  styleUrl: './getting-started.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GettingStartedPage {
  protected readonly info = inject(AppInfoStore).info;
  /** Only once app-info has answered: while it loads or when it failed, nothing is known about the link. */
  protected readonly templateUnlinked = computed(() => {
    const info = this.info();
    return info !== undefined && !info.submissionTemplateUrl;
  });
  protected readonly tour = INTRO_TOUR;
  private readonly offered = inject(TutorialStore).offered();
  protected readonly tourOffered = computed(() => !!this.offered()?.has(INTRO_TOUR));
}
