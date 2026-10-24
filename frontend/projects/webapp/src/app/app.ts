import { ChangeDetectionStrategy, Component, DOCUMENT, effect, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { AppInfoStore } from './core/app-info.store';
import { MatomoService } from './core/matomo.service';
import { AppBarComponent } from './shell/app-bar.component';
import { SiteFooterComponent } from './shell/site-footer.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, AppBarComponent, SiteFooterComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  private readonly document = inject(DOCUMENT);

  constructor() {
    const appInfo = inject(AppInfoStore);
    const matomo = inject(MatomoService);
    effect(() => {
      const info = appInfo.info();
      if (info) {
        matomo.init(info.analytics ?? null);
      }
    });
  }

  protected skipToMain(event: Event): void {
    event.preventDefault();
    this.document.getElementById('main')?.focus();
  }
}
