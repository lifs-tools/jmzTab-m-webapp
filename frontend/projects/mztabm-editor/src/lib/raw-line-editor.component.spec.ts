import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { DocumentSource } from './document-source';
import { RawLineEditorComponent } from './raw-line-editor.component';
import { EditableFakeDocumentSource } from './testing/fake-document-source';

@Component({
  imports: [RawLineEditorComponent],
  template: `<mztabm-raw-line-editor [lineNumber]="line()" [text]="text()" [readAt]="readAt()" (saved)="saved = saved + 1" (cancelled)="cancelled = cancelled + 1" (refused)="refused.push($event)" (maybeSaved)="maybe.push($event)" />`,
})
class HostComponent {
  readonly line = signal(12);
  readonly readAt = signal<number | null>(null);
  readonly refused: string[] = [];
  readonly maybe: string[] = [];
  readonly text = signal('MTD\tdescription\tfirst part');
  saved = 0;
  cancelled = 0;
}

describe('RawLineEditorComponent', () => {
  let src: EditableFakeDocumentSource;

  function render(text?: string) {
    src = new EditableFakeDocumentSource();
    TestBed.configureTestingModule({ providers: [{ provide: DocumentSource, useValue: src }] });
    const fixture = TestBed.createComponent(HostComponent);
    if (text !== undefined) fixture.componentInstance.text.set(text);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const input = () => el.querySelector<HTMLInputElement>('input.raw')!;
    const button = (name: string) =>
      Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === name)!;
    return { fixture, el, input, button };
  }

  const key = (target: Element, k: string) =>
    target.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
  const type = (input: HTMLInputElement, value: string) => {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };

  it('opens the raw line editor for a line without prefix', () => {
    const { el, input } = render('identification and quantification\t\t');
    expect(el.textContent).toContain('Line 12');
    expect(input().value).toBe('identification and quantification→→');
    expect(input().getAttribute('aria-label')).toBe('Text of line 12');
    expect(el.textContent).toContain('→ stands for a tab.');
  });

  it('saves the text with real tabs as SetRawLine', () => {
    const { fixture, input } = render();
    type(input(), 'MTD→description→first part, second part');
    key(input(), 'Enter');
    const batch = src.oneEdit();
    expect(batch.args).toEqual([{ op: 'SetRawLine', line: 12, text: 'MTD\tdescription\tfirst part, second part' }]);
    batch.reply();
    expect(fixture.componentInstance.saved).toBe(1);
  });

  it('tells the host the text typed and not saved, but not while it is being saved', () => {
    const { fixture, input } = render();
    const editor = fixture.debugElement.children[0].componentInstance as RawLineEditorComponent;
    expect(editor.unsavedText()).toBeNull();
    type(input(), 'MTD→description→typed');
    expect(editor.unsavedText()).toBe('MTD→description→typed');
    key(input(), 'Enter');
    expect(editor.unsavedText()).toBeNull();
    src.oneEdit().reply();
  });

  it('passes readAt with its edits', () => {
    const { fixture, input } = render();
    fixture.componentInstance.readAt.set(5);
    fixture.detectChanges();
    type(input(), 'MTD→description→x');
    key(input(), 'Enter');
    src.oneEdit().reply();
    expect(src.editOptions).toEqual([{ readAt: 5 }]);
  });

  it('hands the text it tried to save to the host when the save is refused as a conflict', () => {
    const { fixture, input } = render();
    type(input(), 'MTD→description→typed');
    key(input(), 'Enter');
    src.oneEdit().fail(409);
    expect(fixture.componentInstance.refused).toEqual(['MTD→description→typed']);
    expect(fixture.componentInstance.saved).toBe(0);
  });

  it('a save the host could not tell was saved is not "refused": the text goes to maybeSaved', () => {
    const { fixture, input } = render();
    type(input(), 'MTD→description→typed');
    key(input(), 'Enter');
    src.oneEdit().failMaybeSaved();
    expect(fixture.componentInstance.maybe).toEqual(['MTD→description→typed']);
    expect(fixture.componentInstance.refused).toEqual([]);
    expect(fixture.componentInstance.saved).toBe(0);
  });

  it('saves nothing for an unchanged line, also when the tabs are shown', () => {
    const { fixture, input } = render();
    key(input(), 'Enter');
    src.noneEdit();
    expect(fixture.componentInstance.cancelled).toBe(1);
  });

  it('keeps a literal arrow: then tabs are shown as they are', () => {
    const { el, input } = render('COM\ta → b');
    expect(input().value).toBe('COM\ta → b');
    expect(el.textContent).not.toContain('→ stands for a tab.');
    type(input(), 'COM\ta → c');
    key(input(), 'Enter');
    expect(src.oneEdit().args).toEqual([{ op: 'SetRawLine', line: 12, text: 'COM\ta → c' }]);
  });

  it('inserts a tab at the cursor', () => {
    const { fixture, input, button } = render('MTDdescription');
    input().setSelectionRange(3, 3);
    button('Insert tab').click();
    fixture.detectChanges();
    expect(input().value).toBe('MTD→description');
    key(input(), 'Enter');
    expect(src.oneEdit().args).toEqual([{ op: 'SetRawLine', line: 12, text: 'MTD\tdescription' }]);
  });

  it('Escape restores the line and cancels', () => {
    const { fixture, input } = render();
    type(input(), 'something else');
    key(input(), 'Escape');
    fixture.detectChanges();
    expect(input().value).toBe('MTD→description→first part');
    expect(fixture.componentInstance.cancelled).toBe(1);
    src.noneEdit();
  });

  it('deletes the line', () => {
    const { fixture, button } = render('');
    button('Delete this line').click();
    src.oneEdit().reply();
    expect(fixture.componentInstance.saved).toBe(1);
    expect(src.edits[0].args).toEqual([{ op: 'DeleteLine', line: 12 }]);
  });

  it('shows an error when the change is refused', () => {
    const { fixture, el, input } = render();
    type(input(), 'MTD→x');
    key(input(), 'Enter');
    src.oneEdit().fail(400);
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('This change could not be saved.');
    expect(fixture.componentInstance.saved).toBe(0);
  });
});
