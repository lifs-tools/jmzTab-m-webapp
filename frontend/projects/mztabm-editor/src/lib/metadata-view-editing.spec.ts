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

  it('removes a line: a Remove button per entry emits DeleteLine with that entry\'s line number', async () => {
    const { el, settle } = await render();
    const buttons = el.querySelectorAll<HTMLButtonElement>('button.entry-remove');
    expect(buttons.length).toBe(2);
    expect(buttons[1].getAttribute('aria-label')).toBe('Remove line 3');
    expect(buttons[1].title).toBe('Remove this line');
    buttons[1].click();
    await settle();
    expect(src.oneEdit().args).toEqual([{ op: 'DeleteLine', line: 3 }]);
    // the entry is gone at once, and no second removal can use the line numbers that just moved
    expect(el.querySelectorAll('.entry').length).toBe(1);
    expect((el.querySelector('button.entry-remove') as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows the last page when a reload leaves the page shown past the end', async () => {
    src = new EditableFakeDocumentSource();
    TestBed.configureTestingModule({ providers: [{ provide: DocumentSource, useValue: src }] });
    const fixture = TestBed.createComponent(HostComponent);
    const el = fixture.nativeElement as HTMLElement;
    // not whenStable: it waits for the groups that are loading again
    const settle = async () => {
      for (let i = 0; i < 3; i++) {
        fixture.detectChanges();
        await new Promise((r) => setTimeout(r, 0));
      }
      fixture.detectChanges();
    };
    const entries = (from: number, n: number) =>
      Array.from({ length: n }, (_, i) => ({ lineNumber: from + i, key: `k${from + i}`, value: 'v' }));
    fixture.detectChanges();
    src.oneGroups().reply([{ name: 'general', count: 51 }]);
    await settle();
    src.onePage('general').reply({ group: 'general', page: 0, size: 50, total: 51, entries: entries(2, 50) });
    await settle();
    el.querySelector<HTMLButtonElement>('button[aria-label="Next page"]')!.click();
    await settle();
    src.onePage('general').reply({ group: 'general', page: 1, size: 50, total: 51, entries: entries(52, 1) });
    await settle();
    expect(el.querySelector('.pager')?.textContent).toContain('51–51 of 51');

    // the only entry of the last page is removed
    el.querySelector<HTMLButtonElement>('button.entry-remove')!.click();
    await settle();
    src.oneEdit().reply();
    src.changes.next();
    await settle();
    src.onePage('general').reply({ group: 'general', page: 1, size: 50, total: 50, entries: [] });
    await settle();
    src.oneGroups().reply([{ name: 'general', count: 50 }]);
    await settle();

    const last = src.onePage('general');
    expect(last.args.page).toBe(0);
    last.reply({ group: 'general', page: 0, size: 50, total: 50, entries: entries(2, 50) });
    await settle();
    expect(el.querySelectorAll('.entry').length).toBe(50);
    // one page: no pager, and never "51–50 of 50"
    expect(el.textContent).not.toContain('51–50');
  });

  it('a reload does not open again a highlighted panel that the user collapsed', async () => {
    src = new EditableFakeDocumentSource();
    TestBed.configureTestingModule({ providers: [{ provide: DocumentSource, useValue: src }] });
    const fixture = TestBed.createComponent(HostComponent);
    const el = fixture.nativeElement as HTMLElement;
    const settle = async () => {
      for (let i = 0; i < 3; i++) {
        fixture.detectChanges();
        await new Promise((r) => setTimeout(r, 0));
      }
      fixture.detectChanges();
    };
    const groups = [{ name: 'general', count: 2 }, { name: 'sample', count: 1 }];
    const samplePage = { group: 'sample', page: 0, size: 50, total: 1, entries: [{ lineNumber: 9, key: 'sample[1]', value: 's' }] };
    fixture.detectChanges();
    src.oneGroups().reply(groups);
    await settle();
    src.onePage('general').reply({ group: 'general', page: 0, size: 50, total: 2, entries: ENTRIES });
    fixture.componentInstance.highlight.set({ group: 'sample', keys: ['sample[1]'], focusKey: 'sample[1]', moveFocus: false });
    await settle();
    src.onePage('sample').reply(samplePage);
    await settle();
    const header = el.querySelectorAll<HTMLElement>('.mat-expansion-panel-header')[1];
    expect(header.getAttribute('aria-expanded')).toBe('true');
    header.click();
    await settle();
    expect(header.getAttribute('aria-expanded')).toBe('false');

    src.changes.next();
    await settle();
    src.oneGroups().reply(groups.map((g) => ({ ...g })));
    src.pages.filter((p) => !p.taken).forEach((p) => {
      p.taken = true;
      p.reply(p.args.group === 'sample' ? samplePage : { group: 'general', page: 0, size: 50, total: 2, entries: ENTRIES });
    });
    await settle();
    expect(el.querySelectorAll<HTMLElement>('.mat-expansion-panel-header')[1].getAttribute('aria-expanded')).toBe('false');

    // a new highlight is a new jump: it opens the panel again
    fixture.componentInstance.highlight.set({ group: 'sample', keys: ['sample[1]'], focusKey: 'sample[1]', moveFocus: false });
    await settle();
    expect(el.querySelectorAll<HTMLElement>('.mat-expansion-panel-header')[1].getAttribute('aria-expanded')).toBe('true');
  });

  it('passes the page\'s readAt with its SetMetadataValue and DeleteLine', async () => {
    src = new EditableFakeDocumentSource();
    TestBed.configureTestingModule({ providers: [{ provide: DocumentSource, useValue: src }] });
    const fixture = TestBed.createComponent(HostComponent);
    const el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    src.oneGroups().reply([{ name: 'general', count: 2 }]);
    fixture.detectChanges();
    await fixture.whenStable();
    src.onePage('general').reply({ group: 'general', page: 0, size: 50, total: 2, entries: ENTRIES, readAt: 4 });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    el.querySelectorAll<HTMLButtonElement>('button.value-edit')[1].click();
    fixture.detectChanges();
    await fixture.whenStable();
    const input = el.querySelector<HTMLInputElement>('input.value-input')!;
    input.value = 'ISAS-2';
    key(input, 'Enter');
    src.oneEdit().reply();
    el.querySelectorAll<HTMLButtonElement>('button.entry-remove')[0].click();
    src.oneEdit().reply();
    expect(src.editOptions).toEqual([{ readAt: 4 }, { readAt: 4 }]);
  });

  it('after the document changed, closes a value editor and saves or removes nothing until the page is read again', async () => {
    const { fixture, el, settle } = await render();
    el.querySelectorAll<HTMLButtonElement>('button.value-edit')[1].click();
    await settle();
    expect(el.querySelector('input.value-input')).toBeTruthy();

    src.changes.next();
    fixture.detectChanges();
    // the line numbers shown may be stale: no edit names them
    expect(el.querySelector('input.value-input')).toBeNull();
    const remove = el.querySelectorAll<HTMLButtonElement>('button.entry-remove')[0];
    expect(remove.disabled).toBe(true);
    remove.click();
    src.noneEdit();

    src.oneGroups().reply([{ name: 'general', count: 2 }]);
    src.onePage('general').reply({ group: 'general', page: 0, size: 50, total: 2, entries: ENTRIES });
    await settle();
    expect(el.querySelectorAll<HTMLButtonElement>('button.entry-remove')[0].disabled).toBe(false);
  });

  it('shows no Remove button when read-only', async () => {
    const { fixture, el, settle } = await render();
    fixture.componentInstance.readonly.set(true);
    await settle();
    expect(el.querySelector('button.entry-remove')).toBeNull();
  });

  it('shows plain values when read-only', async () => {
    const { fixture, el, settle } = await render();
    fixture.componentInstance.readonly.set(true);
    await settle();
    expect(el.querySelector('button.value-edit')).toBeNull();
    expect(el.querySelectorAll('.value')[1].textContent).toContain('ISAS-1');
  });
});
