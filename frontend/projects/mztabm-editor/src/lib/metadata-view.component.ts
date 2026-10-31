import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  linkedSignal,
  signal,
  viewChildren,
} from '@angular/core';
import { rxResource, takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { MatExpansionModule, MatExpansionPanel } from '@angular/material/expansion';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { catchError, map, of, switchMap, take } from 'rxjs';

import { DocMetadataGroup, DocMetadataPage, DocumentSource } from './document-source';

const PAGE_SIZE = 50;

/** What a validation message names in the metadata: a group, optionally with entry keys and one to focus. */
export interface MetadataHighlight {
  group: string;
  keys: readonly string[];
  focusKey: string | null;
  /** Whether the jump takes the focus; a ‹ › step leaves it on the stepper. */
  moveFocus: boolean;
}

/** Entries of one metadata group, paged by the source; created (and so loaded) only when its panel opens. */
@Component({
  selector: 'mztabm-metadata-group',
  imports: [MatProgressBarModule],
  template: `
    @if (loading()) {
      <mat-progress-bar mode="indeterminate" class="bar" aria-label="Loading metadata" />
    }
    @if (error()) {
      <p class="note" role="alert">These entries could not be loaded.</p>
    }
    @if (data(); as d) {
      <dl class="entries">
        @for (e of d.entries; track e.lineNumber) {
          <div
            class="entry"
            [class.entry--hl]="highlighted().has(e.key ?? '')"
            [attr.tabindex]="focusKey() && e.key === focusKey() ? -1 : null"
          >
            <dt class="key">{{ e.key }}</dt>
            <dd class="value">{{ e.value }}</dd>
          </div>
        }
      </dl>
      @if (count() > pageSize) {
        <div class="pager">
          <span aria-live="polite">{{ range() }}</span>
          <button type="button" aria-label="Previous page" [disabled]="page() === 0" (click)="go(-1)">‹</button>
          <button type="button" aria-label="Next page" [disabled]="page() >= pages() - 1" (click)="go(1)">›</button>
        </div>
      }
    }
  `,
  styleUrl: './metadata-view.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetadataGroupComponent {
  private readonly source = inject(DocumentSource);
  readonly group = input.required<string>();
  readonly count = input.required<number>();
  readonly highlightKeys = input<readonly string[]>([]);
  readonly focusKey = input<string | null>(null);
  /** The highlight object itself: a new object is a new jump even when its keys are the same. */
  readonly jump = input<MetadataHighlight | null>(null);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private readonly panel = inject(MatExpansionPanel, { optional: true });
  protected readonly highlighted = computed(() => new Set(this.highlightKeys()));
  /** The load being asked for: a key (the server picks the page) or a page; paging by hand drops the key. */
  private readonly request = linkedSignal<{ page: number; key: string | null }>(() => {
    this.jump();
    return { page: 0, key: this.focusKey() };
  });
  protected readonly pageSize = PAGE_SIZE;
  protected readonly page = signal(0);
  protected readonly error = signal(false);
  protected readonly data = signal<DocMetadataPage | undefined>(undefined);
  protected readonly loading = signal(true);
  protected readonly pages = computed(() => Math.max(1, Math.ceil(this.count() / PAGE_SIZE)));
  protected readonly range = computed(() => {
    const start = this.page() * PAGE_SIZE;
    return `${start + 1}–${Math.min(this.count(), start + PAGE_SIZE)} of ${this.count()}`;
  });

  constructor() {
    toObservable(computed(() => ({ group: this.group(), ...this.request() })))
      .pipe(
        switchMap((q) => {
          this.loading.set(true);
          this.error.set(false);
          return this.source.metadataPage(q.group, q.page, PAGE_SIZE, q.key ?? undefined).pipe(
            map((d) => ({ d, byKey: !!q.key })),
            catchError(() => {
              this.error.set(true);
              return of({ d: undefined, byKey: false });
            }),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe(({ d, byKey }) => {
        this.loading.set(false);
        if (!d) return;
        this.data.set(d);
        this.page.set(d.page ?? this.request().page);
        if (byKey) afterNextRender(() => this.reveal(), { injector: this.injector });
      });
  }

  /**
   * Scrolls to the entry to focus and focuses it. While its panel is still expanding the entry is hidden, and the
   * browser ignores focus() on hidden elements, so this waits for the panel to finish expanding.
   */
  private reveal(): void {
    const el = this.host.nativeElement.querySelector<HTMLElement>('.entry[tabindex="-1"]');
    if (!el) return;
    if (this.panel && getComputedStyle(el).visibility === 'hidden') {
      this.panel.afterExpand.pipe(take(1), takeUntilDestroyed(this.destroyRef)).subscribe(() => this.reveal());
      return;
    }
    el.scrollIntoView?.({ block: 'nearest' }); // jsdom has no scrollIntoView
    if (this.jump()?.moveFocus) el.focus({ preventScroll: true });
  }

  protected go(delta: number): void {
    this.request.set({ page: Math.min(Math.max(0, this.page() + delta), this.pages() - 1), key: null });
  }
}

/** One expansion panel per metadata group; the first group starts expanded, and so does the one a highlight names. */
@Component({
  selector: 'mztabm-metadata-view',
  imports: [MatExpansionModule, MatProgressBarModule, MetadataGroupComponent],
  templateUrl: './metadata-view.component.html',
  styleUrl: './metadata-view.component.scss',
  host: { '[attr.data-readonly]': 'isReadonly()' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetadataViewComponent {
  private readonly source = inject(DocumentSource);
  /** Whether the view only shows the document; an editable host sets it to false. */
  readonly isReadonly = input(true, { alias: 'readonly' });
  readonly highlight = input<MetadataHighlight | null>(null);
  private readonly panels = viewChildren(MatExpansionPanel);

  constructor() {
    // A highlight is an event: every new object opens its panel again, also after the user collapsed it.
    effect(() => {
      const h = this.highlight();
      const panels = this.panels();
      const i = (this.groups.value() ?? []).findIndex((g) => g.name === h?.group);
      if (h && i >= 0) panels[i]?.open();
    });
  }

  protected readonly groups = rxResource<DocMetadataGroup[], null>({
    params: () => null,
    stream: () => this.source.metadataGroups(),
  });
}
