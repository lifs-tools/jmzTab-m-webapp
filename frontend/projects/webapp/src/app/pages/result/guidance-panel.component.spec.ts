import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { Guidance } from '../../api/models/guidance';
import { ValidationMessage } from '../../api/models/validation-message';
import { DocTarget } from './message-groups';
import { GuidancePanelComponent, QuickFixState } from './guidance-panel.component';

const MESSAGES: ValidationMessage[] = [
  {
    category: 'format',
    code: '[Error-2009] x',
    message: 'm0',
    message_type: 'error',
    line_number: 12,
    location: { section: 'smf', column: 'abundance_assay[1]', row_index: 0 },
  },
  { category: 'format', code: '[Error-2009] x', message: 'm1', message_type: 'error', line_number: 13 },
  { category: 'format', code: '[Error-2009] x', message: 'm2', message_type: 'error', line_number: 14 },
];

const GUIDANCE: Guidance = {
  entryId: 'e1',
  typeCode: 'Error-2009',
  title: 'A column is missing',
  explanation: 'The table lacks a required column.',
  cause: 'Your tool left it out.',
  fixHere: 'Add the column in the editor.',
  fixInTool: 'Enable the abundance export.',
  toolFixInTool: null,
  quickFix: null,
  stopsParsing: false,
  messages: [0, 1, 2],
  lines: [{ lineNumber: 12, text: 'SMF\tid\tabundance_assay[1]\tother', truncated: false }],
};

@Component({
  imports: [GuidancePanelComponent],
  template: `<app-guidance-panel
    [guidance]="guidance()"
    [messages]="messages()"
    [quickFixState]="quickFixState()"
    [headers]="headers()"
    (jump)="jumped = $event"
    (fix)="fixed = $event"
  />`,
})
class HostComponent {
  readonly guidance = signal<Guidance>(GUIDANCE);
  readonly messages = signal<readonly ValidationMessage[]>(MESSAGES);
  readonly quickFixState = signal<QuickFixState | null>(null);
  readonly headers = signal<ReadonlyMap<string, readonly string[]> | null>(null);
  jumped: DocTarget | null = null;
  fixed: { fixId: string; all: boolean } | null = null;
}

describe('GuidancePanelComponent', () => {
  function render(over: Partial<Guidance> = {}, messages: readonly ValidationMessage[] = MESSAGES) {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.guidance.set({ ...GUIDANCE, ...over });
    fixture.componentInstance.messages.set(messages);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el, text: () => el.textContent!.replace(/\s+/g, ' ') };
  }

  it('shows what, where, fix here and fix in tool in order', () => {
    const { text } = render();
    const t = text();
    const at = ['A column is missing', 'The table lacks', 'Your tool left it out', 'Where', 'line 12, SMF', 'Add the column', 'Enable the abundance'].map(
      (s) => t.indexOf(s),
    );
    expect(at.every((i) => i >= 0)).toBe(true);
    expect([...at].sort((a, b) => a - b)).toEqual(at);
    expect(t).toContain("What's wrong");
    expect(t).toContain('Fix it here');
    expect(t).toContain('Fix it in your tool');
  });

  it('prefers the tool variant and marks it as a variant', () => {
    const { text } = render({ toolFixInTool: 'In MyTool, tick the box.', toolName: 'MyTool 1.2' });
    expect(text()).toContain('In MyTool, tick the box.');
    expect(text()).not.toContain('Enable the abundance export.');
    expect(text()).toContain('For MyTool 1.2');
    expect(text()).not.toContain('For the tool that wrote this file');
    expect(render({ toolFixInTool: 'x' }).text()).toContain('For the tool that wrote this file');
    expect(render().text()).not.toContain('For the tool that wrote this file');
  });

  it('marks tabs and highlights the cell of the message column', () => {
    const { el } = render();
    const line = el.querySelector('[data-testid=quoted-line]')!;
    expect(line.textContent).toBe('SMF→id→abundance_assay[1]→other');
    const marks = Array.from(line.querySelectorAll('mark')).map((m) => m.textContent);
    expect(marks).toEqual(['abundance_assay[1]']);
  });

  it('uses the cell after MTD as the key of a metadata line', () => {
    const { text } = render({ lines: [{ lineNumber: 5, text: 'MTD\tsoftware[1]\tX', truncated: false }] });
    expect(text()).toContain('line 5, software[1]');
  });

  it('highlights nothing when the column is not on the line', () => {
    const { el } = render({}, [{ ...MESSAGES[0], location: { section: 'smf', column: 'nope' } }, MESSAGES[1], MESSAGES[2]]);
    expect(el.querySelectorAll('mark').length).toBe(0);
  });

  it('says how many more lines when more are affected than quoted', () => {
    expect(render().text()).toContain('and 2 more lines');
    expect(render({ messages: [0, 1] }).text()).toContain('and 1 more line');
    expect(render({ messages: [0] }).text()).not.toContain('more line');
  });

  it('shows an ellipsis for a truncated line', () => {
    const { el } = render({ lines: [{ lineNumber: 12, text: 'SMF\tid', truncated: true }] });
    expect(el.querySelector('.ellipsis')).not.toBeNull();
    expect(render().el.querySelector('.ellipsis')).toBeNull();
  });

  it('warns that later problems are hidden when stopsParsing', () => {
    const warning = 'jmzTab-m stopped reading at this line, so problems further down are not reported yet.';
    expect(render({ stopsParsing: true }).text()).toContain(warning);
    expect(render().text()).not.toContain(warning);
  });

  it('offers the quick fix hint only when a quick fix exists', () => {
    const hint = 'Open the file in the editor to use the quick fix';
    expect(render({ quickFix: 'insert-column' }).text()).toContain(hint);
    expect(render().text()).not.toContain(hint);
  });

  it('names the rule requirement level for CV-rule messages', () => {
    const ruled = (message_type: 'error' | 'warn' | 'info') =>
      MESSAGES.map((m) => ({ ...m, message_type, rule_id: 'rule-7' }));
    expect(render({}, ruled('warn')).text()).toContain('Rule rule-7: recommended');
    expect(render({}, ruled('error')).text()).toContain('Rule rule-7: required');
    expect(render({}, ruled('info')).text()).toContain('Rule rule-7: optional');
    expect(render().text()).not.toContain('Rule ');
  });

  it('emits the document target when the line heading is clicked', () => {
    const { fixture, el } = render();
    el.querySelector<HTMLButtonElement>('button.loc')!.click();
    expect(fixture.componentInstance.jumped).toEqual({
      tab: 'features',
      rowIndex: 0,
      column: 'abundance_assay[1]',
    });
  });

  describe('in the editor', () => {
    function inEditor(over: Partial<Guidance>, state: QuickFixState, messages: readonly ValidationMessage[] = MESSAGES) {
      const ctx = render(over, messages);
      ctx.fixture.componentInstance.quickFixState.set(state);
      ctx.fixture.detectChanges();
      return ctx;
    }

    it('offers the quick fix as a button instead of the hint', () => {
      const { fixture, el, text } = inEditor({ quickFix: 'remove-empty-parameter' }, 'ready');
      expect(text()).not.toContain('Open the file in the editor to use the quick fix');
      el.querySelector<HTMLButtonElement>('button.quick-fix')!.click();
      expect(fixture.componentInstance.fixed).toEqual({ fixId: 'remove-empty-parameter', all: false });
      const all = el.querySelector<HTMLButtonElement>('button.quick-fix-all')!;
      expect(all.textContent?.trim()).toBe('Fix all 3 like this');
      all.click();
      expect(fixture.componentInstance.fixed).toEqual({ fixId: 'remove-empty-parameter', all: true });
    });

    it('fixes one at a time where the fix cannot fix a group', () => {
      const { el } = inEditor({ quickFix: 'add-missing-metadata' }, 'ready');
      expect(el.querySelector('button.quick-fix')).toBeTruthy();
      expect(el.querySelector('button.quick-fix-all')).toBeNull();
      expect(inEditor({ quickFix: 'normalise-doi', messages: [0] }, 'ready').el.querySelector('button.quick-fix-all')).toBeNull();
    });

    it('disables the buttons while the latest changes are checked', () => {
      const { el, text } = inEditor({ quickFix: 'normalise-doi' }, 'checking');
      expect(el.querySelector<HTMLButtonElement>('button.quick-fix')!.disabled).toBe(true);
      expect(el.querySelector<HTMLButtonElement>('button.quick-fix-all')!.disabled).toBe(true);
      expect(text()).toContain('Checking your latest changes…');
    });

    it('offers no button for re-encoding, which is not a text edit', () => {
      const { el } = inEditor({ quickFix: 'reencode-latin1' }, 'ready');
      expect(el.querySelector('button.quick-fix')).toBeNull();
    });

    it('highlights the cell of a data line by the column index in the header', () => {
      const line = 'SML	7	PC 34:1	null	12.5';
      const messages = [{ ...MESSAGES[0], location: { section: 'sml' as const, column: 'abundance_assay[1]', row_index: 6 } }];
      const { fixture, el } = render({ messages: [0], lines: [{ lineNumber: 12, text: line, truncated: false }] }, messages);
      // without the header nothing on a data line is the column name
      expect(el.querySelectorAll('mark').length).toBe(0);
      fixture.componentInstance.headers.set(new Map([['SML', ['SML_ID', 'chemical_name', 'theoretical_neutral_mass', 'abundance_assay[1]']]]));
      fixture.detectChanges();
      expect(Array.from(el.querySelectorAll('mark')).map((m) => m.textContent)).toEqual(['12.5']);
    });

    it('highlights every cell of a column name the header holds more than once', () => {
      const line = 'SML	7	Sphingolipids	12.5	SP';
      const messages = [{ ...MESSAGES[0], location: { section: 'sml' as const, column: 'opt_global_category', row_index: 6 } }];
      const { fixture, el } = render({ messages: [0], lines: [{ lineNumber: 12, text: line, truncated: false }] }, messages);
      fixture.componentInstance.headers.set(
        new Map([['SML', ['SML_ID', 'opt_global_category', 'theoretical_neutral_mass', 'opt_global_category']]]),
      );
      fixture.detectChanges();
      expect(Array.from(el.querySelectorAll('mark')).map((m) => m.textContent)).toEqual(['Sphingolipids', 'SP']);
    });
  });
});
