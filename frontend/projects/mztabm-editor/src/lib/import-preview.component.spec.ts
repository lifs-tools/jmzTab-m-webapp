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

import { ImportPreviewView, ImportSource, VOCABULARY_INSTANCE } from './import-source';
import { ImportPreviewComponent, MAX_IMPORT_BYTES, OUT_OF_DATE_NOTICE, STALE_NOTICE } from './import-preview.component';
import { FakeImportSource } from './testing/fake-import-source';

@Component({
  imports: [ImportPreviewComponent],
  template: `<mztabm-import-preview [kind]="kind" [fileName]="fileName()" [revision]="revision()" (applied)="applied = applied + 1" (closed)="closed = closed + 1" />`,
})
class HostComponent {
  readonly kind = VOCABULARY_INSTANCE;
  readonly fileName = signal<string | null>('my-study.mzTab');
  readonly revision = signal<number | null>(null);
  applied = 0;
  closed = 0;
}

const dataset = (id: string, title: string) => ({ dataset_id: id, dataset_title: title });
const instance = (datasets: object[], lcsId: string | null = null) => ({ study: { study_title: 'T', lcs_id: lcsId }, datasets });

const preview = (over: Partial<ImportPreviewView> = {}): ImportPreviewView => ({
  kind: VOCABULARY_INSTANCE,
  revision: 3,
  mapping: 'mztab-m-2.0-output',
  mappingVersion: '1.0.0',
  suggestions: [
    { key: 'mzTab-ID', value: 'LCS-99999-01', state: 'REPLACES_EXISTING' },
    { key: 'title', value: 'Plasma lipidome', state: 'NEW' },
    { key: 'description', value: 'Same', state: 'UNCHANGED' },
    { key: 'sample[1]', value: 'Other', state: 'CONFLICTS' },
  ],
  notes: ['ms_run locations are not known yet.', 'Two lipids have no Goslin name.'],
  batch: { revision: 3, operations: [{ op: 'InsertLines', before: 3, lines: ['MTD\ttitle\tPlasma lipidome'] }] },
  ...over,
});

describe('ImportPreviewComponent', () => {
  let src: FakeImportSource;
  let fixture: ComponentFixture<HostComponent>;
  let el: HTMLElement;

  function render(fileName: string | null = 'my-study.mzTab') {
    src = new FakeImportSource();
    TestBed.configureTestingModule({ providers: [{ provide: ImportSource, useValue: src }] });
    fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.fileName.set(fileName);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  async function settle() {
    for (let i = 0; i < 3; i++) {
      await new Promise((r) => setTimeout(r, 0));
      fixture.detectChanges();
    }
  }

  async function choose(content: unknown, name = 'instance.json') {
    const file = new File([typeof content === 'string' ? content : JSON.stringify(content)], name, { type: 'application/json' });
    const input = el.querySelector<HTMLInputElement>('input[type=file]')!;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await settle();
  }

  const button = (name: string) =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === name);
  const keep = () => el.querySelector<HTMLInputElement>('input[data-testid=keep-existing]')!;
  const studyId = () => el.querySelector<HTMLInputElement>('input[data-testid=study-id]')!;
  const rows = () =>
    Array.from(el.querySelectorAll('tbody tr')).map((tr) => Array.from(tr.querySelectorAll('td')).map((td) => td.textContent?.trim()));

  it('asksForDatasetWhenSeveral: an instance with two datasets asks for one, by title and id, before any request', async () => {
    render();
    await choose(instance([dataset('D1', 'Positive mode'), dataset('D2', 'Negative mode')]));
    src.nonePreview();
    const options = Array.from(el.querySelectorAll<HTMLInputElement>('input[name=dataset]'));
    expect(options.length).toBe(2);
    const labels = Array.from(el.querySelectorAll('[data-testid=dataset-choice] label')).map((l) => l.textContent?.replace(/\s+/g, ' ').trim());
    expect(labels).toEqual(['Positive mode (D1)', 'Negative mode (D2)']);
    expect(el.textContent).toContain('This file has 2 datasets. Choose the one this mzTab-M file is for.');

    options[1].click();
    await settle();
    const req = src.onePreview();
    expect(req.args).toEqual({
      kind: VOCABULARY_INSTANCE,
      instance: instance([dataset('D1', 'Positive mode'), dataset('D2', 'Negative mode')]),
      datasetIndex: 2,
      studyId: 'my-study',
      keepExisting: true,
    });
  });

  it('previews an instance with one dataset at once, with the study id of the instance', async () => {
    render();
    await choose(instance([dataset('D1', 'Only')], 'LCS-00123'));
    expect(el.querySelector('[data-testid=dataset-choice]')).toBeNull();
    expect(studyId().value).toBe('LCS-00123');
    const req = src.onePreview();
    expect(req.args.datasetIndex).toBe(1);
    expect(req.args.studyId).toBe('LCS-00123');
  });

  it('takes the study id from the file name when the instance has none, and previews again when it is changed', async () => {
    render('LCS-99999-01.mzTab');
    await choose(instance([dataset('D1', 'Only')]));
    // the trailing -NN (two digits) of the file name is a copy number, not part of the study id
    expect(studyId().value).toBe('LCS-99999');
    src.onePreview().reply(preview());
    await settle();

    studyId().value = 'LCS-12345';
    studyId().dispatchEvent(new Event('change'));
    await settle();
    expect(src.onePreview().args.studyId).toBe('LCS-12345');
  });

  it('strips only a trailing two-digit number from the file name stem', async () => {
    render('my-study-1.mzTab');
    await choose(instance([dataset('D1', 'Only')]));
    expect(studyId().value).toBe('my-study-1');
  });

  it('asks for a study id rather than preview without one', async () => {
    render(null);
    await choose(instance([dataset('D1', 'Only')]));
    src.nonePreview();
    expect(el.textContent).toContain('Enter the study id.');
  });

  it('shows each suggestion with its key, value and state, and lists the notes', async () => {
    render();
    await choose(instance([dataset('D1', 'Only')]));
    src.onePreview().reply(preview());
    await settle();
    expect(rows()).toEqual([
      ['mzTab-ID', 'LCS-99999-01', 'replaces existing'],
      ['title', 'Plasma lipidome', 'new'],
      ['description', 'Same', 'unchanged'],
      ['sample[1]', 'Other', 'kept (differs)'],
    ]);
    const notes = Array.from(el.querySelectorAll('[data-testid=import-notes] li')).map((li) => li.textContent?.trim());
    expect(notes).toEqual(['ms_run locations are not known yet.', 'Two lipids have no Goslin name.']);
    expect(el.textContent).toContain('mztab-m-2.0-output 1.0.0');
  });

  it('keeps existing values by default, and previews again when that is changed', async () => {
    render();
    await choose(instance([dataset('D1', 'Only')]));
    expect(keep().checked).toBe(true);
    expect(src.onePreview().args.keepExisting).toBe(true);

    keep().click();
    await settle();
    expect(src.onePreview().args.keepExisting).toBe(false);
  });

  it('applies the preview as it was shown and tells the host', async () => {
    render();
    await choose(instance([dataset('D1', 'Only')]));
    const shown = preview();
    src.onePreview().reply(shown);
    await settle();
    button('Apply')!.click();
    const apply = src.oneApply();
    expect(apply.args).toBe(shown);
    apply.reply('applied');
    await settle();
    expect(fixture.componentInstance.applied).toBe(1);
  });

  it('staleImportIsPreviewedAgain: a stale apply previews again and says so', async () => {
    render();
    await choose(instance([dataset('D1', 'Only')]));
    src.onePreview().reply(preview());
    await settle();
    button('Apply')!.click();
    src.oneApply().reply('stale');
    await settle();

    const again = src.onePreview();
    expect(again.args.keepExisting).toBe(true);
    again.reply(preview({ revision: 5, suggestions: [{ key: 'title', value: 'Plasma lipidome', state: 'NEW' }] }));
    await settle();
    expect(STALE_NOTICE).toBe('The file changed since the preview. Here is the preview for the current file.');
    expect(el.textContent).toContain(STALE_NOTICE);
    expect(rows()).toEqual([['title', 'Plasma lipidome', 'new']]);
    expect(fixture.componentInstance.applied).toBe(0);
  });

  it('says what it is doing in one live region that is always there', async () => {
    render();
    const status = () => el.querySelector('[data-testid=import-status]');
    const region = status();
    expect(region?.getAttribute('role')).toBe('status');
    expect(region?.textContent?.trim()).toBe('');
    await choose(instance([dataset('D1', 'Only')]));
    expect(status()).toBe(region);
    expect(region?.textContent?.trim()).toBe('Preparing the preview…');
    src.onePreview().reply(preview());
    await settle();
    expect(region?.textContent?.trim()).toBe('');
    button('Apply')!.click();
    src.oneApply().reply('stale');
    await settle();
    expect(status()).toBe(region);
    expect(region?.textContent?.trim()).toBe('Preparing the preview…');
    src.onePreview().reply(preview({ revision: 5 }));
    await settle();
    expect(status()).toBe(region);
    expect(region?.textContent?.trim()).toBe(STALE_NOTICE);
  });

  it('marks a preview out of date when the file changes, before Apply is pressed', async () => {
    render();
    fixture.componentInstance.revision.set(3);
    await choose(instance([dataset('D1', 'Only')]));
    src.onePreview().reply(preview({ revision: 3 }));
    await settle();
    expect(button('Apply')!.disabled).toBe(false);
    expect(button('Preview again')).toBeUndefined();

    fixture.componentInstance.revision.set(4);
    await settle();
    expect(OUT_OF_DATE_NOTICE).toBe('The file changed since this preview. Preview again before applying.');
    expect(el.querySelector('[data-testid=import-status]')?.textContent?.trim()).toBe(OUT_OF_DATE_NOTICE);
    expect(button('Apply')!.disabled).toBe(true);
    button('Preview again')!.click();
    await settle();
    src.onePreview().reply(preview({ revision: 4 }));
    await settle();
    expect(button('Apply')!.disabled).toBe(false);
    expect(el.querySelector('[data-testid=import-status]')?.textContent?.trim()).toBe('');
  });

  it('offers no Apply when nothing would change', async () => {
    render();
    await choose(instance([dataset('D1', 'Only')]));
    src.onePreview().reply(preview({ suggestions: [{ key: 'title', value: 'x', state: 'UNCHANGED' }] }));
    await settle();
    expect(button('Apply')?.disabled).toBe(true);
    expect(el.textContent).toContain('Nothing would change.');
  });

  it("says the server's reason when the preview is refused", async () => {
    render();
    await choose(instance([dataset('D1', 'Only')]));
    src.onePreview().fail(400, 'dataset 4 of 3');
    await settle();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('dataset 4 of 3');
  });

  it('refuses a file that is not JSON, or is too large, without a request', async () => {
    render();
    await choose('MTD\tmzTab-version\t2.0.0-M', 'x.mzTab');
    expect(el.querySelector('[role=alert]')?.textContent).toContain('This file is not JSON.');
    src.nonePreview();

    await choose(' '.repeat(MAX_IMPORT_BYTES + 1));
    expect(el.querySelector('[role=alert]')?.textContent).toContain('The file is larger than 5 MiB, the most an import takes.');
    src.nonePreview();
  });

  it('Cancel tells the host', async () => {
    render();
    button('Cancel')!.click();
    expect(fixture.componentInstance.closed).toBe(1);
  });
});
