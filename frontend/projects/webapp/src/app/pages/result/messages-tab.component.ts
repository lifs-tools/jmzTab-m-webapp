import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { ValidationMessage } from '../../api/models/validation-message';

const PAGE_SIZE = 25;
const LEVEL_CLASS = { error: 'error', warn: 'warn', info: 'info' } as const;
const CATEGORY_LABEL: Record<ValidationMessage['category'], string> = {
  format: 'Format',
  logical: 'Logical',
  cross_check: 'Cross-check',
};

/** Filterable, paged table of validation messages. */
@Component({
  selector: 'app-messages-tab',
  templateUrl: './messages-tab.component.html',
  styleUrl: './messages-tab.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessagesTabComponent {
  readonly messages = input.required<readonly ValidationMessage[]>();

  protected readonly text = signal('');
  protected readonly level = signal('ALL');
  protected readonly category = signal('ALL');
  private readonly page = signal(0);

  protected readonly categories = Object.entries(CATEGORY_LABEL).map(([value, label]) => ({ value, label }));

  private readonly filtered = computed(() => {
    const needle = this.text().trim().toLowerCase();
    const level = this.level();
    const category = this.category();
    return this.messages().filter(
      (m) =>
        (level === 'ALL' || this.levelOf(m) === level.toLowerCase()) &&
        (category === 'ALL' || m.category === category) &&
        (!needle || `${m.message} ${m.code}`.toLowerCase().includes(needle)),
    );
  });
  protected readonly pageCount = computed(() => Math.max(1, Math.ceil(this.filtered().length / PAGE_SIZE)));
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
        message: m.message,
      }));
  });
  protected readonly range = computed(() => {
    const total = this.filtered().length;
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
  protected go(delta: number): void {
    this.page.set(Math.min(Math.max(0, this.pageIndex() + delta), this.pageCount() - 1));
  }
}
