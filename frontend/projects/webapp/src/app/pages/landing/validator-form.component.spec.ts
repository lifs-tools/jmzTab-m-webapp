import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AppInfoDto } from '../../api/models/app-info-dto';
import { ValidationProfile } from '../../api/models/validation-profile';
import { ValidatorFormComponent } from './validator-form.component';

const PROFILES: ValidationProfile[] = [
  {
    id: 'mztab-m-2.0-default',
    version: '1.1.0',
    title: 'mzTab-M 2.0 default',
    description: 'Default rules',
    mzTabVersions: ['2.0.0-M'],
  },
  {
    id: 'lipidcompass-summary-2.0',
    version: '3.0.0',
    title: 'LipidCompass summary',
    description: 'Summary',
    mzTabVersions: ['2.0.0-M'],
  },
  {
    id: 'mztab-m-2.1-default',
    version: '1.0.0',
    title: 'mzTab-M 2.1 default',
    description: 'Default 2.1 rules',
    mzTabVersions: ['2.1.0-M'],
  },
];

const BOTH_ENGINES: AppInfoDto['engines'] = [
  { line: '2.0', mzTabVersions: ['2.0.0-M'], jmztabmVersion: '1.0.8', available: true },
  { line: '2.1', mzTabVersions: ['2.1.0-M'], jmztabmVersion: '2.1.0', available: true },
];
const INFO: AppInfoDto = {
  customMappingsEnabled: true,
  maxUploadBytes: 64 * 1048576,
  retentionDays: 7,
  engines: BOTH_ENGINES,
  defaultLine: '2.0',
};

describe('ValidatorFormComponent', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ValidatorFormComponent>;
  let el: HTMLElement;
  let navigate: ReturnType<typeof vi.spyOn>;

  async function render(info: AppInfoDto = INFO) {
    await TestBed.configureTestingModule({
      imports: [ValidatorFormComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(ValidatorFormComponent);
    el = fixture.nativeElement;
    http.expectOne((r) => r.url.endsWith('rest/v2/app-info')).flush(info);
    http.expectOne((r) => r.url.endsWith('rest/v2/validation-profiles')).flush(PROFILES);
    await fixture.whenStable();
  }

  async function choose(selector: string, file: File) {
    const input = el.querySelector<HTMLInputElement>(selector)!;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }
  const mzTab = () => new File(['MTD\tmzTab-version\t2.0.0-M'], 'a.mzTab');
  const mzTab21 = () => new File(['COM\tx\nMTD\tmzTab-version\t2.1.0-M\n'], 'b.mzTab');
  const noVersion = () => new File(['MTD\tmzTab-ID\tA\n'], 'c.mzTab');
  const submitButton = () => el.querySelector<HTMLButtonElement>('button[type=submit]')!;
  const select = () => el.querySelector('mat-select[data-testid=profile-select]')!;

  async function submit(): Promise<TestRequest> {
    submitButton().click();
    await fixture.whenStable();
    return http.expectOne((r) => r.url.endsWith('rest/v2/validations') && r.method === 'POST');
  }

  it('defaults to INFO, 100 and the default validation profile', async () => {
    await render();
    await choose('input[data-testid=file-input]', mzTab());
    const req = await submit();
    expect(req.request.params.get('level')).toBe('INFO');
    expect(req.request.params.get('maxErrors')).toBe('100');
    expect(req.request.params.get('validationProfile')).toBe('mztab-m-2.0-default');
    expect((req.request.body as FormData).get('file')).toBeTruthy();
    req.flush({ id: 'x' }, { status: 202, statusText: 'Accepted' });
  });

  it('rejects maxErrors outside 1..500', async () => {
    await render();
    await choose('input[data-testid=file-input]', mzTab());
    expect(submitButton().disabled).toBe(false);
    const input = el.querySelector<HTMLInputElement>('input[data-testid=max-errors]')!;
    for (const bad of ['0', '501', '']) {
      input.value = bad;
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      expect(submitButton().disabled).toBe(true);
    }
    input.value = '500';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(submitButton().disabled).toBe(false);
  });

  it('requires a file', async () => {
    await render();
    expect(submitButton().disabled).toBe(true);
  });

  it('choosing a mapping file disables the profile select and sends mappingFile only', async () => {
    await render();
    await choose('input[data-testid=file-input]', mzTab());
    await choose('input[data-testid=mapping-input]', new File(['<x/>'], 'map.xml'));
    expect(select().getAttribute('aria-disabled')).toBe('true');
    expect(select().textContent).toContain('Custom mapping file');
    const req = await submit();
    expect(req.request.params.has('validationProfile')).toBe(false);
    expect((req.request.body as FormData).get('mappingFile')).toBeTruthy();
    req.flush({ id: 'x' }, { status: 202, statusText: 'Accepted' });
  });

  it('clearing the mapping file re-enables the profile select', async () => {
    await render();
    await choose('input[data-testid=mapping-input]', new File(['<x/>'], 'map.xml'));
    el.querySelector<HTMLButtonElement>('[data-testid=remove-mapping]')!.click();
    fixture.detectChanges();
    expect(select().getAttribute('aria-disabled')).toBe('false');
  });

  it('hides the mapping upload when custom mappings are disabled', async () => {
    await render({ ...INFO, customMappingsEnabled: false, maxUploadBytes: 1048576 });
    expect(el.querySelector('input[data-testid=mapping-input]')).toBeNull();
  });

  it('navigates to /result/:id after 202', async () => {
    await render();
    await choose('input[data-testid=file-input]', mzTab());
    const req = await submit();
    expect(submitButton().disabled).toBe(true);
    req.flush({ id: 'abc-1' }, { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith(['/result', 'abc-1']);
  });

  it('shows the size message on 413', async () => {
    await render();
    await choose('input[data-testid=file-input]', mzTab());
    (await submit()).flush('', { status: 413, statusText: 'Payload Too Large' });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('The file is larger than 64 MB.');
    expect(submitButton().disabled).toBe(false);
  });

  describe('size limits', () => {
    const ONE_MB = { ...INFO, maxUploadBytes: 1048576 };
    const bytes = (n: number, name: string) => new File([new Uint8Array(n)], name);
    const alertText = () => el.querySelector('[role=alert]')?.textContent ?? '';
    const noUpload = () => http.expectNone((r) => r.url.endsWith('rest/v2/validations') && r.method === 'POST');

    it('rejects a chosen file over the upload limit without uploading', async () => {
      await render(ONE_MB);
      await choose('input[data-testid=file-input]', bytes(1048577, 'big.mzTab'));
      expect(alertText()).toContain('The file is larger than 1 MB.');
      expect(el.querySelector('.drop__name')).toBeNull();
      expect(submitButton().disabled).toBe(true);
      submitButton().click();
      await fixture.whenStable();
      noUpload();
    });

    it('rejects a dropped file over the upload limit', async () => {
      await render(ONE_MB);
      const drop = Object.assign(new Event('drop', { cancelable: true }), {
        dataTransfer: { files: [bytes(1048577, 'big.mzTab')] },
      });
      el.querySelector('.drop')!.dispatchEvent(drop);
      fixture.detectChanges();
      expect(alertText()).toContain('The file is larger than 1 MB.');
      expect(fixture.componentInstance.file()).toBeNull();
    });

    it('accepts a file of exactly the upload limit', async () => {
      await render(ONE_MB);
      await choose('input[data-testid=file-input]', bytes(1048576, 'ok.mzTab'));
      expect(alertText()).toBe('');
      const req = await submit();
      req.flush({ id: 'x' }, { status: 202, statusText: 'Accepted' });
    });

    it('checks the upload limit again on submit', async () => {
      await render(ONE_MB);
      fixture.componentInstance.file.set(bytes(1048577, 'big.mzTab'));
      fixture.detectChanges();
      submitButton().click();
      await fixture.whenStable();
      fixture.detectChanges();
      noUpload();
      expect(alertText()).toContain('The file is larger than 1 MB.');
    });

    it('rejects a mapping file over 5 MB without uploading', async () => {
      await render();
      await choose('input[data-testid=file-input]', mzTab());
      await choose('input[data-testid=mapping-input]', bytes(5 * 1048576 + 1, 'big.xml'));
      expect(alertText()).toContain('The mapping file is larger than 5 MB.');
      expect(select().getAttribute('aria-disabled')).toBe('false');
      expect(fixture.componentInstance.mappingFile()).toBeNull();
    });

    it('checks the mapping size again on submit', async () => {
      await render();
      await choose('input[data-testid=file-input]', mzTab());
      fixture.componentInstance.mappingFile.set(bytes(5 * 1048576 + 1, 'big.xml'));
      fixture.detectChanges();
      submitButton().click();
      await fixture.whenStable();
      fixture.detectChanges();
      noUpload();
      expect(alertText()).toContain('The mapping file is larger than 5 MB.');
    });
  });

  it('shows the server message on 400 and a generic one on a non-JSON 500', async () => {
    await render();
    await choose('input[data-testid=file-input]', mzTab());
    (await submit()).flush(
      { status: 400, error: 'Bad Request', message: 'Unknown profile', path: '/x' },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Unknown profile');
    (await submit()).flush('<html>boom</html>', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Something went wrong. Please try again.');
  });

  describe('mzTab-M versions', () => {
    const badge = () => el.querySelector('[data-testid=version-badge]')?.textContent?.trim() ?? '';
    const callout = () => el.querySelector<HTMLElement>('.lifs-callout--warn[role=status]');

    /** Opens the profile select and returns the offered option values. */
    async function offered(): Promise<string[]> {
      el.querySelector<HTMLElement>('mat-select[data-testid=profile-select] .mat-mdc-select-trigger')!.click();
      fixture.detectChanges();
      await fixture.whenStable();
      const options = Array.from(document.querySelectorAll('mat-option')).map((o) => o.textContent?.trim() ?? '');
      document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      return options;
    }

    it('offers only the profiles of the detected line and preselects its default', async () => {
      await render();
      expect(await offered()).toEqual(['mzTab-M 2.0 default', 'LipidCompass summary', 'None — structural validation only']);
      await choose('input[data-testid=file-input]', mzTab21());
      expect(badge()).toBe('mzTab-M 2.1');
      expect(select().textContent).toContain('mzTab-M 2.1 default');
      expect(await offered()).toEqual(['mzTab-M 2.1 default', 'None — structural validation only']);
      expect(el.textContent).toContain('Default 2.1 rules');
      const req = await submit();
      expect(req.request.params.get('validationProfile')).toBe('mztab-m-2.1-default');
      req.flush({ id: 'x' }, { status: 202, statusText: 'Accepted' });
    });

    it('resets the choice when the line changes', async () => {
      await render();
      await choose('input[data-testid=file-input]', mzTab());
      expect(badge()).toBe('mzTab-M 2.0');
      fixture.componentInstance.validationProfile.set(null);
      fixture.detectChanges();
      expect(select().textContent).toContain('None');
      await choose('input[data-testid=file-input]', mzTab21());
      expect(select().textContent).toContain('mzTab-M 2.1 default');
      await choose('input[data-testid=file-input]', mzTab());
      expect(select().textContent).toContain('mzTab-M 2.0 default');
    });

    it('selects None when the line has no default profile', async () => {
      await render();
      await choose('input[data-testid=file-input]', new File(['MTD\tmzTab-version\t2.2.0-M\n'], 'd.mzTab'));
      expect(badge()).toBe('mzTab-M 2.2');
      expect(select().textContent).toContain('None');
      expect(await offered()).toEqual(['None — structural validation only']);
      const req = await submit();
      expect(req.request.params.has('validationProfile')).toBe(false);
      req.flush({ id: 'x' }, { status: 202, statusText: 'Accepted' });
    });

    it('falls back to the default line for a file without version', async () => {
      await render();
      await choose('input[data-testid=file-input]', noVersion());
      expect(badge()).toBe('No version declared · validated as mzTab-M 2.0');
      expect(select().textContent).toContain('mzTab-M 2.0 default');
      expect(callout()).toBeNull();
    });

    it('takes the default line from app info', async () => {
      await render({ ...INFO, defaultLine: '2.1' });
      expect(select().textContent).toContain('mzTab-M 2.1 default');
      await choose('input[data-testid=file-input]', noVersion());
      expect(badge()).toBe('No version declared · validated as mzTab-M 2.1');
    });

    it('detects the version of a JSON file', async () => {
      await render();
      await choose(
        'input[data-testid=file-input]',
        new File(['{"smallMoleculeSummary":[],"metadata":{"mzTab-version":"2.1.0-M"}}'], 'e.json'),
      );
      expect(badge()).toBe('mzTab-M 2.1');
    });

    it('warns when the line has no engine', async () => {
      await render({ ...INFO, engines: [BOTH_ENGINES![0]] });
      await choose('input[data-testid=file-input]', mzTab21());
      expect(callout()?.textContent).toContain('mzTab-M 2.1 is not supported by this validator.');
      expect(callout()?.querySelector('a')).toBeNull();
      expect(submitButton().disabled).toBe(false);
      await choose('input[data-testid=file-input]', mzTab());
      expect(callout()).toBeNull();
    });

    it('warns when the engine of the line is unavailable, and links the hint URL', async () => {
      await render({
        ...INFO,
        engines: [BOTH_ENGINES![0], { ...BOTH_ENGINES![1], available: false }],
        unavailableHintUrl: 'https://dev.example.org/mztabvalidator/',
      });
      await choose('input[data-testid=file-input]', mzTab21());
      expect(callout()?.textContent).toContain('mzTab-M 2.1 is not supported by this validator.');
      const link = callout()!.querySelector<HTMLAnchorElement>('a')!;
      expect(link.textContent?.trim()).toBe('It is available for testing on the development validator.');
      expect(link.getAttribute('href')).toBe('https://dev.example.org/mztabvalidator/');
      expect(submitButton().disabled).toBe(false);
    });

    it('removing the file removes the badge and returns to the default line', async () => {
      await render();
      await choose('input[data-testid=file-input]', mzTab21());
      el.querySelector<HTMLButtonElement>('[data-testid=remove-file]')!.click();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(el.querySelector('[data-testid=version-badge]')).toBeNull();
      expect(select().textContent).toContain('mzTab-M 2.0 default');
    });
  });

  it('validateExample of another line than the chosen file uses that line\'s default profile', async () => {
    await render();
    await choose('input[data-testid=file-input]', mzTab21());
    fixture.componentInstance.validateExample('ex-2', '2.0.0-M');
    const req = http.expectOne((r) => r.url.endsWith('rest/v2/examples/ex-2/validations'));
    expect(req.request.body.validationProfile).toBe('mztab-m-2.0-default');
  });

  it('validateExample of the active line keeps the chosen profile', async () => {
    await render();
    fixture.componentInstance.validationProfile.set('lipidcompass-summary-2.0');
    fixture.componentInstance.validateExample('ex-3', '2.0.0-M');
    const req = http.expectOne((r) => r.url.endsWith('rest/v2/examples/ex-3/validations'));
    expect(req.request.body.validationProfile).toBe('lipidcompass-summary-2.0');
  });

  it('validateExample posts the form values as JSON and navigates', async () => {
    await render();
    fixture.componentInstance.validateExample('ex-1');
    const req = http.expectOne((r) => r.url.endsWith('rest/v2/examples/ex-1/validations'));
    expect(req.request.body).toEqual({ level: 'INFO', maxErrors: 100, validationProfile: 'mztab-m-2.0-default' });
    req.flush({ id: 'r1' }, { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith(['/result', 'r1']);
  });
});
