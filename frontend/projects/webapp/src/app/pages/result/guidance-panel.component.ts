import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';

import { Guidance } from '../../api/models/guidance';
import { ValidationMessage } from '../../api/models/validation-message';
import { DocTarget, targetOf } from './message-groups';

const REQUIREMENT = { error: 'required', warn: 'recommended', info: 'optional' } as const;
/** The table lines whose header the editor knows; their cells are highlighted by the header's column index. */
const DATA_PREFIXES = new Set(['SML', 'SMF', 'SME']);
/**
 * The quick fixes that can fix every message of a group at once (the server's QuickFix.appliesToAll); the
 * others fix one message per preview. "reencode-latin1" re-creates the copy: the editor never offers it here.
 */
export const FIXES_FOR_ALL = new Set(['remove-empty-parameter', 'normalise-doi', 'join-broken-line', 'split-mtd-line']);
const NOT_A_PREVIEW = 'reencode-latin1';

/** Whether quick fixes can be previewed now (the editor), or not yet (its latest changes are being checked). */
export type QuickFixState = 'ready' | 'checking';

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

/**
 * Splits a raw line on TAB. The cell of the message's column is highlighted: on a data line (SML, SMF, SME)
 * whose header is known, by the column's index in the header (cell 0 is the prefix); otherwise the cells whose
 * text is the column name (header lines, MTD keys).
 */
export function cellsOf(
  text: string,
  column: string | null | undefined,
  headers?: ReadonlyMap<string, readonly string[]> | null,
): Cell[] {
  const parts = text.split('\t');
  const header = headers?.get(parts[0]);
  if (column && header && DATA_PREFIXES.has(parts[0])) {
    const at = header.indexOf(column) + 1;
    return parts.map((t, i) => ({ text: t, highlight: at > 0 && i === at }));
  }
  return parts.map((t) => ({ text: t, highlight: !!column && t === column }));
}

/**
 * The fix guidance for one group of problems: what is wrong, where, how to fix it here and in the
 * user's tool. Presentational: `guidance.messages` indexes into `messages`.
 */
@Component({
  selector: 'app-guidance-panel',
  imports: [MatButtonModule],
  templateUrl: './guidance-panel.component.html',
  styleUrl: './guidance-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GuidancePanelComponent {
  readonly guidance = input.required<Guidance>();
  /** The record's messages, as `guidance.messages` indexes them. */
  readonly messages = input.required<readonly ValidationMessage[]>();
  readonly jump = output<DocTarget>();
  /** Set by the editor, which offers the quick fix; null on the result page, which only names it. */
  readonly quickFixState = input<QuickFixState | null>(null);
  /** The editor's table headers by line prefix (SML, SMF, SME), to highlight cells of quoted data lines. */
  readonly headers = input<ReadonlyMap<string, readonly string[]> | null>(null);
  /** Preview the quick fix for the group's first message, or for all of them. */
  readonly fix = output<{ fixId: string; all: boolean }>();

  private readonly affected = computed(() =>
    (this.guidance().messages ?? [])
      .map((i) => this.messages()[i])
      .filter((m): m is ValidationMessage => !!m),
  );

  protected readonly where = computed<Where[]>(() =>
    (this.guidance().lines ?? []).map((l) => {
      const m = this.affected().find((a) => a.line_number === l.lineNumber);
      const text = l.text ?? '';
      const cells = cellsOf(text, m?.location?.column, this.headers());
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
  /** The quick fix the editor offers as a button, if any. */
  protected readonly editorFix = computed(() => {
    const id = this.guidance().quickFix;
    return this.quickFixState() && id && id !== NOT_A_PREVIEW ? id : null;
  });
  /** How many problems "all" would fix, when the fix can fix them together. */
  protected readonly allCount = computed(() => {
    const id = this.editorFix();
    const n = this.affected().length;
    return id && FIXES_FOR_ALL.has(id) && n > 1 ? n : 0;
  });
  /** "Rule X: required" for CV-rule messages, from the message level. */
  protected readonly rule = computed(() => {
    const m = this.affected().find((a) => !!a.rule_id);
    return m ? { id: m.rule_id!, requirement: REQUIREMENT[m.message_type ?? 'info'] } : null;
  });
}
