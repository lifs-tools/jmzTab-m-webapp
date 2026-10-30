import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

import { AppInfoStore } from '../../core/app-info.store';

/** Shown for unknown routes, in the style of the LIFS error pages. */
@Component({
  selector: 'app-not-found-page',
  imports: [MatButtonModule, RouterLink],
  templateUrl: './not-found.page.html',
  styleUrl: './not-found.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotFoundPage {
  private readonly appInfo = inject(AppInfoStore);
  protected readonly retentionDays = computed(() => this.appInfo.info()?.retentionDays);
}
