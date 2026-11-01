import { ChangeDetectionStrategy, Component, computed, inject, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';

import { ExampleDto } from '../../api/models/example-dto';
import { ExamplesService } from '../../api/services/examples.service';
import { quietRequest } from '../../core/http-error.interceptor';
import { AppInfoStore } from '../../core/app-info.store';
import { AboutMzTabmComponent } from './about-mztabm.component';
import { ExamplesCardComponent } from './examples-card.component';
import { ValidatorFormComponent } from './validator-form.component';

/** Landing page: hero, the validator form, example files, links for scripts and a short section on mzTab-M. */
@Component({
  selector: 'app-landing-page',
  imports: [ValidatorFormComponent, ExamplesCardComponent, AboutMzTabmComponent],
  templateUrl: './landing.page.html',
  styleUrl: './landing.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LandingPage {
  private readonly form = viewChild.required(ValidatorFormComponent);
  private readonly appInfo = inject(AppInfoStore);

  protected readonly retentionDays = computed(() => this.appInfo.info()?.retentionDays);
  protected readonly defaultLine = computed(() => this.appInfo.info()?.defaultLine);
  protected readonly examples = toSignal(
    inject(ExamplesService)
      .listExamples(undefined, quietRequest())
      .pipe(catchError(() => of([] as ExampleDto[]))),
    { initialValue: [] as ExampleDto[] },
  );

  protected validateExample(example: ExampleDto): void {
    if (example.id) this.form().validateExample(example.id, example.mzTabVersion);
  }
}
