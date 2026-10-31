import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';

import { DocTablePage, DocumentSource } from './document-source';
import { TableViewComponent } from './table-view.component';
import { EditableFakeDocumentSource, FakeDocumentSource } from './testing/fake-document-source';

/** SML_ID and c1 … c60, where the column at cell 56 is a second c3: past the first window of 50. */
const WIDE = ['SML_ID', ...Array.from({ length: 60 }, (_, i) => (i === 54 ? 'c3' : `c${i + 1}`))];
const SECOND_WINDOW = [1, ...Array.from({ length: 11 }, (_, i) => 51 + i)];

/** A page of the given cell indexes; with `positions` false, a source that reports none (a validation). */
function page(cells: number[], all: string[] = WIDE, positions = true): DocTablePage {
  return {
    section: 'SML',
    page: 0,
    size: 50,
    totalRows: 1,
    allColumns: all,
    columns: cells.map((c) => all[c - 1]),
    ...(positions ? { columnPositions: cells } : {}),
    rows: [{ index: 0, lineNumber: 40, cells: cells.map((c) => `v${c}`) }],
  };
}
const FIRST_WINDOW = Array.from({ length: 50 }, (_, i) => i + 1);

@Component({
  imports: [TableViewComponent],
  template: `<mztabm-table-view section="sml" [readonly]="readonly()" />`,
})
class HostComponent {
  readonly readonly = signal(false);
}

describe('TableViewComponent columns by position', () => {
  async function render(first: DocTablePage, source: FakeDocumentSource = new EditableFakeDocumentSource()) {
    TestBed.configureTestingModule({
      providers: [
        { provide: DocumentSource, useValue: source },
        { provide: MATERIAL_ANIMATIONS, useValue: { animationsDisabled: true } },
      ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.readonly.set(!source.editable);
    fixture.detectChanges();
    const settle = async (ms = 0) => {
      for (let i = 0; i < 3; i++) {
        fixture.detectChanges();
        await new Promise((r) => setTimeout(r, ms));
      }
      fixture.detectChanges();
    };
    source.oneTable().reply(first);
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    const heads = () => [...el.querySelectorAll<HTMLElement>('thead tr:first-child th')];
    const next = async () => {
      el.querySelector<HTMLButtonElement>('button[aria-label="Next columns"]')!.click();
      await settle();
    };
    return { el, settle, heads, next };
  }

  it('asks for a later window by position, so a later copy of a name shows and is edited as itself', async () => {
    const src = new EditableFakeDocumentSource();
    const { el, settle, heads, next } = await render(page(FIRST_WINDOW), src);
    await next();
    const t = src.oneTable();
    expect(t.args.q.positions).toEqual(SECOND_WINDOW);
    expect(t.args.q.columns).toBeUndefined();
    t.reply(page(SECOND_WINDOW));
    await settle();
    // the second c3 is the 7th column shown: SML_ID, then cells 51 … 56
    expect(heads()[6].textContent).toContain('c3');
    const td = el.querySelectorAll<HTMLElement>('tbody tr td')[6];
    expect(td.textContent).toContain('v56');
    td.click();
    await settle();
    const input = td.querySelector<HTMLInputElement>('input.cell-input')!;
    input.value = 'new';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    await settle();
    expect(src.oneEdit().args).toEqual([{ op: 'SetCell', line: 40, cell: 56, text: 'new' }]);
  });

  it('sorts and filters a column by its position', async () => {
    const src = new EditableFakeDocumentSource();
    const { settle, heads, next } = await render(page(FIRST_WINDOW), src);
    await next();
    src.oneTable().reply(page(SECOND_WINDOW));
    await settle();
    heads()[6].querySelector<HTMLButtonElement>('button.sort')!.click();
    await settle();
    let q = src.oneTable().args.q;
    expect(q.sortPosition).toBe(56);
    expect(q.sort).toBeNull();
    expect(q.positions).toEqual(SECOND_WINDOW);
    src.tables.at(-1)!.reply(page(SECOND_WINDOW));
    await settle();
    expect(heads()[6].getAttribute('aria-sort')).toBe('ascending');
    expect(heads()[3].getAttribute('aria-sort')).toBe('none');

    const filter = [...document.querySelectorAll<HTMLInputElement>('input.filter')][6];
    filter.value = 'x';
    filter.dispatchEvent(new Event('input'));
    await settle(120);
    q = src.oneTable().args.q;
    expect(q.filters).toEqual([{ column: 'c3', position: 56, text: 'x' }]);
  });

  it('asks for every column of a picked name', async () => {
    const all = ['SML_ID', 'a', 'name', 'b', 'name'];
    const src = new EditableFakeDocumentSource();
    const { el, settle } = await render(page([1, 2, 3, 4, 5], all), src);
    el.querySelector<HTMLButtonElement>('.pick-open')!.click();
    await settle();
    const box = [...el.querySelectorAll<HTMLLabelElement>('.picker label')]
      .find((l) => l.textContent?.trim() === 'a')!
      .querySelector<HTMLInputElement>('input')!;
    box.checked = false;
    box.dispatchEvent(new Event('change'));
    await settle();
    el.querySelector<HTMLButtonElement>('.pick-apply')!.click();
    await settle();
    const q = src.oneTable().args.q;
    expect(q.positions).toEqual([1, 3, 5, 4]);
    expect(q.columns).toBeUndefined();
  });

  it('refuses a pick of more columns than one request can ask for, and says so', async () => {
    // "x" names 210 columns: with SML_ID and a, the pick would ask for 212 positions
    const all = ['SML_ID', 'a', ...Array.from({ length: 210 }, () => 'x')];
    const src = new EditableFakeDocumentSource();
    const { el, settle } = await render(page([1, 2, 3], all), src);
    el.querySelector<HTMLButtonElement>('.pick-open')!.click();
    await settle();
    el.querySelector<HTMLButtonElement>('.pick-apply')!.click();
    await settle();
    src.noneTable();
    const alert = el.querySelector('[data-testid=pick-error]')!;
    expect(alert.textContent).toContain('212 columns');
    expect(alert.textContent).toContain('up to 200');
    // the picker stays open to choose fewer
    expect(el.querySelector('.picker')).toBeTruthy();
    const box = [...el.querySelectorAll<HTMLLabelElement>('.picker label')]
      .find((l) => l.textContent?.trim() === 'x')!
      .querySelector<HTMLInputElement>('input')!;
    box.checked = false;
    box.dispatchEvent(new Event('change'));
    await settle();
    el.querySelector<HTMLButtonElement>('.pick-apply')!.click();
    await settle();
    expect(src.oneTable().args.q.positions).toEqual([1, 2]);
    expect(el.querySelector('[data-testid=pick-error]')).toBeNull();
  });

  it('asks a source without positions by name, as before', async () => {
    const src = new FakeDocumentSource();
    const { settle, heads, next } = await render(page(FIRST_WINDOW, WIDE, false), src);
    await next();
    const q = src.oneTable().args.q;
    expect(q.positions).toBeUndefined();
    expect(q.columns).toEqual([WIDE[0], ...WIDE.slice(50)]);
    src.tables.at(-1)!.reply(page(SECOND_WINDOW, WIDE, false));
    await settle();
    heads()[2].querySelector<HTMLButtonElement>('button.sort')!.click();
    await settle();
    expect(src.oneTable().args.q.sort).toBe('c51');
  });

  it('after a change, fetches the column list before asking by position again', async () => {
    const src = new EditableFakeDocumentSource();
    const { settle, heads, next } = await render(page(FIRST_WINDOW), src);
    await next();
    src.oneTable().reply(page(SECOND_WINDOW));
    await settle();
    // sort by c51 (cell 52), which a removed column further right does not move
    heads()[2].querySelector<HTMLButtonElement>('button.sort')!.click();
    await settle();
    expect(src.oneTable().args.q.sortPosition).toBe(52);
    src.tables.at(-1)!.reply(page(SECOND_WINDOW));
    await settle();

    // the second c3 (cell 56) was merged away elsewhere
    src.changes.next();
    await settle();
    const probe = src.oneTable();
    expect(probe.args.q.positions).toBeUndefined();
    expect(probe.args.q.sortPosition).toBeUndefined();
    const fewer = WIDE.filter((_, i) => i !== 55);
    probe.reply(page(FIRST_WINDOW, fewer));
    await settle();
    // the probe's rows are not shown: the view still shows the second window
    expect(heads()[1].textContent).toContain('c50');
    const again = src.oneTable().args.q;
    expect(again.positions).toEqual(SECOND_WINDOW.slice(0, -1));
    expect(again.sortPosition).toBe(52);
    src.tables.at(-1)!.reply(page(SECOND_WINDOW.slice(0, -1), fewer));
    await settle();
    expect(heads().length).toBe(11);
    src.noneTable();
  });

  it('a failed column-list request after a change is asked again by Retry, not by the old positions', async () => {
    const src = new EditableFakeDocumentSource();
    const { el, settle, heads, next } = await render(page(FIRST_WINDOW), src);
    await next();
    src.oneTable().reply(page(SECOND_WINDOW));
    await settle();
    src.changes.next();
    await settle();
    src.oneTable().fail(500);
    await settle();
    // the rows shown are kept, with the error; nothing is asked until Retry
    expect(el.textContent).toContain('The table could not be loaded');
    expect(heads()[1].textContent).toContain('c50');
    src.noneTable();
    el.querySelector<HTMLButtonElement>('button.retry')!.click();
    await settle();
    const probe = src.oneTable();
    expect(probe.args.q.positions).toBeUndefined();
    const fewer = WIDE.filter((_, i) => i !== 55);
    probe.reply(page(FIRST_WINDOW, fewer));
    await settle();
    expect(src.oneTable().args.q.positions).toEqual(SECOND_WINDOW.slice(0, -1));
  });

  it('after a change that moves the sorted column, drops the sort', async () => {
    const src = new EditableFakeDocumentSource();
    const { settle, heads, next } = await render(page(FIRST_WINDOW), src);
    await next();
    src.oneTable().reply(page(SECOND_WINDOW));
    await settle();
    heads()[8].querySelector<HTMLButtonElement>('button.sort')!.click(); // c57, cell 58
    await settle();
    src.oneTable().reply(page(SECOND_WINDOW));
    await settle();
    src.changes.next();
    await settle();
    src.oneTable().reply(page(FIRST_WINDOW, WIDE.filter((_, i) => i !== 55)));
    await settle();
    const q = src.oneTable().args.q;
    expect(q.sortPosition).toBeUndefined();
    expect(q.sort).toBeNull();
  });
});
