import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { Guidance } from '../../api/models/guidance';
import { ValidationMessage } from '../../api/models/validation-message';
import { DocTarget, targetOf } from './message-groups';

const REQUIREMENT = { error: 'required', warn: 'recommended', info: 'optional' } as const;

interface Cell {
  text: string;
  highlight: boolean;
}

interface Where {
  lineNumber: number;
  /** The MTD key, else the first cell of the line. */
  key: string;
  cells: Cell[];
  truncated: boolean;
  /** Where the first message on this line points in the document, if anywhere. */
  target: DocTarget | null;
}

/** Splits a raw line on TAB; the cells whose text is the message's column name are highlighted. */
export function cellsOf(text: string, column: string | null | undefined): Cell[] {
  return text.split('\t').map((t) => ({ text: t, highlight: !!column && t === column }));
}

/**
 * The fix guidance for one group of problems: what is wrong, where, how to fix it here and in the
 * user's tool. Presentational: `guidance.messages` indexes into `messages`.
 */
@Component({
  selector: 'app-guidance-panel',
  templateUrl: './guidance-panel.component.html',
  styleUrl: './guidance-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GuidancePanelComponent {
  readonly guidance = input.required<Guidance>();
  /** The record's messages, as `guidance.messages` indexes them. */
  readonly messages = input.required<readonly ValidationMessage[]>();
  readonly jump = output<DocTarget>();

  private readonly affected = computed(() =>
    (this.guidance().messages ?? [])
      .map((i) => this.messages()[i])
      .filter((m): m is ValidationMessage => !!m),
  );

  protected readonly where = computed<Where[]>(() =>
    (this.guidance().lines ?? []).map((l) => {
      const m = this.affected().find((a) => a.line_number === l.lineNumber);
      const text = l.text ?? '';
      const cells = cellsOf(text, m?.location?.column);
      const key = cells[0]?.text === 'MTD' ? (cells[1]?.text ?? '') : (cells[0]?.text ?? '');
      return {
        lineNumber: l.lineNumber ?? 0,
        key,
        cells,
        truncated: !!l.truncated,
        target: m ? targetOf(m) : null,
      };
    }),
  );
  /** Affected messages beyond the quoted lines. */
  protected readonly moreLines = computed(() =>
    Math.max(0, (this.guidance().messages ?? []).length - (this.guidance().lines ?? []).length),
  );
  protected readonly fixInTool = computed(
    () => this.guidance().toolFixInTool ?? this.guidance().fixInTool,
  );
  protected readonly isVariant = computed(() => !!this.guidance().toolFixInTool);
  /** "Rule X: required" for CV-rule messages, from the message level. */
  protected readonly rule = computed(() => {
    const m = this.affected().find((a) => !!a.rule_id);
    return m ? { id: m.rule_id!, requirement: REQUIREMENT[m.message_type ?? 'info'] } : null;
  });
}
