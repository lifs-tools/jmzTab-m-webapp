import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatMenuModule } from '@angular/material/menu';
import { RouterLink, RouterLinkActive } from '@angular/router';

interface NavItem {
  readonly label: string;
  /** In-app route, or an href that opens in a new tab. */
  readonly route?: string;
  readonly href?: string;
}

/** The lifs-tools.org nav bar, with the app name and the validator's sections. */
@Component({
  selector: 'app-bar',
  imports: [RouterLink, RouterLinkActive, MatMenuModule],
  templateUrl: './app-bar.component.html',
  styleUrl: './app-bar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppBarComponent {
  protected readonly githubUrl = 'https://github.com/lifs-tools/jmzTab-m-webapp';

  protected readonly items: readonly NavItem[] = [
    { label: 'Validate', route: '/' },
    { label: 'Examples', route: '/examples' },
    // Relative to <base href>, so it works under the servlet context path.
    { label: 'REST API', href: 'swagger-ui/index.html' },
    { label: 'About', route: '/about' },
    { label: 'LIFS Tools ↗', href: 'https://lifs-tools.org/' },
  ];
}
