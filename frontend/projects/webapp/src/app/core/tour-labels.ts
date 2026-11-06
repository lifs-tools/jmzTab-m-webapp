/** The labels of the tour's buttons; the documentation names them from here, so they cannot drift. */
export const TAKE_THE_TOUR = 'Take the tour';
/** The tour from a chapter page of Understanding mzTab-M, at the chapter's first step. */
export const TRY_IT = 'Try it';
export const TOUR_BACK = 'Back';
export const TOUR_NEXT = 'Next';
export const TOUR_CLOSE = 'Close';
/** Takes the place of Next on the last step; it leads to the validator form. */
export const VALIDATE_YOUR_OWN_FILE = 'Validate your own file';

/** The label of the switch to the same step of the tour on the other mzTab-M version, e.g. `2.1`. */
export function tourSwitchLabel(version: string): string {
  return `Show this in mzTab-M ${version}`;
}
