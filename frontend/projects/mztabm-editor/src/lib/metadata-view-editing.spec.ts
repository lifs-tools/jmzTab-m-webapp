import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { DocumentSource } from './document-source';
import { MetadataHighlight, MetadataViewComponent } from './metadata-view.component';
import { EditableFakeDocumentSource } from './testing/fake-document-source';

@Component({
  imports: [MetadataViewComponent],
  template: `<mztabm-metadata-view [readonly]="readonly()" [highlight]="highlight()" />`,
})
class HostComponent {
  readonly readonly = signal(false);
  readonly highlight = signal<MetadataHighlight | null>(null);
}

const ENTRIES = [
  { lineNumber: 2, key: 'mzTab-version', value: '2.0.0-M' },
  { lineNumber: 3, key: 'mzTab-ID', value: 'ISAS-1' },
];

describe('MetadataViewComponent editing', () => {
  let src: EditableFakeDocumentSource;

  async function render() {
    src = new EditableFakeDocumentSource();
    TestBed.configureTestingModule({ providers: [{ provide: DocumentSource, useValue: src }] });
    const fixture = TestBed.createComponent(HostComponent);
    const settle = async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    fixture.detectChanges();
    src.oneGroups().reply([{ name: 'general', count: 2 }]);
    await settle();
    src.onePage('general').reply({ group: 'general', page: 0, size: 50, total: 2, entries: ENTRIES });
    await settle();
    return { fixture, el: fixture.nativeElement as HTMLElement, settle };
  }

  const key = (target: Element, k: string) =>
    target.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));

  it('edits a value and emits SetMetadataValue with its line number', async () => {
    const { el, settle } = await render();
    const button = el.querySelectorAll<HTMLButtonElement>('button.value-edit')[1];
    expect(button.textContent).toContain('ISAS-1');
    expect(button.querySelector('.vh')?.textContent).toContain('Edit mzTab-ID');
    button.click();
    await settle();
    const input = el.querySelector<HTMLInputElement>('input.value-input')!;
    expect(document.activeElement).toBe(input);
    input.value = 'ISAS-2';
    key(input, 'Enter');
    await settle();
    expect(src.oneEdit().args).toEqual([{ op: 'SetMetadataValue', line: 3, value: 'ISAS-2' }]);
    expect(el.querySelectorAll('button.value-edit')[1].textContent).toContain('ISAS-2');
  });

  it('Escape cancels a value edit', async () => {
    const { el, settle } = await render();
    el.querySelectorAll<HTMLButtonElement>('button.value-edit')[0].click();
    await settle();
    const input = el.querySelector<HTMLInputElement>('input.value-input')!;
    input.value = 'x';
    key(input, 'Escape');
    await settle();
    src.noneEdit();
    expect(el.querySelector('input.value-input')).toBeNull();
    expect(document.activeElement).toBe(el.querySelectorAll('button.value-edit')[0]);
  });

  it('refuses a tab in a value', async () => {
    const { el, settle } = await render();
    el.querySelectorAll<HTMLButtonElement>('button.value-edit')[0].click();
    await settle();
    const input = el.querySelector<HTMLInputElement>('input.value-input')!;
    input.value = 'a\tb';
    key(input, 'Enter');
    await settle();
    src.noneEdit();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('A cell cannot contain a tab or a line break.');
  });

  it('opens the value of the jumped-to key for editing when the jump asks for it', async () => {
    const { fixture, el, settle } = await render();
    fixture.componentInstance.highlight.set({ group: 'general', keys: ['mzTab-ID'], focusKey: 'mzTab-ID', moveFocus: true, edit: true });
    await settle();
    src.onePage('general').reply({ group: 'general', page: 0, size: 50, total: 2, entries: [...ENTRIES, { lineNumber: 4, key: 'title', value: '' }] });
    await settle();
    await settle();
    const input = el.querySelector<HTMLInputElement>('input.value-input')!;
    expect(input).toBeTruthy();
    expect(input.closest('.entry')?.querySelector('.key')?.textContent).toBe('mzTab-ID');
    expect(document.activeElement).toBe(input);
  });

  it('reloads the groups and the open page when the document changed, keeping the panels', async () => {
    const { fixture, el, settle } = await render();
    src.changes.next();
    fixture.detectChanges();
    // while the groups load again, the panels stay
    expect(el.querySelector('mat-accordion mztabm-metadata-group')).toBeTruthy();
    src.oneGroups().reply([{ name: 'general', count: 3 }]);
    src.onePage('general').reply({ group: 'general', page: 0, size: 50, total: 3, entries: ENTRIES });
    await settle();
    expect(el.querySelector('mat-panel-title')?.textContent?.trim()).toBe('general (3)');
  });

  it('shows plain values when read-only', async () => {
    const { fixture, el, settle } = await render();
    fixture.componentInstance.readonly.set(true);
    await settle();
    expect(el.querySelector('button.value-edit')).toBeNull();
    expect(el.querySelectorAll('.value')[1].textContent).toContain('ISAS-1');
  });
});
