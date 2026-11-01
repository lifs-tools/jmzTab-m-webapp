import { inject } from '@angular/core';
import { RedirectFunction, Router } from '@angular/router';

/**
 * Redirects to `target` with the fragment of the old address, so links such as /guide#guide-fixing keep
 * landing on their section (a string redirectTo drops the fragment).
 */
export function keepFragment(target: string): RedirectFunction {
  return ({ fragment }) => inject(Router).parseUrl(target + (fragment ? '#' + fragment : ''));
}
