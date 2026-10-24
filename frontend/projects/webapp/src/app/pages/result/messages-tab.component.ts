import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { ValidationMessage } from '../../api/models/validation-message';
import { MessageGroup, groupMessages, occurrenceLabel, targetOf } from './message-groups';

const PAGE_SIZE = 25;
const CHIP_LIMIT = 8;
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

/** Filterable, paged validation messages: grouped by rule with the fix first, or as a flat table. */
@Component({
  selector: 'app-messages-tab',
  templateUrl: './messages-tab.component.html',
  styleUrl: './messages-tab.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessagesTabComponent {
  readonly messages = input.required<readonly ValidationMessage[]>();
  /** E.g. "lipidcompass-summary-2.0 v3"; shown in the meta line of rule-based groups. */
  readonly profileLabel = input<string | null>(null);
  /** `MessageGroup.key` of the group currently shown in the document. */
  readonly selected = input<string | null>(null);
  readonly show = output<ShowRequest>();

  protected readonly flat = signal(false);
  private readonly expanded = signal<ReadonlySet<string>>(new Set());

  protected readonly text = signal('');
  protected readonly level = signal('ALL');
  protected readonly category = signal('ALL');
  private readonly page = signal(0);

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
        const open = expanded.has(group.key);
        return {
          group,
          cls: LEVEL_CLASS[group.level],
          level: group.level.toUpperCase(),
          count: group.messages.length,
          code: code ? `code ${code}` : null,
          category: CATEGORY_LABEL[group.category] ?? group.category,
          chips: open ? chips : chips.slice(0, CHIP_LIMIT),
          more: open ? 0 : Math.max(0, chips.length - CHIP_LIMIT),
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
  protected showMore(key: string): void {
    this.expanded.update((set) => new Set(set).add(key));
  }
  protected emit(group: MessageGroup, index: number, all: boolean): void {
    this.show.emit({ group, index, all });
  }
  protected go(delta: number): void {
    this.page.set(Math.min(Math.max(0, this.pageIndex() + delta), this.pageCount() - 1));
  }
}
