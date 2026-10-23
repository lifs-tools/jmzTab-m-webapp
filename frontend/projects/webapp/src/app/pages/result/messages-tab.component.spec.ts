import { TestBed } from '@angular/core/testing';

import { ValidationMessage } from '../../api/models/validation-message';
import { MessagesTabComponent } from './messages-tab.component';

function msg(i: number, over: Partial<ValidationMessage> = {}): ValidationMessage {
  return { category: 'format', code: `C${i}`, message: `Message number ${i}`, message_type: 'info', line_number: i, ...over };
}

describe('MessagesTabComponent', () => {
  function render(messages: ValidationMessage[]) {
    const fixture = TestBed.createComponent(MessagesTabComponent);
    fixture.componentRef.setInput('messages', messages);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
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
      msg(3, { message_type: 'info', message: 'Optional abundance element absent', category: 'logical' }),
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
    const many = Array.from({ length: 55 }, (_, i) => msg(i + 1, { line_number: i === 0 ? -1 : i === 1 ? null : i }));
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
    const { el } = render([]);
    expect(el.textContent).toContain('No messages');
  });
});
