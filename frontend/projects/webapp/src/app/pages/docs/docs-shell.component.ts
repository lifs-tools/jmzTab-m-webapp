import { BreakpointObserver } from '@angular/cdk/layout';
import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';

import { routerPath } from '../../core/router-path';
import { DocsTocComponent } from './docs-toc.component';
import { DOCS_HOME, DOCS_TREE, docsNeighbours, docsTitle } from './docs-tree';

const WIDE_QUERY = '(min-width: 1100px)';

/**
 * The documentation layout: the side navigation of the docs tree, the page title, "On this page" and
 * previous/next links around the routed page. Below 1100 px the navigation collapses into "Contents".
 */
@Component({
  selector: 'app-docs-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, NgTemplateOutlet, DocsTocComponent],
  templateUrl: './docs-shell.component.html',
  styleUrl: './docs-shell.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocsShellComponent {
  protected readonly home = DOCS_HOME;
  protected readonly chapters = DOCS_TREE;

  private readonly path = routerPath();
  protected readonly title = computed(() => docsTitle(this.path()));
  protected readonly neighbours = computed(() => docsNeighbours(this.path()));

  protected readonly wide = toSignal(
    inject(BreakpointObserver)
      .observe(WIDE_QUERY)
      .pipe(map((s) => s.matches)),
    { initialValue: true },
  );
  /** Whether the collapsed navigation ("Contents") is open. */
  protected readonly contentsOpen = signal(false);
}
