import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';
import { MatDialog } from '@angular/material/dialog';

import { DocDuplicateColumn, DocTablePage, DocumentSource } from './document-source';
import { TableViewComponent } from './table-view.component';
import { EditableFakeDocumentSource, FakeDocumentSource } from './testing/fake-document-source';

/** SML_ID, then `name` three times (cells 3, 7 and 9) among other columns, and two empty trailing cells. */
const ALL = ['SML_ID', 'a', 'name', 'b', 'c', 'd', 'name', 'e', 'name', '', ''];

function page(over: Partial<DocTablePage> = {}): DocTablePage {
  return {
    section: 'SML',
    page: 0,
    size: 50,
    totalRows: 1,
    allColumns: ALL,
    columns: ALL,
    columnPositions: ALL.map((_, i) => i + 1),
    rows: [{ index: 0, lineNumber: 40, cells: ALL.map((_, i) => `v${i}`) }],
    ...over,
  };
}

const DUPLICATE: DocDuplicateColumn = {
  name: 'name',
  positions: [3, 7, 9],
  conflictingRows: 2,
  samples: [
    { lineNumber: 40, values: ['x', 'y', ''] },
    { lineNumber: 41, values: ['x', 'null', 'z'] },
  ],
};

@Component({
  imports: [TableViewComponent],
  template: `<mztabm-table-view section="sml" [readonly]="readonly()" />`,
})
class HostComponent {
  readonly readonly = signal(false);
}

describe('TableViewComponent duplicate columns', () => {
  let src: EditableFakeDocumentSource;

  async function render(first: DocTablePage = page(), source: FakeDocumentSource = new EditableFakeDocumentSource(), readonly = false) {
    src = source as EditableFakeDocumentSource;
    TestBed.configureTestingModule({
      providers: [
        { provide: DocumentSource, useValue: source },
        { provide: MATERIAL_ANIMATIONS, useValue: { animationsDisabled: true } },
      ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.readonly.set(readonly);
    fixture.detectChanges();
    const settle = async () => {
      for (let i = 0; i < 3; i++) {
        fixture.detectChanges();
        await new Promise((r) => setTimeout(r, 0));
      }
      fixture.detectChanges();
    };
    source.oneTable().reply(first);
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    const dialog = () => document.querySelector<HTMLElement>('mztabm-duplicate-column-dialog');
    const button = (root: ParentNode, text: string) =>
      [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === text);
    const radio = (label: string) =>
      [...document.querySelectorAll<HTMLLabelElement>('mztabm-duplicate-column-dialog label')]
        .find((l) => l.textContent?.trim().startsWith(label))
        ?.querySelector<HTMLInputElement>('input');
    return { fixture, el, settle, dialog, button, radio };
  }

  afterEach(() => TestBed.inject(MatDialog).closeAll());

  it('marks every header cell of a duplicated name, and only those', async () => {
    const { el } = await render();
    const heads = [...el.querySelectorAll<HTMLElement>('thead tr:first-child th')];
    const marked = heads.map((th) => th.classList.contains('col--dup'));
    expect(marked).toEqual([false, false, true, false, false, false, true, false, true, false, false]);
    expect(heads[2].textContent).toContain('Duplicate column name');
    expect(heads[1].textContent).not.toContain('Duplicate column name');
    // empty trailing header cells name no column, and the note names the duplicate once
    expect(el.querySelectorAll('.dup-note li').length).toBe(1);
    expect(el.querySelector('.dup-note li code')?.textContent).toBe('name');
    expect(el.querySelector('.resolve-dup')?.getAttribute('aria-label')).toBe('Resolve duplicate column name');
  });

  it('marks duplicates read-only too, without offering to resolve them', async () => {
    const { el } = await render(page(), new FakeDocumentSource(), true);
    expect(el.querySelectorAll('th.col--dup').length).toBe(3);
    expect(el.querySelector('.resolve-dup')).toBeNull();
  });

  it('edits the cell of the column at its position, not the first of its name', async () => {
    const { el, settle } = await render();
    const td = el.querySelectorAll<HTMLElement>('tbody tr td')[6];
    td.click();
    await settle();
    const input = td.querySelector<HTMLInputElement>('input.cell-input')!;
    input.value = 'new';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    await settle();
    expect(src.oneEdit().args).toEqual([{ op: 'SetCell', line: 40, cell: 7, text: 'new' }]);
  });

  it('keeps the chosen column and deletes the others, the rightmost first', async () => {
    const { el, settle, dialog, button, radio } = await render();
    el.querySelector<HTMLButtonElement>('.resolve-dup')!.click();
    await settle();
    expect(src.oneDuplicates().args).toBe('SMH');
    expect(TestBed.inject(MatDialog).openDialogs.length).toBe(1);
    src.duplicates[0].reply([{ name: 'other', positions: [2, 4] }, DUPLICATE]);
    await settle();
    expect(dialog()!.textContent).toContain('appears 3 times');
    expect(radio('Keep one column')!.checked).toBe(true);
    expect(radio('Column 3')!.checked).toBe(true);
    radio('Column 7')!.click();
    await settle();
    button(dialog()!, 'Delete the other columns')!.click();
    await settle();

    const batch = src.oneEdit();
    expect(batch.args).toEqual([
      { op: 'DeleteColumn', header: 'SMH', name: 'name', position: 9 },
      { op: 'DeleteColumn', header: 'SMH', name: 'name', position: 3 },
    ]);
    batch.reply();
    await settle();
    expect(TestBed.inject(MatDialog).openDialogs.length).toBe(0);
    // the source tells its views about the change; the table does not ask twice
    src.noneTable();
    src.changes.next();
    await settle();
    src.oneTable();
  });

  it('merges into the column that wins where rows disagree, showing how many do', async () => {
    const { el, settle, dialog, button, radio } = await render();
    el.querySelector<HTMLButtonElement>('.resolve-dup')!.click();
    await settle();
    src.oneDuplicates().reply([DUPLICATE]);
    await settle();
    radio('Merge them')!.click();
    await settle();
    expect(dialog()!.querySelector('[data-testid="conflicts"]')?.textContent).toContain('In 2 rows');
    const sampleRows = dialog()!.querySelectorAll('.samples tbody tr');
    expect(sampleRows.length).toBe(2);
    expect(sampleRows[0].textContent).toContain('40');
    expect(sampleRows[0].textContent).toContain('(empty)');
    // the first column wins by default
    button(dialog()!, 'Merge the columns')!.click();
    await settle();
    expect(src.oneEdit().args).toEqual([{ op: 'MergeColumns', header: 'SMH', name: 'name', keep: 3, others: [7, 9] }]);
  });

  it('cancels without an edit', async () => {
    const { el, settle, dialog, button } = await render();
    el.querySelector<HTMLButtonElement>('.resolve-dup')!.click();
    await settle();
    src.oneDuplicates().reply([DUPLICATE]);
    await settle();
    button(dialog()!, 'Cancel')!.click();
    await settle();
    expect(TestBed.inject(MatDialog).openDialogs.length).toBe(0);
    src.noneEdit();
  });

  it("shows the source's reason for a refused edit, and closes on a conflict", async () => {
    const { el, settle, dialog, button } = await render();
    el.querySelector<HTMLButtonElement>('.resolve-dup')!.click();
    await settle();
    src.oneDuplicates().reply([DUPLICATE]);
    await settle();
    button(dialog()!, 'Delete the other columns')!.click();
    await settle();
    src.oneEdit().fail(400, 'Column 9 of SMH is not name');
    await settle();
    expect(dialog()!.querySelector('[role="alert"]')?.textContent).toContain('Column 9 of SMH is not name');

    button(dialog()!, 'Delete the other columns')!.click();
    await settle();
    src.oneEdit().fail(409);
    await settle();
    expect(TestBed.inject(MatDialog).openDialogs.length).toBe(0);
  });

  it('lists a duplicated name once in the column picker', async () => {
    const { el, settle } = await render();
    el.querySelector<HTMLButtonElement>('.pick-open')!.click();
    await settle();
    const names = [...el.querySelectorAll('mztabm-column-picker .name')].map((n) => n.textContent);
    expect(names.filter((n) => n === 'name').length).toBe(1);
  });
});
