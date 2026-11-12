import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ColumnPickerComponent } from './column-picker.component';
import { DocumentSource } from './document-source';
import { MetadataViewComponent } from './metadata-view.component';
import { TableViewComponent } from './table-view.component';
import { FakeDocumentSource } from './testing/fake-document-source';

@Component({
  imports: [MetadataViewComponent, TableViewComponent],
  template: `
    <mztabm-metadata-view />
    <mztabm-table-view section="sml" />
  `,
})
class DefaultHostComponent {}

@Component({
  imports: [TableViewComponent],
  template: `<mztabm-table-view section="sml" [readonly]="false" />`,
})
class EditableHostComponent {}

@Component({
  imports: [ColumnPickerComponent],
  template: `<mztabm-column-picker [allColumns]="all" [selected]="['a']" />`,
})
class PickerHostComponent {
  readonly all = ['a', 'b', 'c'];
}

const COLS = ['SML_ID', 'chemical_name', 'exp_mass_to_charge'];

describe('library views over a read-only source', () => {
  let src: FakeDocumentSource;
  beforeEach(() => {
    src = new FakeDocumentSource();
    TestBed.configureTestingModule({ providers: [{ provide: DocumentSource, useValue: src }] });
  });

  it('renders identically for a read-only source', async () => {
    expect(src.editable).toBe(false);
    expect(src.applyEdits).toBeUndefined();
    const fixture = TestBed.createComponent(DefaultHostComponent);
    fixture.detectChanges();
    src.oneGroups().reply([{ name: 'general', count: 1 }]);
    src.oneTable().reply({
      section: 'SML',
      page: 0,
      size: 50,
      totalRows: 3,
      allColumns: COLS,
      columns: COLS,
      rows: [{ index: 0, cells: ['1', 'PC 34:1', 'null'] }],
    });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    src.onePage('general').reply({ group: 'general', page: 0, size: 50, total: 1, entries: [{ lineNumber: 1, key: 'mzTab-ID', value: 'x' }] });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    // the DOM landmarks of the views before the move
    expect(el.querySelector('mat-accordion mat-expansion-panel mat-panel-title')?.textContent?.trim()).toBe('general (1)');
    expect(el.querySelector('.entries .entry .key')?.textContent).toBe('mzTab-ID');
    expect(Array.from(el.querySelectorAll('thead tr:first-child th .col-name')).map((c) => c.textContent)).toEqual(COLS);
    expect(el.querySelectorAll('thead tr.filter-row input[type="search"]').length).toBe(3);
    expect(el.querySelector('.table-wrap[role="region"][aria-label="Table data"]')).toBeTruthy();
    expect(el.querySelector('.pager select')).toBeTruthy();
    expect(el.querySelector('.pager [aria-live="polite"]')?.textContent).toContain('1–3 of 3');
    expect(el.querySelector('button[aria-label="Previous page"]')).toBeTruthy();
    expect(el.querySelector('button[aria-label="Next page"]')).toBeTruthy();
    expect(el.querySelector('button.pick-open')?.textContent?.trim()).toBe('Choose columns');
    // nothing editable is offered
    expect(el.querySelector('[contenteditable], td input, td textarea')).toBeNull();
    expect(el.querySelector('mztabm-table-view')?.getAttribute('data-readonly')).toBe('true');
  });

  it('is read-only unless the host says otherwise', () => {
    const fixture = TestBed.createComponent(EditableHostComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('mztabm-table-view').getAttribute('data-readonly')).toBe('false');
  });

  it('renders the column picker with its landmarks', () => {
    const fixture = TestBed.createComponent(PickerHostComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('section.picker[aria-label="Choose columns"]')).toBeTruthy();
    expect(el.querySelectorAll('input[type="checkbox"]').length).toBe(3);
    expect(el.querySelector('.pick-apply')).toBeTruthy();
  });
});
