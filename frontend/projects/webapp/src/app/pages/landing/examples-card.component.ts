import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

import { ExampleDto } from '../../api/models/example-dto';

const MAX_SHOWN = 6;

/** Compact list of example files for the landing page. */
@Component({
  selector: 'app-examples-card',
  imports: [MatButtonModule, RouterLink],
  templateUrl: './examples-card.component.html',
  styleUrl: './examples-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExamplesCardComponent {
  readonly examples = input<readonly ExampleDto[]>([]);
  readonly validate = output<string>();

  /** Example files, capped; mapping entries never take a slot. */
  protected readonly shown = computed(() =>
    this.examples()
      .filter((e) => e.section === 'example')
      .slice(0, MAX_SHOWN),
  );
  /** Mapping files are download-only (no Validate action). */
  protected readonly mappings = computed(() => this.examples().filter((e) => e.section === 'mapping'));
}
