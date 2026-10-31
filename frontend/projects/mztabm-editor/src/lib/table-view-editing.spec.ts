import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { DocTablePage, DocumentSource } from './document-source';
import { TableHighlight, TableViewComponent } from './table-view.component';
import { EditableFakeDocumentSource } from './testing/fake-document-source';

/** 60 columns: the first window (50) shows SML_ID … col[49]; col[55] is outside it. */
const ALL = Array.from({ length: 60 }, (_, i) => (i === 0 ? 'SML_ID' : `col[${i}]`));

function page(over: Partial<DocTablePage> = {}): DocTablePage {
  const columns = over.columns ?? ALL.slice(0, 50);
  return {
    section: 'SML',
    page: 0,
    size: 50,
    totalRows: 2,
    allColumns: ALL,
    columns,
    rows: [
      { index: 0, lineNumber: 40, cells: columns.map((_, i) => `a${i}`) },
      { index: 1, lineNumber: 41, cells: columns.map((_, i) => `b${i}`) },
    ],
    ...over,
  };
}

@Component({
  imports: [TableViewComponent],
  template: `<mztabm-table-view section="sml" [readonly]="readonly()" [highlight]="highlight()" />`,
})
class HostComponent {
  readonly readonly = signal(false);
  readonly highlight = signal<TableHighlight | null>(null);
}

describe('TableViewComponent editing', () => {
  let src: EditableFakeDocumentSource;

  async function render(first: DocTablePage = page()) {
    src = new EditableFakeDocumentSource();
    TestBed.configureTestingModule({ providers: [{ provide: DocumentSource, useValue: src }] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const settle = async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    src.oneTable().reply(first);
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    const cell = (row: number, col: number) =>
      el.querySelectorAll('tbody tr')[row].querySelectorAll<HTMLElement>('td')[col];
    return { fixture, el, settle, cell };
  }

  const key = (target: Element, k: string, init: KeyboardEventInit = {}) =>
    target.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true, ...init }));

  it('edits a cell and emits SetCell with its line number', async () => {
    const { el, settle, cell } = await render();
    cell(1, 3).click();
    await settle();
    const input = cell(1, 3).querySelector<HTMLInputElement>('input.cell-input')!;
    expect(input).toBeTruthy();
    expect(input.value).toBe('b3');
    expect(input.getAttribute('aria-label')).toBe('Edit col[3], row 2');
    expect(document.activeElement).toBe(input);
    input.value = 'PC 34:1';
    key(input, 'Enter');
    await settle();

    const batch = src.oneEdit();
    // cell 0 of a line is its prefix: header column 3 is cell 4 of line 41
    expect(batch.args).toEqual([{ op: 'SetCell', line: 41, cell: 4, text: 'PC 34:1' }]);
    expect(el.querySelector('input.cell-input')).toBeNull();
    // shown at once, and kept: no reload after the own edit
    expect(cell(1, 3).textContent?.trim()).toBe('PC 34:1');
    batch.reply();
    await settle();
    src.noneTable();
    expect(cell(1, 3).textContent?.trim()).toBe('PC 34:1');
    expect(document.activeElement).toBe(cell(1, 3));
  });

  it('Escape cancels an edit', async () => {
    const { el, settle, cell } = await render();
    cell(0, 2).click();
    await settle();
    const input = cell(0, 2).querySelector<HTMLInputElement>('input.cell-input')!;
    input.value = 'changed';
    key(input, 'Escape');
    await settle();
    expect(el.querySelector('input.cell-input')).toBeNull();
    expect(cell(0, 2).textContent?.trim()).toBe('a2');
    src.noneEdit();
    expect(document.activeElement).toBe(cell(0, 2));
  });

  it('numbers the cell by its position among all columns, also in a later window', async () => {
    const cols = [ALL[0], ...ALL.slice(50, 60)];
    const { settle, cell } = await render(page({ columns: cols }));
    cell(0, 6).click(); // col[55]
    await settle();
    const input = cell(0, 6).querySelector<HTMLInputElement>('input')!;
    input.value = 'x';
    key(input, 'Enter');
    expect(src.oneEdit().args).toEqual([{ op: 'SetCell', line: 40, cell: 56, text: 'x' }]);
  });

  it('refuses a tab in a cell and keeps editing', async () => {
    const { el, settle, cell } = await render();
    cell(0, 1).click();
    await settle();
    const input = cell(0, 1).querySelector<HTMLInputElement>('input')!;
    input.value = 'a\tb';
    key(input, 'Enter');
    await settle();
    src.noneEdit();
    expect(el.querySelector('input.cell-input')).toBe(input);
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('A cell cannot contain a tab or a line break.');
    expect(input.getAttribute('aria-invalid')).toBe('true');
  });

  it('saves nothing when the text is unchanged', async () => {
    const { el, settle, cell } = await render();
    cell(0, 1).click();
    await settle();
    key(cell(0, 1).querySelector('input')!, 'Enter');
    await settle();
    src.noneEdit();
    expect(el.querySelector('input.cell-input')).toBeNull();
  });

  it('moves between cells with the arrow keys and edits with Enter', async () => {
    const { settle, cell } = await render();
    // one tab stop: the first cell
    expect(cell(0, 0).getAttribute('tabindex')).toBe('0');
    expect(cell(0, 1).getAttribute('tabindex')).toBe('-1');
    cell(0, 0).focus();
    key(cell(0, 0), 'ArrowRight');
    await settle();
    expect(document.activeElement).toBe(cell(0, 1));
    key(cell(0, 1), 'ArrowDown');
    await settle();
    expect(document.activeElement).toBe(cell(1, 1));
    expect(cell(1, 1).getAttribute('tabindex')).toBe('0');
    expect(cell(0, 0).getAttribute('tabindex')).toBe('-1');
    key(cell(1, 1), 'Enter');
    await settle();
    expect(document.activeElement).toBe(cell(1, 1).querySelector('input'));
  });

  it('shows an error and reloads when the edit is refused, and stays quiet on a conflict', async () => {
    const { el, settle, cell } = await render();
    cell(0, 1).click();
    await settle();
    let input = cell(0, 1).querySelector<HTMLInputElement>('input')!;
    input.value = 'x';
    key(input, 'Enter');
    src.oneEdit().fail(400);
    await settle();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('This change could not be saved.');
    src.oneTable().reply(page());
    await settle();
    expect(cell(0, 1).textContent?.trim()).toBe('a1');

    cell(0, 2).click();
    await settle();
    input = cell(0, 2).querySelector<HTMLInputElement>('input')!;
    input.value = 'y';
    key(input, 'Enter');
    src.oneEdit().fail(409);
    await settle();
    // the host reloads the document after a conflict and says so
    expect(el.querySelector('[role="alert"]')).toBeNull();
    src.noneTable();
  });

  it('requests the page again when the document changed', async () => {
    const { settle } = await render();
    src.changes.next();
    await settle();
    expect(src.oneTable().args.q.page).toBe(0);
  });

  it('highlights the message cell on a data row', async () => {
    const { fixture, el, settle } = await render();
    fixture.componentInstance.highlight.set({ rows: [1], focusRow: 1, column: 'col[3]', header: false, moveFocus: true });
    await settle();
    const hl = el.querySelectorAll('td.cell--hl');
    expect(hl.length).toBe(1);
    expect(hl[0].textContent?.trim()).toBe('b3');
    expect(document.activeElement).toBe(hl[0]);
  });

  it('offers no editing when read-only', async () => {
    const { fixture, el, settle, cell } = await render();
    fixture.componentInstance.readonly.set(true);
    await settle();
    cell(0, 1).click();
    await settle();
    expect(el.querySelector('input.cell-input')).toBeNull();
    expect(cell(0, 1).getAttribute('tabindex')).toBeNull();
  });
});
