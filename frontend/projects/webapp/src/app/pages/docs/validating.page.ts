import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AppInfoStore } from '../../core/app-info.store';

/** "Validating a file": validation levels, profiles and mapping files, network access, and reading the result. */
@Component({
  selector: 'app-validating-page',
  imports: [RouterLink],
  templateUrl: './validating.page.html',
  styleUrl: './validating.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ValidatingPage {
  protected readonly info = inject(AppInfoStore).info;
}
