import { TourStepDto } from '../../api/models/tour-step-dto';
import { groupOf } from '../result/message-groups';

/** The examples of HUPO-PSI/mzTab-M that the teaching files are adapted from. */
export const ORIGINALS_FOLDER = 'https://github.com/HUPO-PSI/mzTab-M/tree/main/examples';

const PREFIX_OF_TAB: Record<string, { row: string; header: string }> = {
  summary: { row: 'SML', header: 'SMH' },
  features: { row: 'SMF', header: 'SFH' },
  evidence: { row: 'SME', header: 'SEH' },
};

/**
 * The lines of a step's excerpt that its target is about, as 1-based numbers inside `step.lines`: a
 * metadata key's `MTD` line, the `MTD` lines of a metadata group, a data row, or a section's header line.
 * `lines` is the whole file. A step without a target, or one that finds nothing, marks no line.
 */
export function excerptMarks(lines: readonly string[], step: TourStepDto): ReadonlySet<number> {
  const marks = new Set<number>();
  const target = step.target;
  const [from, to] = step.lines ?? [1, lines.length];
  if (!target) return marks;
  const inside = (n: number) => n >= from && n <= to;

  if (target.tab === 'metadata') {
    lines.forEach((line, i) => {
      const [prefix, key] = line.split('\t');
      if (prefix !== 'MTD' || key === undefined || !inside(i + 1)) return;
      if (target.key ? key === target.key : target.group != null && groupOf(key) === target.group) {
        marks.add(i + 1);
      }
    });
    return marks;
  }

  const prefixes = PREFIX_OF_TAB[target.tab ?? ''];
  if (!prefixes) return marks;
  if (target.header) {
    const header = lines.findIndex((line) => line.startsWith(prefixes.header + '\t'));
    if (header >= 0 && inside(header + 1)) marks.add(header + 1);
  } else if (target.rowIndex != null) {
    let row = -1;
    lines.forEach((line, i) => {
      if (line.startsWith(prefixes.row + '\t') && ++row === target.rowIndex && inside(i + 1)) {
        marks.add(i + 1);
      }
    });
  }
  return marks;
}
