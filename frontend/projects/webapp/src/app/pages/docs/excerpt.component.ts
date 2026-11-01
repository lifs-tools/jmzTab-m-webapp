import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { TourStepDto } from '../../api/models/tour-step-dto';
import { excerptMarks } from './excerpt';

interface ExcerptLine {
  readonly no: number;
  /** The tab-separated fields of the line. */
  readonly fields: readonly string[];
  readonly marked: boolean;
}

/** The lines of a step from the teaching file, numbered, tabs shown as arrows, the step's element marked. */
@Component({
  selector: 'app-excerpt',
  imports: [NgTemplateOutlet],
  templateUrl: './excerpt.component.html',
  styleUrl: './excerpt.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExcerptComponent {
  /** The whole file, one entry per line. */
  readonly lines = input.required<readonly string[]>();
  readonly step = input.required<TourStepDto>();

  protected readonly range = computed(() => {
    const [from, to] = this.step().lines ?? [1, this.lines().length];
    return { from, to: Math.min(to, this.lines().length) };
  });
  protected readonly excerpt = computed<ExcerptLine[]>(() => {
    const { from, to } = this.range();
    const marks = excerptMarks(this.lines(), this.step());
    return this.lines()
      .slice(from - 1, to)
      .map((line, i) => ({ no: from + i, fields: line.split('\t'), marked: marks.has(from + i) }));
  });
}
