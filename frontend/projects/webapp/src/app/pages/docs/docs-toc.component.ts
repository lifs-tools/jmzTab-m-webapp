import { BreakpointObserver } from '@angular/cdk/layout';
import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  DOCUMENT,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { map } from 'rxjs';

import { routerPath } from '../../core/router-path';
import { activeSection } from './active-section';

const WIDE_QUERY = '(min-width: 1100px)';
/** Px from the top of the viewport at which a heading's section counts as the one in view. */
const IN_VIEW_THRESHOLD = 100;

interface TocEntry {
  readonly id: string;
  readonly text: string;
  readonly level: 2 | 3;
  readonly heading: HTMLElement;
}

const sameEntries = (a: readonly TocEntry[], b: readonly TocEntry[]) =>
  a.length === b.length &&
  a.every((e, i) => e.heading === b[i].heading && e.id === b[i].id && e.text === b[i].text);

/**
 * "On this page": the `h2`/`h3` sections with an id in `container`, the one in view marked. Beside the text
 * from 1100 px, a collapsible list below that. Follows the container's content as the routed page renders.
 */
@Component({
  selector: 'app-docs-toc',
  imports: [RouterLink, NgTemplateOutlet],
  templateUrl: './docs-toc.component.html',
  styleUrl: './docs-toc.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocsTocComponent {
  readonly container = input.required<HTMLElement>();

  protected readonly wide = toSignal(
    inject(BreakpointObserver)
      .observe(WIDE_QUERY)
      .pipe(map((s) => s.matches)),
    { initialValue: true },
  );
  /** Links keep the route: a bare #id would resolve against <base href> to the start page. */
  protected readonly path = routerPath();
  protected readonly entries = signal<readonly TocEntry[]>([], { equal: sameEntries });
  protected readonly active = signal<string | null>(null);

  constructor() {
    effect((onCleanup) => {
      const container = this.container();
      const scan = () => {
        this.entries.set(
          Array.from(container.querySelectorAll<HTMLElement>('h2[id], h3[id]')).map((heading) => ({
            id: heading.id,
            text: heading.textContent?.trim() ?? '',
            level: heading.tagName === 'H3' ? 3 : 2,
            heading,
          })),
        );
        this.markActive();
      };
      untracked(scan);
      const observer = new MutationObserver(scan);
      observer.observe(container, {
        childList: true,
        subtree: true,
        characterData: true,
        attributeFilter: ['id'],
      });
      onCleanup(() => observer.disconnect());
    });

    // One measurement per frame, however many scroll events arrive.
    const win = inject(DOCUMENT).defaultView;
    let frame = 0;
    const onScroll = () => {
      frame ||= win!.requestAnimationFrame(() => {
        frame = 0;
        this.markActive();
      });
    };
    win?.addEventListener('scroll', onScroll, { passive: true });
    inject(DestroyRef).onDestroy(() => {
      win?.removeEventListener('scroll', onScroll);
      if (frame) win?.cancelAnimationFrame(frame);
    });
  }

  private markActive(): void {
    const tops = this.entries().map((e) => ({
      id: e.id,
      top: e.heading.getBoundingClientRect().top,
    }));
    this.active.set(activeSection(tops, IN_VIEW_THRESHOLD));
  }
}
