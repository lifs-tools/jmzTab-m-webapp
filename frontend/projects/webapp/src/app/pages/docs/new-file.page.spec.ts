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
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { pageTitle } from '../../core/page-titles';
import { NewFileDocsPage } from './new-file.page';

describe('NewFileDocsPage', () => {
  async function render() {
    await TestBed.configureTestingModule({
      imports: [NewFileDocsPage],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(NewFileDocsPage);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('has a section for each topic, each named by its heading', async () => {
    const el = await render();
    const sections = Array.from(el.querySelectorAll('.page section')).map((s) => ({
      labelledBy: s.getAttribute('aria-labelledby'),
      heading: s.querySelector('h2')?.textContent?.trim(),
      headingId: s.querySelector('h2')?.id,
    }));
    expect(sections.map((s) => s.heading)).toEqual([
      'The three choices',
      'Why the new file is not valid yet',
      'Still missing',
      'Add from a vocabulary file',
      'Undo',
    ]);
    expect(sections.every((s) => s.labelledBy && s.labelledBy === s.headingId)).toBe(true);
    expect(Array.from(el.querySelectorAll('h2, h3')).filter((h) => !h.id)).toEqual([]);
  });

  it('names the choices and the labels of the form and the panel as they are shown', async () => {
    const text = (await render()).textContent!.replace(/\s+/g, ' ');
    for (const label of [
      'mzTab-M version',
      'Content profile',
      'Repository rules',
      'Create file',
      'Still missing',
      'Required',
      'Recommended',
      'Not yet checked',
      'Nothing is missing for these repository rules.',
      'The file cannot be read into a model yet, so these rules could not be checked against it. Fix the errors in the messages first.',
      'A vocabulary file (JSON)',
      'Keep existing values',
      'Preview again',
      'kept (differs)',
      'replaces existing',
      'Apply',
      'Undo',
    ]) {
      expect(text).toContain(label);
    }
  });

  it('links the README of the vocabulary and the page about fixing', async () => {
    const el = await render();
    expect(el.querySelector('a[href$="submission-vocabulary/README.md"]')?.textContent).toContain('submission vocabulary');
    expect(el.querySelector('a[href$="/docs/fixing"]')).toBeTruthy();
  });

  it('has a fixed page title', () => {
    expect(pageTitle('/docs/new-file')).toBe('mzTab-M Validator · Starting a new file');
  });
});
