import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injectable,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { ValidationMessage } from '../../api/models/validation-message';
import { MessageGroup, groupMessages, occurrenceLabel, targetOf } from './message-groups';

const PAGE_SIZE = 25;
const CHIP_LIMIT = 8;
/** "+N more" shows this many further chips; a group can hold thousands of occurrences. */
const CHIP_STEP = 100;
const LEVEL_CLASS = { error: 'error', warn: 'warn', info: 'info' } as const;
const CATEGORY_LABEL: Record<ValidationMessage['category'], string> = {
  format: 'Format',
  logical: 'Logical',
  cross_check: 'Cross-check',
};

export interface ShowRequest {
  group: MessageGroup;
  /** Index into `group.messages` of the clicked occurrence. */
  index: number;
  all: boolean;
}

/**
 * The messages tab's view state. The result page provides it, so filters, page, view and expanded chips
 * survive the tab being re-created by a jump (narrow screens) or a layout switch.
 */
@Injectable()
export class MessagesViewState {
  readonly text = signal('');
  readonly level = signal('ALL');
  readonly category = signal('ALL');
  readonly page = signal(0);
  readonly flat = signal(false);
  /** Number of chips shown per group key, for groups expanded beyond the first `CHIP_LIMIT`. */
  readonly expanded = signal<ReadonlyMap<string, number>>(new Map());
  /** The key of a group to bring into view and focus ("Back to message"). */
  readonly reveal = signal<string | null>(null);

  reset(): void {
    this.text.set('');
    this.level.set('ALL');
    this.category.set('ALL');
    this.page.set(0);
    this.flat.set(false);
    this.expanded.set(new Map());
    this.reveal.set(null);
  }
}

/** Filterable, paged validation messages: grouped by rule with the fix first, or as a flat table. */
@Component({
  selector: 'app-messages-tab',
  templateUrl: './messages-tab.component.html',
  styleUrl: './messages-tab.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessagesTabComponent {
  private static nextId = 0;
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  /** The page's state when it provides one; otherwise this tab keeps its own. */
  private readonly state = inject(MessagesViewState, { optional: true }) ?? new MessagesViewState();
  /** Prefix of the headline ids the action buttons are described by. */
  protected readonly uid = `msgs-${MessagesTabComponent.nextId++}`;
  readonly messages = input.required<readonly ValidationMessage[]>();
  /** E.g. "lipidcompass-summary-2.0 v3"; shown in the meta line of rule-based groups. */
  readonly profileLabel = input<string | null>(null);
  /** `MessageGroup.key` of the group currently shown in the document. */
  readonly selected = input<string | null>(null);
  readonly show = output<ShowRequest>();

  protected readonly flat = this.state.flat;
  private readonly expanded = this.state.expanded;
  protected readonly text = this.state.text;
  protected readonly level = this.state.level;
  protected readonly category = this.state.category;
  private readonly page = this.state.page;

  protected readonly categories = Object.entries(CATEGORY_LABEL).map(([value, label]) => ({
    value,
    label,
  }));

  private readonly filtered = computed(() => {
    const needle = this.text().trim().toLowerCase();
    const level = this.level();
    const category = this.category();
    return this.messages().filter(
      (m) =>
        (level === 'ALL' || this.levelOf(m) === level.toLowerCase()) &&
        (category === 'ALL' || m.category === category) &&
        (!needle ||
          `${m.message} ${m.code} ${m.suggestion ?? ''} ${occurrenceLabel(m)}`
            .toLowerCase()
            .includes(needle)),
    );
  });
  private readonly groups = computed(() => groupMessages(this.filtered()));
  protected readonly total = computed(() =>
    this.flat() ? this.filtered().length : this.groups().length,
  );
  protected readonly pageCount = computed(() => Math.max(1, Math.ceil(this.total() / PAGE_SIZE)));
  /** The page index clamped to the filtered result, so changing a filter never strands the user. */
  protected readonly pageIndex = computed(() => Math.min(this.page(), this.pageCount() - 1));
  protected readonly rows = computed(() => {
    const start = this.pageIndex() * PAGE_SIZE;
    return this.filtered()
      .slice(start, start + PAGE_SIZE)
      .map((m) => ({
        cls: LEVEL_CLASS[this.levelOf(m)],
        level: this.levelOf(m).toUpperCase(),
        line: m.line_number != null && m.line_number >= 0 ? String(m.line_number) : '—',
        category: CATEGORY_LABEL[m.category] ?? m.category,
        location: occurrenceLabel(m),
        message: m.message,
      }));
  });
  protected readonly cards = computed(() => {
    const start = this.pageIndex() * PAGE_SIZE;
    const expanded = this.expanded();
    return this.groups()
      .slice(start, start + PAGE_SIZE)
      .map((group) => {
        const chips = group.messages
          .map((m, index) => ({
            index,
            label: occurrenceLabel(m),
            hasTarget: targetOf(m) !== null,
          }))
          .filter((c) => c.hasTarget);
        const code = group.typeCode?.match(/(\d+)\s*$/)?.[1];
        const shown = expanded.get(group.key) ?? CHIP_LIMIT;
        return {
          group,
          cls: LEVEL_CLASS[group.level],
          level: group.level.toUpperCase(),
          count: group.messages.length,
          code: code ? `code ${code}` : null,
          category: CATEGORY_LABEL[group.category] ?? group.category,
          chips: chips.slice(0, shown),
          more: Math.max(0, chips.length - shown),
          targets: chips.length,
          first: chips[0]?.index ?? 0,
        };
      });
  });
  protected readonly range = computed(() => {
    const total = this.total();
    if (!total) return '0 of 0';
    const start = this.pageIndex() * PAGE_SIZE;
    return `${start + 1}–${Math.min(total, start + PAGE_SIZE)} of ${total}`;
  });

  constructor() {
    effect(() => {
      const key = this.state.reveal();
      if (key !== null) untracked(() => this.revealGroup(key));
    });
  }

  /**
   * Opens the page that holds the group, in the grouped view, and focuses its first button. Filters
   * stay unless they hide the group: then the text filter is cleared, and if that is not enough, the
   * level and category too.
   */
  private revealGroup(key: string): void {
    this.state.reveal.set(null);
    this.flat.set(false);
    const position = () => this.groups().findIndex((g) => g.key === key);
    if (position() < 0) this.text.set('');
    if (position() < 0) {
      this.level.set('ALL');
      this.category.set('ALL');
    }
    const i = position();
    if (i < 0) return;
    this.page.set(Math.floor(i / PAGE_SIZE));
    afterNextRender(
      () =>
        this.host.nativeElement
          .querySelector<HTMLElement>('.grp[aria-current="true"] button')
          ?.focus(),
      { injector: this.injector },
    );
  }

  private levelOf(m: ValidationMessage): 'error' | 'warn' | 'info' {
    return m.message_type ?? 'info';
  }

  protected onText(event: Event): void {
    this.text.set((event.target as HTMLInputElement).value);
    this.page.set(0);
  }
  protected onLevel(event: Event): void {
    this.level.set((event.target as HTMLSelectElement).value);
    this.page.set(0);
  }
  protected onCategory(event: Event): void {
    this.category.set((event.target as HTMLSelectElement).value);
    this.page.set(0);
  }
  protected toggleView(): void {
    this.flat.update((f) => !f);
    this.page.set(0);
  }
  /** Shows the next `CHIP_STEP` chips and moves the focus to the first of them. */
  protected showMore(key: string, shown: number, occ: HTMLElement): void {
    this.expanded.update((map) => new Map(map).set(key, shown + CHIP_STEP));
    afterNextRender(() => occ.querySelectorAll<HTMLElement>('.chip')[shown]?.focus(), {
      injector: this.injector,
    });
  }
  protected emit(group: MessageGroup, index: number, all: boolean): void {
    this.show.emit({ group, index, all });
  }
  protected go(delta: number): void {
    this.page.set(Math.min(Math.max(0, this.pageIndex() + delta), this.pageCount() - 1));
  }
}
