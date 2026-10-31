import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AppInfoStore } from '../../core/app-info.store';

/** "Fixing a file": how to read a result, fix a file in the editor, and what is kept and for how long. */
@Component({
  selector: 'app-guide-page',
  imports: [RouterLink],
  templateUrl: './guide.page.html',
  styleUrl: './guide.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GuidePage {
  protected readonly info = inject(AppInfoStore).info;
}
