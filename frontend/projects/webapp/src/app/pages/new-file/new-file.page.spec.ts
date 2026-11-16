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
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';
import { Router, provideRouter } from '@angular/router';

import { AppInfoDto } from '../../api/models/app-info-dto';
import { ValidationProfile } from '../../api/models/validation-profile';
import { pageTitle } from '../../core/page-titles';
import { CONTENT_PROFILES, NewFilePage } from './new-file.page';

const APP_INFO: AppInfoDto = {
  defaultLine: '2.0',
  engines: [
    { line: '2.0', mzTabVersions: ['2.0.0-M'], available: true },
    { line: '2.1', mzTabVersions: ['2.1.0-M'], available: true },
  ],
};

const PROFILES: ValidationProfile[] = [
  { id: 'mztab-m-2.0-default', version: '1.0.0', title: 'mzTab-M 2.0 default rules', description: 'Old rules.', mzTabVersions: ['2.0.0-M'] },
  { id: 'mztab-m-2.0-default', version: '1.1.0', title: 'mzTab-M 2.0 default rules', description: 'The reference rules.', mzTabVersions: ['2.0.0-M'] },
  { id: 'lipidcompass', version: '1.0.0', title: 'LipidCompass', description: 'For LipidCompass.', mzTabVersions: ['2.0.0-M'] },
  { id: 'mztab-m-2.1-default', version: '1.0.0', title: 'mzTab-M 2.1 default rules', description: '2.1 rules.', mzTabVersions: ['2.1.0-M'] },
];

describe('NewFilePage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<NewFilePage>;
  let el: HTMLElement;
  let navigate: ReturnType<typeof vi.fn>;

  async function render(info: AppInfoDto = APP_INFO) {
    await TestBed.configureTestingModule({
      imports: [NewFilePage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MATERIAL_ANIMATIONS, useValue: { animationsDisabled: true } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true) as never;
    fixture = TestBed.createComponent(NewFilePage);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne((r) => r.url.endsWith('rest/v2/app-info')).flush(info);
    http.expectOne((r) => r.url.endsWith('rest/v2/validation-profiles')).flush(PROFILES);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const select = (testid: string) => el.querySelector<HTMLSelectElement>(`select[data-testid=${testid}]`)!;
  const options = (testid: string) => Array.from(select(testid).options).map((o) => o.textContent?.trim());
  const choose = async (testid: string, value: string) => {
    select(testid).value = value;
    select(testid).dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  const idInput = () => el.querySelector<HTMLInputElement>('input[data-testid=mztab-id]')!;
  const typeId = (value: string) => {
    idInput().value = value;
    idInput().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  const submit = () => el.querySelector<HTMLButtonElement>('button[type=submit]')!;
  const contentProfiles = () => el.querySelector('[data-testid=content-profile]');
  const posts = () => http.match((r) => r.method === 'POST' && r.url.endsWith('rest/v2/working-copies/new'));

  afterEach(() => http.verify());

  it('offers the versions of the configured engines, the default preselected', async () => {
    await render();
    expect(options('version')).toEqual(['2.0.0-M', '2.1.0-M']);
    expect(select('version').value).toBe('2.0.0-M');
    expect(el.textContent).toContain('mzTab-M version');
  });

  it('preselects the default line of a server whose default is 2.1', async () => {
    await render({ ...APP_INFO, defaultLine: '2.1' });
    expect(select('version').value).toBe('2.1.0-M');
  });

  it('shows the content profile for 2.1 only, with its five options and their meanings', async () => {
    await render();
    expect(contentProfiles()).toBeNull();
    await choose('version', '2.1.0-M');
    const labels = Array.from(contentProfiles()!.querySelectorAll('label')).map((l) => l.textContent?.replace(/\s+/g, ' ').trim());
    expect(labels).toEqual([
      'M Metadata only.',
      'M+S Metadata and the summary table (SML).',
      'M+F Metadata and the feature table (SMF).',
      'M+F+E Metadata, features (SMF) and evidence (SME).',
      'M+S+F+E All four sections.',
    ]);
    expect(CONTENT_PROFILES.map((p) => p.value)).toEqual(['M', 'M+S', 'M+F', 'M+F+E', 'M+S+F+E']);
    expect(contentProfiles()!.textContent).toContain('content profile');
    await choose('version', '2.0.0-M');
    expect(contentProfiles()).toBeNull();
  });

  it("lists the catalog's repository rules that list the version, the version's default preselected", async () => {
    await render();
    expect(options('rules')).toEqual([
      'mzTab-M 2.0 default rules 1.0.0',
      'mzTab-M 2.0 default rules 1.1.0',
      'LipidCompass 1.0.0',
    ]);
    // the latest version of the line's default
    expect(select('rules').value).toBe('mztab-m-2.0-default@1.1.0');
    expect(el.textContent).toContain('The reference rules.');
    expect(el.textContent).toContain('repository rules');
    await choose('version', '2.1.0-M');
    expect(options('rules')).toEqual(['mzTab-M 2.1 default rules 1.0.0']);
    expect(select('rules').value).toBe('mztab-m-2.1-default@1.0.0');
  });

  it('posts the choices and opens the editor on the new copy', async () => {
    await render();
    typeId('LCS-99999-01');
    submit().click();
    fixture.detectChanges();
    const sent = posts();
    expect(sent.length).toBe(1);
    expect(sent[0].request.body).toEqual({
      mzTabVersion: '2.0.0-M',
      contentProfile: null,
      profileId: 'mztab-m-2.0-default',
      profileVersion: '1.1.0',
      mzTabId: 'LCS-99999-01',
    });
    sent[0].flush({ id: 'n1', origin: 'new', revision: 0 }, { status: 201, statusText: 'Created' });
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith(['/edit', 'n1']);
  });

  it('posts the content profile of a 2.1 file, which must be chosen first', async () => {
    await render();
    await choose('version', '2.1.0-M');
    typeId('study');
    expect(submit().disabled).toBe(true);
    const radio = Array.from(contentProfiles()!.querySelectorAll<HTMLInputElement>('input[type=radio]')).find((r) => r.value === 'M+S+F+E')!;
    radio.click();
    fixture.detectChanges();
    expect(submit().disabled).toBe(false);
    submit().click();
    expect(posts()[0].request.body).toEqual({
      mzTabVersion: '2.1.0-M',
      contentProfile: 'M+S+F+E',
      profileId: 'mztab-m-2.1-default',
      profileVersion: '1.0.0',
      mzTabId: 'study',
    });
  });

  it('checks the mzTab-ID before posting', async () => {
    await render();
    expect(submit().disabled).toBe(true);
    typeId('bad id');
    expect(submit().disabled).toBe(true);
    expect(idInput().getAttribute('aria-invalid')).toBe('true');
    expect(el.textContent).toContain('Start with a letter or a digit');
    typeId('x'.repeat(65));
    expect(submit().disabled).toBe(true);
    typeId('ok.id_1-2');
    expect(submit().disabled).toBe(false);
    expect(posts().length).toBe(0);
  });

  it("shows the server's reason next to the form when it refuses the new file", async () => {
    await render();
    typeId('LCS-1');
    submit().click();
    posts()[0].flush(
      { message: 'The repository rules mztab-m-2.1-default 1.0.0 apply to mzTab-M 2.1.0-M, not to mzTab-M 2.0.0-M.' },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('apply to mzTab-M 2.1.0-M, not to mzTab-M 2.0.0-M.');
    expect(navigate).not.toHaveBeenCalled();
    expect(submit().disabled).toBe(false);
  });

  it("shows the server's reason for a busy or full server too", async () => {
    await render();
    typeId('LCS-1');
    submit().click();
    posts()[0].flush({ message: 'The storage for files is full.' }, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('The storage for files is full.');
  });

  it('has its own page title', () => {
    expect(pageTitle('/new')).toBe('mzTab-M Validator · New file');
  });
});
