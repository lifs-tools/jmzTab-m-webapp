import { TestBed } from '@angular/core/testing';

import { ValidationMessage } from '../../api/models/validation-message';
import { MessagesTabComponent } from './messages-tab.component';

function msg(i: number, over: Partial<ValidationMessage> = {}): ValidationMessage {
  return {
    category: 'format',
    code: `C${i}`,
    message: `Message number ${i}`,
    message_type: 'info',
    line_number: i,
    ...over,
  };
}

describe('MessagesTabComponent', () => {
  function render(
    messages: ValidationMessage[],
    flat = true,
    inputs: Record<string, unknown> = {},
  ) {
    const fixture = TestBed.createComponent(MessagesTabComponent);
    fixture.componentRef.setInput('messages', messages);
    for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    if (flat) {
      el.querySelector<HTMLButtonElement>('.view-toggle')!.click();
      fixture.detectChanges();
    }
    const rows = () => Array.from(el.querySelectorAll('tbody tr'));
    return { fixture, el, rows };
  }

  function type(el: HTMLElement, selector: string, value: string, event = 'input') {
    const input = el.querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event(event));
  }

  it('filters messages by level and text', async () => {
    const { fixture, el, rows } = render([
      msg(1, { message_type: 'error', message: 'Column abundance is missing' }),
      msg(2, { message_type: 'warn', message: 'CV term not allowed' }),
      msg(3, {
        message_type: 'info',
        message: 'Optional abundance element absent',
        category: 'logical',
      }),
    ]);
    expect(rows().length).toBe(3);
    type(el, '.f-text', 'abundance');
    fixture.detectChanges();
    expect(rows().length).toBe(2);
    type(el, '.f-level', 'ERROR', 'change');
    fixture.detectChanges();
    expect(rows().length).toBe(1);
    expect(rows()[0].textContent).toContain('Column abundance is missing');
    type(el, '.f-level', 'ALL', 'change');
    type(el, '.f-category', 'logical', 'change');
    fixture.detectChanges();
    expect(rows().length).toBe(1);
    expect(rows()[0].textContent).toContain('Optional abundance');
    expect(el.querySelector('.lifs-lvl--info')).toBeTruthy();
  });

  it('shows a dash for unknown lines and pages at 25', () => {
    const many = Array.from({ length: 55 }, (_, i) =>
      msg(i + 1, { line_number: i === 0 ? -1 : i === 1 ? null : i }),
    );
    const { fixture, el, rows } = render(many);
    expect(rows().length).toBe(25);
    expect(rows()[0].querySelectorAll('td')[1].textContent?.trim()).toBe('—');
    expect(rows()[1].querySelectorAll('td')[1].textContent?.trim()).toBe('—');
    expect(el.querySelector('.pager')?.textContent).toContain('1–25 of 55');
    el.querySelector<HTMLButtonElement>('button[aria-label="Next page"]')!.click();
    fixture.detectChanges();
    expect(el.querySelector('.pager')?.textContent).toContain('26–50 of 55');
  });

  it('shows an empty note when nothing matches', () => {
    const { el } = render([], false);
    expect(el.textContent).toContain('No messages');
  });

  const ERR_FIX = 'Add the missing column abundance_assay[3].';
  const INFO_FIX = 'Consider adding a species term.';
  const grouped = () => [
    msg(1, {
      message_type: 'info',
      code: '[Info-3010] a',
      rule_id: 'sample_species_may',
      suggestion: INFO_FIX,
      message: 'species missing 1',
      location: { section: 'mtd', metadata_key: 'sample[1]-species[1]' },
    }),
    msg(2, {
      message_type: 'error',
      code: '[Error-2009] x',
      suggestion: ERR_FIX,
      message: 'column missing',
      location: { section: 'smh', column: 'abundance_assay[3]' },
    }),
    msg(3, {
      message_type: 'info',
      code: '[Info-3010] b',
      rule_id: 'sample_species_may',
      suggestion: INFO_FIX,
      message: 'species missing 2',
      location: { section: 'mtd', metadata_key: 'sample[2]-species[1]' },
    }),
  ];
  const texts = (el: HTMLElement, sel: string) =>
    Array.from(el.querySelectorAll(sel)).map((e) => e.textContent!.trim());
  const click = (el: HTMLElement, sel: string) => el.querySelector<HTMLButtonElement>(sel)!.click();

  it('shows groups with headline, count and chips by default', () => {
    const { el } = render(grouped(), false, { profileLabel: 'lipidcompass-summary-2.0 v3' });
    expect(texts(el, '.grp .fix')).toEqual([ERR_FIX, INFO_FIX]);
    expect(el.querySelector('.grp:nth-of-type(1) .count')).toBeNull();
    expect(texts(el, '.grp:nth-of-type(2) .count')).toEqual(['2 occurrences']);
    expect(texts(el, '.grp:nth-of-type(2) .chip')).toEqual([
      'sample[1]-species[1]',
      'sample[2]-species[1]',
    ]);
    expect(texts(el, '.grp:nth-of-type(1) .why')).toEqual(['column missing']);
    expect(el.querySelector('.grp:nth-of-type(1) .why')!.getAttribute('title')).toBe(
      'column missing',
    );
    const meta = texts(el, '.grp:nth-of-type(2) .meta')[0];
    expect(meta).toContain('code 3010');
    expect(meta).toContain('rule sample_species_may');
    expect(meta).toContain('lipidcompass-summary-2.0 v3');
    expect(texts(el, '.grp:nth-of-type(1) .meta')[0]).not.toContain('rule');
  });

  it('emits show for a chip and for highlight all', () => {
    const { fixture, el } = render(grouped(), false);
    const shown: unknown[] = [];
    fixture.componentInstance.show.subscribe((r) => shown.push(r));
    click(el, '.grp:nth-of-type(2) .chip:nth-of-type(2)');
    expect(shown[0]).toEqual({ group: expect.anything(), index: 1, all: false });
    click(el, '.grp:nth-of-type(2) .act-first');
    expect(shown[1]).toEqual({ group: expect.anything(), index: 0, all: false });
    click(el, '.grp:nth-of-type(2) .act-all');
    expect((shown[2] as { all: boolean }).all).toBe(true);
    expect(el.querySelector('.grp:nth-of-type(1) .act-all')).toBeNull();
  });

  it('collapses chips after 8', () => {
    const many = Array.from({ length: 11 }, (_, i) =>
      msg(i, {
        code: '[Warn-1] x',
        message_type: 'warn',
        location: { section: 'sml', row_index: i },
      }),
    );
    const { fixture, el } = render(many, false);
    expect(el.querySelectorAll('.chip').length).toBe(8);
    expect(el.querySelector('.more')!.textContent!.trim()).toBe('+3 more');
    click(el, '.more');
    fixture.detectChanges();
    expect(el.querySelectorAll('.chip').length).toBe(11);
    expect(el.querySelector('.more')).toBeNull();
  });

  it('expands chips 100 at a time and focuses the first new one', async () => {
    const many = Array.from({ length: 250 }, (_, i) =>
      msg(i, {
        code: '[Warn-1] x',
        message_type: 'warn',
        location: { section: 'sml', row_index: i },
      }),
    );
    const { fixture, el } = render(many, false);
    const chips = () => el.querySelectorAll<HTMLElement>('.chip');
    const more = async (shown: number, focused: number) => {
      click(el, '.more');
      fixture.detectChanges();
      await fixture.whenStable();
      expect(chips().length).toBe(shown);
      expect(document.activeElement).toBe(chips()[focused]);
    };
    expect(el.querySelector('.more')!.textContent!.trim()).toBe('+242 more');
    await more(108, 8);
    expect(el.querySelector('.more')!.textContent!.trim()).toBe('+142 more');
    await more(208, 108);
    await more(250, 208);
    expect(el.querySelector('.more')).toBeNull();
  });

  it('describes the action buttons by their card headline', () => {
    const { el } = render(grouped(), false);
    for (const card of Array.from(el.querySelectorAll('.grp'))) {
      const fix = card.querySelector('.fix')!;
      expect(fix.id).toBeTruthy();
      for (const b of Array.from(card.querySelectorAll('.act button'))) {
        expect(b.getAttribute('aria-describedby')).toBe(fix.id);
      }
    }
    const ids = Array.from(el.querySelectorAll('.fix')).map((f) => f.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(el.querySelectorAll('.act button[aria-describedby]').length).toBe(3);
  });

  it('renders old records without chips or actions', () => {
    const { el } = render(
      [msg(1, { code: '[Error-2009] old\r\n', message_type: 'error', message: 'Old message' })],
      false,
    );
    expect(texts(el, '.grp .fix')).toEqual(['Old message']);
    expect(el.querySelector('.grp .why')).toBeNull();
    expect(el.querySelector('.chip')).toBeNull();
    expect(el.querySelector('.act-first')).toBeNull();
    expect(el.querySelector('.grp button')).toBeNull();
  });

  it('filters apply inside groups', () => {
    const { fixture, el } = render(grouped(), false);
    type(el, '.f-text', 'missing 2');
    fixture.detectChanges();
    expect(el.querySelectorAll('.grp').length).toBe(1);
    expect(el.querySelector('.count')).toBeNull();
    expect(texts(el, '.chip')).toEqual(['sample[2]-species[1]']);
  });

  it('the text filter searches the fix and the occurrence label', () => {
    const { fixture, el } = render(grouped(), false);
    type(el, '.f-text', 'consider adding');
    fixture.detectChanges();
    expect(texts(el, '.grp .fix')).toEqual([INFO_FIX]);
    expect(texts(el, '.chip')).toEqual(['sample[1]-species[1]', 'sample[2]-species[1]']);
    type(el, '.f-text', 'sample[2]-species');
    fixture.detectChanges();
    expect(texts(el, '.grp .fix')).toEqual([INFO_FIX]);
    expect(texts(el, '.chip')).toEqual(['sample[2]-species[1]']);
  });

  it('flat list has a Location column', () => {
    const { el } = render(grouped());
    expect(texts(el, 'th')).toContain('Location');
    expect(texts(el, 'tbody tr')[0]).toContain('sample[1]-species[1]');
  });

  it('toggles back to groups', () => {
    const { fixture, el } = render(grouped());
    expect(el.querySelector('.view-toggle')!.textContent).toContain('Group by rule');
    click(el, '.view-toggle');
    fixture.detectChanges();
    expect(el.querySelectorAll('.grp').length).toBe(2);
    expect(el.querySelector('.view-toggle')!.textContent).toContain('Show as flat list');
  });

  it('marks the selected group', () => {
    const key = 'error|Error-2009|';
    const { el } = render(grouped(), false, { selected: key });
    const sel = el.querySelectorAll('.grp--sel');
    expect(sel.length).toBe(1);
    expect(sel[0].getAttribute('aria-current')).toBe('true');
    expect(sel[0].querySelector('.fix')!.textContent).toContain(ERR_FIX);
  });
});
