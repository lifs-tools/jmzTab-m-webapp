import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { AppInfoStore } from '../../core/app-info.store';

/** About this service: retention and privacy, how to cite, and the version. */
@Component({
  selector: 'app-service-about-page',
  imports: [],
  templateUrl: './service-about.page.html',
  styleUrl: './service-about.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServiceAboutPage {
  protected readonly info = inject(AppInfoStore).info;
}
