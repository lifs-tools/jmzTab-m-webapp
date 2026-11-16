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
import { TestBed } from '@angular/core/testing';

import { ImportSource } from './import-source';
import { NOTHING_MISSING, NOT_CHECKED_TEXT, StillMissingComponent } from './still-missing.component';
import { FakeImportSource } from './testing/fake-import-source';

@Component({
  imports: [StillMissingComponent],
  template: `<mztabm-still-missing [checkedRevision]="revision()" />`,
})
class HostComponent {
  readonly revision = signal<number | null>(1);
}

const rule = (name: string | undefined, path: string, requirement: string) => ({
  id: path,
  name,
  path,
  requirement,
  present: false,
});

describe('StillMissingComponent', () => {
  let src: FakeImportSource;

  function render(revision: number | null = 1) {
    src = new FakeImportSource();
    TestBed.configureTestingModule({ providers: [{ provide: ImportSource, useValue: src }] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.revision.set(revision);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const group = (heading: string) =>
      Array.from(el.querySelectorAll<HTMLElement>('[data-testid=missing-group]')).find(
        (g) => g.querySelector('h4')?.textContent?.trim() === heading,
      );
    const items = (g: HTMLElement | undefined) => Array.from(g?.querySelectorAll('li') ?? []).map((li) => li.textContent?.trim());
    return { fixture, el, group, items };
  }

  it('lists MUST rules under Required and SHOULD rules under Recommended, by name or else by path', () => {
    const { fixture, group, items, el } = render();
    src.oneMissing().reply({
      revision: 1,
      checked: true,
      missing: [
        rule('Quantification method', '/metadata/@quantificationMethod', 'MUST'),
        rule(undefined, '/metadata/@sampleProcessing', 'MUST'),
        rule('Instrument source', '/metadata/instrument/@source', 'SHOULD'),
      ],
    });
    fixture.detectChanges();
    expect(items(group('Required'))).toEqual(['Quantification method', '/metadata/@sampleProcessing']);
    expect(items(group('Recommended'))).toEqual(['Instrument source']);
    expect(el.textContent).not.toContain(NOTHING_MISSING);
    expect(group('Not yet checked')).toBeUndefined();
  });

  it('says that nothing is missing for an empty list of a checked text', () => {
    const { fixture, el, group } = render();
    src.oneMissing().reply({ revision: 1, checked: true, missing: [] });
    fixture.detectChanges();
    expect(el.textContent).toContain('Nothing is missing for these repository rules.');
    expect(group('Required')).toBeUndefined();
    expect(group('Recommended')).toBeUndefined();
  });

  it('lists the rules under Not yet checked when the text could not be read, never "nothing missing"', () => {
    const { fixture, el, group, items } = render();
    src.oneMissing().reply({
      revision: 1,
      checked: false,
      missing: [rule('Quantification method', '/q', 'MUST'), rule('Instrument source', '/i', 'SHOULD')],
    });
    fixture.detectChanges();
    expect(NOT_CHECKED_TEXT).toBe(
      'The file cannot be read into a model yet, so these rules could not be checked against it. Fix the errors in the messages first.',
    );
    expect(el.textContent).toContain(NOT_CHECKED_TEXT);
    const unchecked = group('Not yet checked');
    expect(items(unchecked).map((t) => t?.replace(/\s+/g, ' '))).toEqual([
      'Quantification method (required)',
      'Instrument source (recommended)',
    ]);
    expect(group('Required')).toBeUndefined();
    expect(el.textContent).not.toContain(NOTHING_MISSING);
  });

  it('refreshes when the check of a newer revision is done, and only then', () => {
    const { fixture, el } = render(1);
    src.oneMissing().reply({ revision: 1, checked: true, missing: [rule('A', '/a', 'MUST')] });
    fixture.detectChanges();

    fixture.componentInstance.revision.set(1);
    fixture.detectChanges();
    src.noneMissing();

    fixture.componentInstance.revision.set(2);
    fixture.detectChanges();
    src.oneMissing().reply({ revision: 2, checked: true, missing: [] });
    fixture.detectChanges();
    expect(el.textContent).toContain(NOTHING_MISSING);
  });

  it('asks nothing before a check of the file is done', () => {
    const { fixture } = render(null);
    src.noneMissing();
    fixture.componentInstance.revision.set(0);
    fixture.detectChanges();
    src.oneMissing();
  });

  it("says the server's reason when the checklist could not be made", () => {
    const { fixture, el } = render();
    src.oneMissing().fail(503, 'The validator is busy.');
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('The validator is busy.');
  });
});
