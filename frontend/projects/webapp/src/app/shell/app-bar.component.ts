import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatMenuModule } from '@angular/material/menu';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { routerPath } from '../core/router-path';
import { DOCS_HOME, DOCS_TREE } from '../pages/docs/docs-tree';

interface NavItem {
  readonly label: string;
  /** In-app route, or an href that opens in a new tab. */
  readonly route?: string;
  readonly href?: string;
  /** Opens these items instead; active on every page under `route`. */
  readonly menu?: readonly NavItem[];
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

  private readonly path = routerPath();

  protected readonly items: readonly NavItem[] = [
    { label: 'Validate', route: '/' },
    { label: 'Examples', route: '/examples' },
    {
      label: 'Documentation',
      route: DOCS_HOME.path,
      menu: [
        { label: 'All documentation', route: DOCS_HOME.path },
        ...DOCS_TREE.map((n) => ({ label: n.title, route: n.path })),
      ],
    },
    // Relative to <base href>, so it works under the servlet context path.
    { label: 'REST API', href: 'swagger-ui/index.html' },
    { label: 'About', route: '/about' },
    { label: 'LIFS Tools ↗', href: 'https://lifs-tools.org/' },
  ];

  /** Whether the current page is `route` or below it. */
  protected within(route: string): boolean {
    const path = this.path();
    return path === route || path.startsWith(route + '/');
  }
}
