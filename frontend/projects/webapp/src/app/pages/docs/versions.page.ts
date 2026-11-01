import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { AppInfoStore } from '../../core/app-info.store';

/** The mzTab-M versions this instance validates and the engine behind each. */
@Component({
  selector: 'app-versions-page',
  imports: [],
  templateUrl: './versions.page.html',
  styleUrl: './versions.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VersionsPage {
  private readonly info = inject(AppInfoStore).info;
  /** The mzTab-M version lines this instance is configured for, with their engines. */
  protected readonly engines = computed(() => this.info()?.engines ?? []);
}
