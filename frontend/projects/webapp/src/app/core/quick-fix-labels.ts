/** The labels of the quick-fix buttons; the guide and the editor help name them from here, so they cannot drift. */
export const QUICK_FIX_PREVIEW_LABEL = 'Preview the quick fix';

/** The label of the button that fixes every occurrence of a group. */
export function quickFixAllLabel(count: number | string): string {
  return `Fix all ${count} like this`;
}
