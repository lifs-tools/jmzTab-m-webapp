import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

import { TourDto } from '../../api/models/tour-dto';
import {
  TOUR_BACK,
  TOUR_CLOSE,
  TOUR_NEXT,
  VALIDATE_YOUR_OWN_FILE,
  tourSwitchLabel,
} from '../../core/tour-labels';

/**
 * The tour of a teaching file, in place of the messages: the step's title, text and what to look for,
 * Back / Next and Close, and a switch to the same tour on the other mzTab-M version. The result page drives
 * the viewer; the panel only reports what was pressed. The tour never edits the file.
 */
@Component({
  selector: 'app-tour-panel',
  imports: [MatButtonModule, RouterLink],
  templateUrl: './tour-panel.component.html',
  styleUrl: './tour-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TourPanelComponent {
  private readonly injector = inject(Injector);
  protected readonly labels = { back: TOUR_BACK, next: TOUR_NEXT, close: TOUR_CLOSE, ownFile: VALIDATE_YOUR_OWN_FILE };
  protected readonly switchLabel = tourSwitchLabel;
  readonly tour = input.required<TourDto>();
  /** 0-based index of the step shown. */
  readonly index = input.required<number>();
  readonly counterpartOffered = input(false);
  /** The version line of the counterpart, e.g. `2.1`, for the switch's label. */
  readonly counterpartVersion = input<string | null>(null);
  readonly back = output<void>();
  readonly next = output<void>();
  readonly close = output<void>();
  readonly switchVersion = output<void>();

  private readonly backButton = viewChild<string, ElementRef<HTMLButtonElement>>('backButton', { read: ElementRef });
  private readonly nextButton = viewChild<string, ElementRef<HTMLButtonElement>>('nextButton', { read: ElementRef });
  private readonly ownFile = viewChild<string, ElementRef<HTMLAnchorElement>>('ownFile', { read: ElementRef });

  protected readonly steps = computed(() => this.tour().steps ?? []);
  protected readonly step = computed(() => this.steps()[this.index()]);
  protected readonly last = computed(() => this.index() >= this.steps().length - 1);

  /**
   * Back and Next keep the focus on the panel: when the pressed button goes away (Next on the last step)
   * or is disabled (Back on the first), the focus moves to the other one.
   */
  protected onBack(): void {
    const moveFocus = this.index() === 1 && this.hasFocus(this.backButton());
    this.back.emit();
    if (moveFocus) afterNextRender(() => this.nextButton()?.nativeElement.focus(), { injector: this.injector });
  }

  protected onNext(): void {
    const moveFocus = this.index() === this.steps().length - 2 && this.hasFocus(this.nextButton());
    this.next.emit();
    if (moveFocus) afterNextRender(() => this.ownFile()?.nativeElement.focus(), { injector: this.injector });
  }

  private hasFocus(el: ElementRef<HTMLElement> | undefined): boolean {
    return !!el && el.nativeElement.ownerDocument.activeElement === el.nativeElement;
  }
}
