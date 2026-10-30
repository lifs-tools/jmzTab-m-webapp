import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, provideRouter } from '@angular/router';

import { AppInfoDto } from '../../api/models/app-info-dto';
import { ExampleDto } from '../../api/models/example-dto';
import { ValidationProfile } from '../../api/models/validation-profile';
import { httpErrorInterceptor } from '../../core/http-error.interceptor';
import { ExamplesPage } from './examples.page';

const EXAMPLES: ExampleDto[] = [
  {
    id: 'lip',
    name: 'lipidomics.mzTab',
    section: 'example',
    focus: 'Lipidomics',
    description: 'Lipids',
    createdBy: 'Jane',
    version: '2.0.0',
    mzTabVersion: '2.0.0-M',
    uri: 'https://example.org/lip.mzTab',
  },
  {
    id: 'mf21',
    name: 'official-profile-mf.mztab',
    section: 'example',
    focus: 'Metabolomics',
    description: 'Profile MF',
    createdBy: 'jmzTab-m',
    version: '2.1',
    mzTabVersion: '2.1.0-M',
    uri: 'https://example.org/mf21.mztab',
  },
  {
    id: 'map',
    name: 'mapping.xml',
    section: 'mapping',
    description: 'A mapping',
    version: 'DRAFT',
    mzTabVersion: '2.0.0-M',
    uri: 'https://example.org/map.xml',
  },
];

const PROFILES: ValidationProfile[] = [
  {
    id: 'mztab-m-2.0-default',
    version: '1.1.0',
    title: 'mzTab-M 2.0 default rules',
    description: 'The 2.0 rules.',
    mzTabVersions: ['2.0.0-M'],
  },
  {
    id: 'mztab-m-2.1-default',
    version: '1.0.0',
    title: 'mzTab-M 2.1 default rules',
    description: 'The 2.1 rules.',
    mzTabVersions: ['2.1.0-M'],
  },
];

const INFO: AppInfoDto = {
  defaultLine: '2.0',
  engines: [
    { line: '2.0', mzTabVersions: ['2.0.0-M'], jmztabmVersion: '1.0.8', available: true },
    { line: '2.1', mzTabVersions: ['2.1.0-M'], jmztabmVersion: '2.1.0', available: true },
    { line: '2.2', mzTabVersions: ['2.2.0-M'], available: false },
  ],
};

describe('ExamplesPage', () => {
  async function render(info: AppInfoDto = INFO) {
    await TestBed.configureTestingModule({
      imports: [ExamplesPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const http = TestBed.inject(HttpTestingController);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(ExamplesPage);
    http.expectOne((r) => r.url.endsWith('rest/v2/app-info')).flush(info);
    http.expectOne((r) => r.url.endsWith('rest/v2/validation-profiles')).flush(PROFILES);
    http.expectOne((r) => r.url.endsWith('rest/v2/examples')).flush(EXAMPLES);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement, http, navigate };
  }

  function validateButton(el: HTMLElement, index = 0): HTMLButtonElement {
    return Array.from(el.querySelectorAll<HTMLButtonElement>('button')).filter(
      (b) => b.textContent?.trim() === 'Validate',
    )[index];
  }

  const exampleNames = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('[data-testid=example-files] .item a')).map((a) => a.textContent?.trim());
  const mappingItems = (el: HTMLElement) =>
    Array.from(el.querySelectorAll<HTMLElement>('[data-testid=mapping-files] .item'));

  it('filters examples by version', async () => {
    const { el, fixture } = await render();
    const toggles = Array.from(el.querySelectorAll<HTMLButtonElement>('mat-button-toggle button'));
    expect(toggles.map((t) => t.textContent?.trim())).toEqual(['All', '2.1', '2.0']);
    expect(el.querySelector('mat-button-toggle-group')?.getAttribute('aria-label')).toBe('mzTab-M version');
    expect(exampleNames(el)).toEqual(['lipidomics.mzTab', 'official-profile-mf.mztab']);
    expect(el.textContent).toContain('mzTab-M 2.1.0-M');

    toggles[1].click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(exampleNames(el)).toEqual(['official-profile-mf.mztab']);
    expect(mappingItems(el).map((li) => li.querySelector('a')?.textContent?.trim())).toEqual([
      'mzTab-M 2.1 default rules',
    ]);

    toggles[2].click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(exampleNames(el)).toEqual(['lipidomics.mzTab']);
    expect(mappingItems(el).length).toBe(2);

    toggles[0].click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(exampleNames(el).length).toBe(2);
  });

  it('shows a version badge on every entry', async () => {
    const { el } = await render();
    const items = Array.from(el.querySelectorAll<HTMLElement>('.item'));
    expect(items.length).toBe(5);
    for (const item of items) {
      expect(item.querySelector('[data-testid=version]')?.textContent).toMatch(/^mzTab-M 2\.[01]\.0-M$/);
    }
  });

  it('lists catalog profiles before draft mappings under "Mapping files"', async () => {
    const { el } = await render();
    const card = el.querySelector('[data-testid=mapping-files]')!;
    expect(card.querySelector('h2')?.textContent?.trim()).toBe('Mapping files');
    const items = mappingItems(el);
    expect(items.map((li) => li.querySelector('a')?.textContent?.trim())).toEqual([
      'mzTab-M 2.0 default rules',
      'mzTab-M 2.1 default rules',
      'mapping.xml',
    ]);
    const download = items[1].querySelector<HTMLAnchorElement>('a')!;
    expect(download.getAttribute('href')).toBe('rest/v2/validation-profiles/mztab-m-2.1-default/1.0.0/mapping');
    expect(download.hasAttribute('download')).toBe(true);
    expect(items[1].textContent).toContain('The 2.1 rules.');
    expect(items[1].textContent).toContain('1.0.0');
    expect(items[2].textContent).toContain('DRAFT');
  });

  it('Validate on a 2.1 example uses the 2.1 default profile', async () => {
    const { el, http } = await render();
    validateButton(el, 1).click();
    const req = http.expectOne((r) => r.url.endsWith('rest/v2/examples/mf21/validations'));
    expect(req.request.body).toEqual({ level: 'INFO', maxErrors: 100, validationProfile: 'mztab-m-2.1-default' });
  });

  it('groups example files and mapping files', async () => {
    const { el } = await render();
    const headings = Array.from(el.querySelectorAll('h2')).map((h) => h.textContent?.trim());
    expect(headings).toEqual(['Example files', 'Mapping files']);
    const link = el.querySelector<HTMLAnchorElement>('a[href="https://example.org/lip.mzTab"]')!;
    expect(link.target).toBe('_blank');
    expect(link.rel).toContain('noopener');
    expect(el.textContent).toContain('Lipidomics');
    expect(el.textContent).toContain('Jane');
    expect(el.textContent).toContain('2.0.0');
    const map = el.querySelector<HTMLAnchorElement>('a[href="https://example.org/map.xml"]')!;
    expect(map.hasAttribute('download')).toBe(true);
    expect(map.target).toBe('_blank');
    expect(map.rel).toContain('noopener');
    const buttons = Array.from(el.querySelectorAll('button')).filter((b) => b.textContent?.trim() === 'Validate');
    expect(buttons.length).toBe(2);
  });

  it('Validate posts with the default options and opens the result', async () => {
    const { el, http, fixture, navigate } = await render();
    validateButton(el).click();
    const req = http.expectOne((r) => r.url.endsWith('rest/v2/examples/lip/validations'));
    expect(req.request.body).toEqual({ level: 'INFO', maxErrors: 100, validationProfile: 'mztab-m-2.0-default' });
    req.flush({ id: 'abc' });
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith(['/result', 'abc']);
  });

  it('shows an inline error when validation fails', async () => {
    const { el, http, fixture } = await render();
    validateButton(el).click();
    http
      .expectOne((r) => r.url.endsWith('rest/v2/examples/lip/validations'))
      .flush('x', { status: 500, statusText: 'err' });
    await fixture.whenStable();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Something went wrong. Please try again.');
  });

  it('a failing examples list shows the inline message and no snackbar', async () => {
    const open = vi.fn();
    await TestBed.configureTestingModule({
      imports: [ExamplesPage],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([httpErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ExamplesPage);
    TestBed.inject(HttpTestingController)
      .expectOne((r) => r.url.endsWith('rest/v2/examples'))
      .flush('x', { status: 500, statusText: 'err' });
    await fixture.whenStable();
    await import('@angular/material/snack-bar');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('could not be loaded');
    expect(open).not.toHaveBeenCalled();
  });
});
