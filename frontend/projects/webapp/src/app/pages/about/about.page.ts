import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AppInfoStore } from '../../core/app-info.store';

/** About: what the validator is, validation levels and profiles, retention, privacy, citation. */
@Component({
  selector: 'app-about-page',
  imports: [RouterLink],
  templateUrl: './about.page.html',
  styleUrl: './about.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AboutPage {
  protected readonly info = inject(AppInfoStore).info;
}
