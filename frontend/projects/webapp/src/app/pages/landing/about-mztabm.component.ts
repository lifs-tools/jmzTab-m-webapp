import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { TourButtonComponent } from '../../core/tour-button.component';
import { INTRO_TOUR } from '../../core/tour.service';
import { TutorialStore } from '../../core/tutorial.store';

/** The short "About mzTab-M" section below the validator form: what the format is, what it is for, who uses it. */
@Component({
  selector: 'app-about-mztabm',
  imports: [RouterLink, TourButtonComponent],
  templateUrl: './about-mztabm.component.html',
  styleUrl: './about-mztabm.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AboutMzTabmComponent {
  protected readonly tour = INTRO_TOUR;
  private readonly offered = inject(TutorialStore).offered();
  protected readonly tourOffered = computed(() => !!this.offered()?.has(INTRO_TOUR));
}
