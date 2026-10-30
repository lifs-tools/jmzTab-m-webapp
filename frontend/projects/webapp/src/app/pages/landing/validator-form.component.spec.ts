import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AppInfoDto } from '../../api/models/app-info-dto';
import { ValidationProfile } from '../../api/models/validation-profile';
import { ValidatorFormComponent } from './validator-form.component';

const PROFILES: ValidationProfile[] = [
  { id: 'mztab-m-2.0-default', version: '1', title: 'mzTab-M 2.0 default', description: 'Default rules' },
  { id: 'lipidcompass-summary-2.0', version: '3', title: 'LipidCompass summary', description: 'Summary' },
];

describe('ValidatorFormComponent', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ValidatorFormComponent>;
  let el: HTMLElement;
  let navigate: ReturnType<typeof vi.spyOn>;

  async function render(
    info: AppInfoDto = { customMappingsEnabled: true, maxUploadBytes: 64 * 1048576, retentionDays: 7 },
  ) {
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
    await render({ customMappingsEnabled: false, maxUploadBytes: 1048576 });
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
    const ONE_MB = { customMappingsEnabled: true, maxUploadBytes: 1048576, retentionDays: 7 };
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
