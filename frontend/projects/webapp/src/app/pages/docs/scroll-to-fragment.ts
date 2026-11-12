import { ViewportScroller } from '@angular/common';
import { afterNextRender, effect, inject, Injector } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

/**
 * A deep link to a section (/docs/problems#id): the router scrolls at NavigationEnd, before content that a page
 * loads afterwards is there, so the section is missing or moves down. This scrolls to the route's fragment again,
 * once, after the render in which `rendered()` first is true. Call it in an injection context (a constructor).
 */
export function scrollToFragmentOnceRendered(rendered: () => boolean): void {
  const fragment = inject(ActivatedRoute).snapshot.fragment;
  const scroller = inject(ViewportScroller);
  const injector = inject(Injector);
  if (!fragment) return;
  let done = false;
  effect(
    () => {
      if (done || !rendered()) return;
      done = true;
      afterNextRender(() => scroller.scrollToAnchor(fragment), { injector });
    },
    { injector },
  );
}
