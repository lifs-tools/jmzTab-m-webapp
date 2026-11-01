/** The labels of the quick-fix buttons; the guide and the editor help name them from here, so they cannot drift. */
export const QUICK_FIX_PREVIEW_LABEL = 'Preview the quick fix';

/** The label of the button that fixes every occurrence of a group. */
export function quickFixAllLabel(count: number | string): string {
  return `Fix all ${count} like this`;
}

/**
 * The titles of quick fixes that the documentation names, by fix id; the preview dialog opens with the title the
 * server sends (`QuickFix.title()`), and QuickFixesTest checks that these are the same.
 */
export const QUICK_FIX_TITLES = {
  'remove-mismatched-field': 'Remove the field or column that the profile has no section for',
} as const;
