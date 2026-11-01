import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { DOCS_TREE } from './docs-tree';

/** The documentation's main page: the format in brief, mzTab-M and jmzTab-m, and the chapters. */
@Component({
  selector: 'app-docs-home-page',
  imports: [RouterLink],
  templateUrl: './docs-home.page.html',
  styleUrl: './docs-home.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocsHomePage {
  protected readonly chapters = DOCS_TREE;
}
