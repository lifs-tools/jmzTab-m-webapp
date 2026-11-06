import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { TAKE_THE_TOUR } from './tour-labels';
import { TourService } from './tour.service';
import { validationErrorMessage } from './validation-error';

/**
 * "Take the tour" / "Try it": starts the tour of a tutorial example at a step. The host shows it only when
 * the example is offered (`TutorialStore.offered()`); a failed validation is reported next to the button.
 */
@Component({
  selector: 'app-tour-button',
  template: `<button type="button" class="tour-button" [disabled]="busy()" (click)="start()">{{ label() }}</button>
    @if (error(); as msg) {
      <span class="tour-error" role="alert">{{ msg }}</span>
    }`,
  styles: `
    .tour-button {
      border: 0;
      padding: 0;
      background: none;
      font: inherit;
      color: var(--lifs-primary);
      text-decoration: underline;
      text-underline-offset: 2px;
      cursor: pointer;

      &:disabled {
        color: var(--lifs-slate);
        cursor: progress;
      }
    }
    .tour-error {
      margin-left: 8px;
      color: var(--lifs-error);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TourButtonComponent {
  private readonly tours = inject(TourService);
  /** A tour still being started when the user leaves the page must not navigate any more. */
  private readonly destroyRef = inject(DestroyRef);
  readonly exampleId = input.required<string>();
  /** 1-based. */
  readonly step = input(1);
  readonly label = input(TAKE_THE_TOUR);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected start(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.tours
      .start(this.exampleId(), this.step())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        complete: () => this.busy.set(false),
        error: (err: unknown) => {
          this.busy.set(false);
          this.error.set(validationErrorMessage(err));
        },
      });
  }
}
