import { inject } from '@angular/core';
import { RedirectFunction, Router } from '@angular/router';

/**
 * Redirects to `target` with the fragment of the old address, so links such as /guide#guide-fixing keep
 * landing on their section (a string redirectTo drops the fragment).
 */
export function keepFragment(target: string): RedirectFunction {
  return ({ fragment }) => inject(Router).parseUrl(target + (fragment ? '#' + fragment : ''));
}

/** The page that now holds each section of the former About page. */
export const ABOUT_SECTION_PAGES: Readonly<Record<string, string>> = {
  'about-formats': '/docs',
  'about-versions': '/docs/versions',
  'about-levels': '/docs/validating',
  'about-profiles': '/docs/validating',
  'about-editor': '/docs/fixing',
  'about-retention': '/docs/about',
  'about-cite': '/docs/about',
  'about-version': '/docs/about',
};

/** /about#<section> goes to the page holding that section; anything else to "About this service". */
export const aboutRedirect: RedirectFunction = ({ fragment }) =>
  inject(Router).parseUrl(
    (Object.hasOwn(ABOUT_SECTION_PAGES, fragment ?? '') ? ABOUT_SECTION_PAGES[fragment!] : '/docs/about') +
      (fragment ? '#' + fragment : ''),
  );
