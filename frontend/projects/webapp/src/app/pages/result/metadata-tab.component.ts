import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
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
import { catchError, map, of, switchMap } from 'rxjs';

import { MetadataGroup } from '../../api/models/metadata-group';
import { MetadataPage } from '../../api/models/metadata-page';
import { ValidationsService } from '../../api/services/validations.service';
import { quietRequest } from '../../core/http-error.interceptor';

const PAGE_SIZE = 50;

/** What a validation message names in the metadata: a group, optionally with entry keys and one to focus. */
export interface MetadataHighlight {
  group: string;
  keys: readonly string[];
  focusKey: string | null;
}

/** Entries of one metadata group, paged by the server; created (and so loaded) only when its panel opens. */
@Component({
  selector: 'app-metadata-group',
  imports: [MatProgressBarModule],
  template: `
    @if (loading()) {
      <mat-progress-bar mode="indeterminate" class="bar" aria-label="Loading metadata" />
    }
    @if (error()) {
      <p class="lifs-note" role="alert">These entries could not be loaded.</p>
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
  styleUrl: './metadata-tab.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetadataGroupComponent {
  private readonly api = inject(ValidationsService);
  readonly validationId = input.required<string>();
  readonly group = input.required<string>();
  readonly count = input.required<number>();
  readonly highlightKeys = input<readonly string[]>([]);
  readonly focusKey = input<string | null>(null);
  /** The highlight object itself: a new object is a new jump even when its keys are the same. */
  readonly jump = input<MetadataHighlight | null>(null);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  protected readonly highlighted = computed(() => new Set(this.highlightKeys()));
  /** The load being asked for: a key (the server picks the page) or a page; paging by hand drops the key. */
  private readonly request = linkedSignal<{ page: number; key: string | null }>(() => {
    this.jump();
    return { page: 0, key: this.focusKey() };
  });
  protected readonly pageSize = PAGE_SIZE;
  protected readonly page = signal(0);
  protected readonly error = signal(false);
  protected readonly data = signal<MetadataPage | undefined>(undefined);
  protected readonly loading = signal(true);
  protected readonly pages = computed(() => Math.max(1, Math.ceil(this.count() / PAGE_SIZE)));
  protected readonly range = computed(() => {
    const start = this.page() * PAGE_SIZE;
    return `${start + 1}–${Math.min(this.count(), start + PAGE_SIZE)} of ${this.count()}`;
  });

  constructor() {
    toObservable(computed(() => ({ id: this.validationId(), group: this.group(), ...this.request() })))
      .pipe(
        switchMap((q) => {
          this.loading.set(true);
          this.error.set(false);
          const { key, ...rest } = q;
          return this.api.getMetadataPage({ ...rest, ...(key ? { key } : {}), size: PAGE_SIZE }, quietRequest()).pipe(
            map((d) => ({ d, byKey: !!key })),
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
        if (byKey) {
          afterNextRender(
            () => {
              const el = this.host.nativeElement.querySelector<HTMLElement>('.entry[tabindex="-1"]');
              el?.scrollIntoView?.({ block: 'nearest' }); // jsdom has no scrollIntoView
              el?.focus();
            },
            { injector: this.injector },
          );
        }
      });
  }

  protected go(delta: number): void {
    this.request.set({ page: Math.min(Math.max(0, this.page() + delta), this.pages() - 1), key: null });
  }
}

/** One expansion panel per metadata group; the first group starts expanded, and so does the one a highlight names. */
@Component({
  selector: 'app-metadata-tab',
  imports: [MatExpansionModule, MatProgressBarModule, MetadataGroupComponent],
  templateUrl: './metadata-tab.component.html',
  styleUrl: './metadata-tab.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetadataTabComponent {
  private readonly api = inject(ValidationsService);
  readonly validationId = input.required<string>();
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

  protected readonly groups = rxResource<MetadataGroup[], string>({
    params: () => this.validationId(),
    stream: ({ params }) => this.api.listMetadataGroups({ id: params }, quietRequest()),
  });
}
