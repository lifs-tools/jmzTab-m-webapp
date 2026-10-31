import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Subject } from 'rxjs';

import { QuickFixPreview } from '../../api/models/quick-fix-preview';
import { WorkingCopyInfo } from '../../api/models/working-copy-info';
import { QuickFixDialogComponent } from './quick-fix-dialog.component';
import { WorkingCopyStore } from './working-copy.store';

const PREVIEW: QuickFixPreview = {
  fixId: 'normalise-doi',
  title: 'Write the DOI as doi:10.xxxx/yyyy',
  revision: 3,
  occurrences: 2,
  changes: [
    { lineNumber: 5, before: 'MTD\tpublication[1]\tdoi:https://doi.org/10.1/x', after: 'MTD\tpublication[1]\tdoi:10.1/x' },
    { lineNumber: 6, before: 'MTD\tpublication[2]\t[, , , ]', after: null },
  ],
  batch: {
    revision: 3,
    operations: [
      { op: 'SetMetadataValue', line: 5, value: 'doi:10.1/x' } as never,
      { op: 'DeleteLine', line: 6 } as never,
    ],
  },
};

describe('QuickFixDialogComponent', () => {
  function render(preview: QuickFixPreview = PREVIEW) {
    const result = new Subject<WorkingCopyInfo>();
    const store = { applyFix: vi.fn(() => result.asObservable()) };
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
      imports: [QuickFixDialogComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: preview },
        { provide: MatDialogRef, useValue: ref },
        { provide: WorkingCopyStore, useValue: store },
      ],
    });
    const fixture = TestBed.createComponent(QuickFixDialogComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const apply = () => el.querySelector<HTMLButtonElement>('button.apply')!;
    return { fixture, el, store, ref, result, apply };
  }

  it('shows before and after, the count, and applies the batch', () => {
    const { fixture, el, store, ref, result, apply } = render();
    expect(el.textContent).toContain('Write the DOI as doi:10.xxxx/yyyy');
    expect(el.querySelector('[data-testid=fix-count]')?.textContent).toBe('Fixes 2 problems');
    const changes = el.querySelectorAll('.change');
    expect(changes.length).toBe(2);
    expect(changes[0].querySelector('.line')?.textContent).toBe('line 5');
    expect(changes[0].querySelector('del')?.textContent).toBe('MTD→publication[1]→doi:https://doi.org/10.1/x');
    expect(changes[0].querySelector('ins')?.textContent).toBe('MTD→publication[1]→doi:10.1/x');
    expect(changes[1].querySelector('ins')).toBeNull();
    expect(changes[1].textContent).toContain('The line is removed.');

    apply().click();
    fixture.detectChanges();
    expect(store.applyFix).toHaveBeenCalledWith(PREVIEW);
    expect(apply().disabled).toBe(true);
    const after: WorkingCopyInfo = { id: 'abc', revision: 4 };
    result.next(after);
    result.complete();
    expect(ref.close).toHaveBeenCalledWith(after);
  });

  it('counts one problem in the singular', () => {
    const { el, apply } = render({ ...PREVIEW, occurrences: 1 });
    expect(el.querySelector('[data-testid=fix-count]')?.textContent).toBe('Fixes 1 problem');
    expect(apply().textContent?.trim()).toBe('Apply');
  });

  it("names the server's count of the problems on the button that applies them all", () => {
    expect(render().apply().textContent?.trim()).toBe('Apply to all 2');
    TestBed.resetTestingModule();
    expect(render({ ...PREVIEW, occurrences: 1234 }).apply().textContent?.trim()).toBe('Apply to all 1,234');
  });

  it('says that only the first changed lines are shown when the server cut the list', () => {
    const lines = (n: number) =>
      Array.from({ length: n }, (_, i) => ({ lineNumber: i + 1, before: `SML\t${i}`, after: `SML\t${i}\tnull` }));
    const op = (o: string) => ({ op: o }) as never;
    const hint = (preview: QuickFixPreview) => {
      TestBed.resetTestingModule();
      return render(preview).el.textContent?.includes('Only the first changed lines are shown.');
    };
    // 200 lines listed and one column insert, which changes every row: cut
    expect(hint({ ...PREVIEW, occurrences: 1, changes: lines(200), batch: { revision: 3, operations: [op('InsertColumn')] } })).toBe(true);
    // 200 lines for 250 problems: cut
    expect(hint({ ...PREVIEW, occurrences: 250, changes: lines(200), batch: { revision: 3, operations: Array(250).fill(op('DeleteLine')) } })).toBe(true);
    // exactly 200 problems on 200 lines: complete
    expect(hint({ ...PREVIEW, occurrences: 200, changes: lines(200), batch: { revision: 3, operations: Array(200).fill(op('DeleteLine')) } })).toBe(false);
    // fewer lines than the cap are always complete, even when a line takes two operations
    expect(hint({ ...PREVIEW, occurrences: 3, changes: lines(3), batch: { revision: 3, operations: Array(6).fill(op('JoinWithPrevious')) } })).toBe(false);
  });

  it("shows the server's reason when the fix is refused", () => {
    const { fixture, el, apply, result } = render();
    apply().click();
    result.error(new HttpErrorResponse({ status: 400, error: { message: 'There is no line 6' } }));
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('There is no line 6');
  });

  it('shows an error and stays open when the fix is refused, closes on a conflict', () => {
    const { fixture, el, ref, result, apply } = render();
    apply().click();
    result.error(new HttpErrorResponse({ status: 400 }));
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('The fix could not be applied.');
    expect(ref.close).not.toHaveBeenCalled();
    expect(apply().disabled).toBe(false);

    TestBed.resetTestingModule();
    const second = render();
    second.apply().click();
    second.result.error(new HttpErrorResponse({ status: 409 }));
    expect(second.ref.close).toHaveBeenCalledWith(null);
  });
});
