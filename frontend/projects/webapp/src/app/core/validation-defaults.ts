/**
 * Validation profile used when none is chosen for a file of an mzTab-M version line (landing form
 * and example actions), e.g. `2.1` -> `mztab-m-2.1-default`.
 */
export function defaultProfileFor(line: string): string {
  return `mztab-m-${line}-default`;
}
