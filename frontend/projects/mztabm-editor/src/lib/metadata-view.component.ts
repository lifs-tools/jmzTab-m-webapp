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

import { DocMetadataEntry, DocMetadataGroup, DocMetadataPage, DocumentSource, invalidCellText, saveErrorOf } from './document-source';

const PAGE_SIZE = 50;

/** What a validation message names in the metadata: a group, optionally with entry keys and one to focus. */
export interface MetadataHighlight {
  group: string;
  keys: readonly string[];
  focusKey: string | null;
  /** Whether the jump takes the focus; a ‹ › step leaves it on the stepper. */
  moveFocus: boolean;
  /** Whether to open the value of `focusKey` for editing (an editable view only), e.g. after inserting the line. */
  edit?: boolean;
}

const SAVE_ERROR = 'This change could not be saved.';

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
            <dd class="value">
              @if (editable() && editing()?.line === e.lineNumber) {
                @let err = editing()?.error;
                <input
                  class="value-input"
                  type="text"
                  spellcheck="false"
                  [value]="e.value ?? ''"
                  [attr.aria-label]="'Value of ' + e.key"
                  [attr.aria-invalid]="err ? 'true' : null"
                  (keydown)="onInputKey($event, e)"
                />
                @if (err) {
                  <span class="value-error" role="alert">{{ err }}</span>
                }
              } @else if (editable()) {
                <button type="button" class="value-edit" [attr.data-line]="e.lineNumber" (click)="startEdit(e)">
                  <span class="vh">Edit {{ e.key }}: </span>{{ e.value }}
                </button>
              } @else {
                {{ e.value }}
              }
            </dd>
          </div>
        }
      </dl>
      @if (saveError(); as msg) {
        <p class="note" role="alert">{{ msg }}</p>
      }
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
  /** Whether the view only shows the document; set by the metadata view. */
  readonly isReadonly = input(true, { alias: 'readonly' });
  protected readonly editable = computed(() => !this.isReadonly() && !!this.source.applyEdits);
  /** The line whose value is being edited, and why the typed text cannot be saved. */
  protected readonly editing = signal<{ line: number; error: string | null } | null>(null);
  protected readonly saveError = signal<string | null>(null);
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
        this.editing.set(null);
        this.page.set(d.page ?? this.request().page);
        if (byKey) afterNextRender(() => this.reveal(), { injector: this.injector });
      });

    // the document changed elsewhere: load the page shown again
    this.source.changed?.pipe(takeUntilDestroyed()).subscribe(() => this.request.set({ page: this.page(), key: null }));
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
    const jump = this.jump();
    const entry = this.data()?.entries?.find((e) => e.key === this.focusKey());
    if (jump?.edit && this.editable() && entry) this.startEdit(entry);
    else if (jump?.moveFocus) el.focus({ preventScroll: true });
  }

  protected startEdit(e: DocMetadataEntry): void {
    if (!this.editable() || e.lineNumber == null) return;
    this.saveError.set(null);
    this.editing.set({ line: e.lineNumber, error: null });
    afterNextRender(
      () => {
        const input = this.host.nativeElement.querySelector<HTMLInputElement>('input.value-input');
        input?.focus();
        input?.select();
      },
      { injector: this.injector },
    );
  }

  protected onInputKey(event: KeyboardEvent, e: DocMetadataEntry): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      this.save(e, (event.target as HTMLInputElement).value);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.editing.set(null);
      this.focusValue(e);
    }
  }

  /** Saves the value as one SetMetadataValue; the entry shows it at once and keeps it once the source accepts it. */
  private save(e: DocMetadataEntry, value: string): void {
    const editing = this.editing();
    if (!editing || e.lineNumber == null || !this.source.applyEdits) return;
    const error = invalidCellText(value);
    if (error) {
      this.editing.set({ ...editing, error });
      return;
    }
    this.editing.set(null);
    this.focusValue(e);
    if (value === (e.value ?? '')) return;
    const d = this.data();
    if (d) this.data.set({ ...d, entries: (d.entries ?? []).map((x) => (x === e ? { ...x, value } : x)) });
    this.source.applyEdits([{ op: 'SetMetadataValue', line: e.lineNumber, value }]).subscribe({
      error: (err: unknown) => {
        // a conflict: the host reloads the document and says so
        if ((err as { status?: unknown } | null)?.status === 409) return;
        this.saveError.set(saveErrorOf(err, SAVE_ERROR));
        this.request.set({ page: this.page(), key: null });
      },
    });
  }

  private focusValue(e: DocMetadataEntry): void {
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>(`button.value-edit[data-line="${e.lineNumber}"]`)?.focus(),
      { injector: this.injector },
    );
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
    // the document changed elsewhere: the groups and their counts may have changed
    this.source.changed?.pipe(takeUntilDestroyed()).subscribe(() => this.groups.reload());
  }

  protected readonly groups = rxResource<DocMetadataGroup[], null>({
    params: () => null,
    stream: () => this.source.metadataGroups(),
  });
}
