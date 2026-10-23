import { ChangeDetectionStrategy, Component, computed, inject, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';

import { ExampleDto } from '../../api/models/example-dto';
import { ExamplesService } from '../../api/services/examples.service';
import { AppInfoStore } from '../../core/app-info.store';
import { ExamplesCardComponent } from './examples-card.component';
import { ValidatorFormComponent } from './validator-form.component';

/** Landing page: hero, the validator form, example files and links for scripts. */
@Component({
  selector: 'app-landing-page',
  imports: [ValidatorFormComponent, ExamplesCardComponent],
  templateUrl: './landing.page.html',
  styleUrl: './landing.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LandingPage {
  private readonly form = viewChild.required(ValidatorFormComponent);
  private readonly appInfo = inject(AppInfoStore);

  protected readonly retentionDays = computed(() => this.appInfo.info()?.retentionDays);
  protected readonly examples = toSignal(
    inject(ExamplesService)
      .listExamples()
      .pipe(catchError(() => of([] as ExampleDto[]))),
    { initialValue: [] as ExampleDto[] },
  );

  protected validateExample(id: string): void {
    this.form().validateExample(id);
  }
}
