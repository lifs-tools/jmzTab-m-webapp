import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AppInfoStore } from '../../core/app-info.store';

/** Getting started: which route is yours, depending on what you have. */
@Component({
  selector: 'app-getting-started-page',
  imports: [RouterLink],
  templateUrl: './getting-started.page.html',
  styleUrl: './getting-started.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GettingStartedPage {
  protected readonly info = inject(AppInfoStore).info;
}
