/*
 * Copyright 2026 LIFS Tools Consortium.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { APPLIED_NOTICE, AddFromPanelComponent } from './add-from-panel.component';
import { ImportPreviewComponent } from './import-preview.component';
import { ImportSource, VOCABULARY_INSTANCE } from './import-source';
import { FakeImportSource } from './testing/fake-import-source';

@Component({
  imports: [AddFromPanelComponent],
  template: `<mztabm-add-from-panel [version]="version()" [fileName]="'my-study.mzTab'" [checkedRevision]="revision()" />`,
})
class HostComponent {
  readonly version = signal<string | null>('2.0');
  readonly revision = signal<number | null>(null);
}

describe('AddFromPanelComponent', () => {
  let src: FakeImportSource;
  let fixture: ComponentFixture<HostComponent>;
  let el: HTMLElement;

  function render(version = '2.0') {
    src = new FakeImportSource();
    TestBed.configureTestingModule({ providers: [{ provide: ImportSource, useValue: src }] });
    fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.version.set(version);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }
  const buttons = () => Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid=import-kind]'));

  it('says that adding from a file is not available for a version without kinds, and offers nothing', () => {
    render('2.1');
    src.oneKinds().reply([]);
    fixture.detectChanges();
    expect(el.textContent).toContain('Adding from a file is not available for mzTab-M 2.1 yet.');
    expect(buttons()).toEqual([]);
  });

  it('offers a vocabulary file, which opens its preview', () => {
    render();
    src.oneKinds().reply([VOCABULARY_INSTANCE]);
    fixture.detectChanges();
    expect(buttons().map((b) => b.textContent?.trim())).toEqual(['A vocabulary file (JSON)']);
    expect(el.querySelector('mztabm-import-preview')).toBeNull();
    buttons()[0].click();
    fixture.detectChanges();
    expect(el.querySelector('mztabm-import-preview')).not.toBeNull();
  });

  it('closes the preview once the import is applied, and says how to take it back', () => {
    render();
    src.oneKinds().reply([VOCABULARY_INSTANCE]);
    fixture.detectChanges();
    buttons()[0].click();
    fixture.detectChanges();
    const preview = fixture.debugElement.query((d) => d.componentInstance instanceof ImportPreviewComponent);
    (preview.componentInstance as ImportPreviewComponent).applied.emit();
    fixture.detectChanges();
    expect(el.querySelector('mztabm-import-preview')).toBeNull();
    expect(el.querySelector('[role=status]')?.textContent).toContain(APPLIED_NOTICE);
    expect(buttons().length).toBe(1);
  });

  it('offers no kind it does not know', () => {
    render();
    src.oneKinds().reply(['something-else']);
    fixture.detectChanges();
    expect(buttons()).toEqual([]);
    expect(el.textContent).toContain('Adding from a file is not available for mzTab-M 2.0 yet.');
  });

  it('asks for the kinds again when the version changes', () => {
    render();
    src.oneKinds().reply([VOCABULARY_INSTANCE]);
    fixture.componentInstance.version.set('2.1');
    fixture.detectChanges();
    src.oneKinds().reply([]);
    fixture.detectChanges();
    expect(buttons()).toEqual([]);
  });

  it('holds the still-missing checklist, which follows the checks of the file', () => {
    render();
    src.oneKinds().reply([]);
    expect(el.querySelector('mztabm-still-missing')).not.toBeNull();
    src.noneMissing();
    fixture.componentInstance.revision.set(0);
    fixture.detectChanges();
    src.oneMissing();
  });
});
