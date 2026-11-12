import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

import { ExampleDto } from '../../api/models/example-dto';
import { lineOf } from '../../core/mztab-version';

const MAX_SHOWN = 6;

/** Compact list of example files of the default mzTab-M version line for the landing page. */
@Component({
  selector: 'app-examples-card',
  imports: [MatButtonModule, RouterLink],
  templateUrl: './examples-card.component.html',
  styleUrl: './examples-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExamplesCardComponent {
  readonly examples = input<readonly ExampleDto[]>([]);
  /** Only entries of this line are shown; all while it is unknown (app info not loaded). */
  readonly defaultLine = input<string | null | undefined>(null);
  readonly validate = output<ExampleDto>();

  private readonly ofDefaultLine = computed(() => {
    const line = this.defaultLine();
    return line ? this.examples().filter((e) => lineOf(e.mzTabVersion) === line) : this.examples();
  });
  /** Example files, capped; mapping entries never take a slot. */
  protected readonly shown = computed(() =>
    this.ofDefaultLine()
      .filter((e) => e.section === 'example')
      .slice(0, MAX_SHOWN),
  );
  /** Mapping files are download-only (no Validate action). */
  protected readonly mappings = computed(() => this.ofDefaultLine().filter((e) => e.section === 'mapping'));
}
