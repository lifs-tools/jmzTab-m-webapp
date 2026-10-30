import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { ExampleDto } from '../../api/models/example-dto';
import { LandingPage } from './landing.page';

const EXAMPLES: ExampleDto[] = [
  {
    id: 'lip',
    name: 'lipidomics-example.mzTab',
    section: 'example',
    focus: 'Lipidomics',
    mzTabVersion: '2.0.0-M',
    uri: 'https://example.org/lip.mzTab',
  },
  {
    id: 'mf21',
    name: 'official-profile-mf.mztab',
    section: 'example',
    mzTabVersion: '2.1.0-M',
    uri: 'https://example.org/mf21.mztab',
  },
  {
    id: 'map',
    name: 'mapping.xml',
    section: 'mapping',
    description: 'A mapping',
    mzTabVersion: '2.0.0-M',
    uri: 'https://example.org/map.xml',
  },
];

describe('LandingPage', () => {
  async function render(examples: ExampleDto[] = EXAMPLES, defaultLine = '2.0') {
    await TestBed.configureTestingModule({
      imports: [LandingPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const http = TestBed.inject(HttpTestingController);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(LandingPage);
    http
      .expectOne((r) => r.url.endsWith('rest/v2/app-info'))
      .flush({ retentionDays: 7, maxUploadBytes: 67108864, customMappingsEnabled: true, defaultLine });
    http.expectOne((r) => r.url.endsWith('rest/v2/validation-profiles')).flush([
      { id: 'mztab-m-2.0-default', version: '1.1.0', title: '2.0 rules', mzTabVersions: ['2.0.0-M'] },
      { id: 'mztab-m-2.1-default', version: '1.0.0', title: '2.1 rules', mzTabVersions: ['2.1.0-M'] },
    ]);
    http.expectOne((r) => r.url.endsWith('rest/v2/examples')).flush(examples);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement, http, navigate };
  }

  it('shows the hero copy and retention days from app info', async () => {
    const { el } = await render();
    expect(el.querySelector('.lifs-eyebrow')?.textContent).toContain('LIFS Tools · Report & share');
    expect(el.querySelector('h1')?.textContent).toContain('Validate your mzTab-M files');
    expect(el.textContent).toContain(
      'Structural and semantic validation with jmzTab-m, the mzTab-M reference implementation.',
    );
    expect(el.textContent).toContain('Results are kept for 7 days, then deleted.');
  });

  it('lists examples with download links; mapping examples have no Validate action', async () => {
    const { el } = await render();
    const link = el.querySelector<HTMLAnchorElement>('a[href="https://example.org/lip.mzTab"]')!;
    expect(link.target).toBe('_blank');
    expect(link.rel).toContain('noopener');
    const map = el.querySelector<HTMLAnchorElement>('a[href="https://example.org/map.xml"]')!;
    expect(map.hasAttribute('download')).toBe(true);
    expect(map.target).toBe('_blank');
    expect(map.rel).toContain('noopener');
    const validateButtons = Array.from(el.querySelectorAll('app-examples-card button')).filter(
      (b) => b.textContent?.trim() === 'Validate',
    );
    expect(validateButtons.length).toBe(1);
    expect(el.querySelector('a[href="/examples"]')).toBeTruthy();
  });

  it('shows at most 6 examples', async () => {
    const many = Array.from({ length: 9 }, (_, i) => ({
      id: `e${i}`,
      name: `f${i}.mzTab`,
      section: 'example',
      mzTabVersion: '2.0.0-M',
      uri: `https://x/${i}`,
    }));
    const { el } = await render(many);
    expect(el.querySelectorAll('app-examples-card .example').length).toBe(6);
  });

  it('Validate on an example posts to examples/{id}/validations and navigates', async () => {
    const { el, http, fixture, navigate } = await render();
    const btn = Array.from(el.querySelectorAll<HTMLButtonElement>('app-examples-card button')).find(
      (b) => b.textContent?.trim() === 'Validate',
    )!;
    btn.click();
    const req = http.expectOne((r) => r.url.endsWith('rest/v2/examples/lip/validations'));
    expect(req.request.method).toBe('POST');
    req.flush({ id: 'r9' }, { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith(['/result', 'r9']);
  });

  it('shows only examples of the default line, with a link to all examples', async () => {
    const { el } = await render();
    expect(el.querySelector('a[href="https://example.org/lip.mzTab"]')).toBeTruthy();
    expect(el.querySelector('a[href="https://example.org/mf21.mztab"]')).toBeNull();
    expect(el.querySelector('a[href="/examples"]')?.textContent).toContain('All examples');
  });

  it('follows the default line of app info', async () => {
    const { el } = await render(EXAMPLES, '2.1');
    expect(el.querySelector('a[href="https://example.org/mf21.mztab"]')).toBeTruthy();
    expect(el.querySelector('a[href="https://example.org/lip.mzTab"]')).toBeNull();
    expect(el.querySelector('a[href="https://example.org/map.xml"]')).toBeNull();
  });

  it('Validate on an example sends the default profile of its line', async () => {
    const { el, http } = await render(EXAMPLES, '2.1');
    const btn = Array.from(el.querySelectorAll<HTMLButtonElement>('app-examples-card button')).find(
      (b) => b.textContent?.trim() === 'Validate',
    )!;
    btn.click();
    const req = http.expectOne((r) => r.url.endsWith('rest/v2/examples/mf21/validations'));
    expect(req.request.body.validationProfile).toBe('mztab-m-2.1-default');
  });

  it('links the scripting resources', async () => {
    const { el } = await render();
    expect(el.querySelector<HTMLAnchorElement>('a[href="swagger-ui/index.html"]')?.target).toBe('_blank');
    expect(el.querySelector('a[href="https://github.com/lifs-tools/pymzTab-m"]')).toBeTruthy();
    expect(el.querySelector('a[href="https://github.com/lifs-tools/rmzTab-m"]')).toBeTruthy();
  });

  it('caps 7 examples at 6 and never gives mapping entries a Validate action', async () => {
    const entries: ExampleDto[] = [
      { id: 'm1', name: 'm1.xml', section: 'mapping', mzTabVersion: '2.0.0-M', uri: 'https://x/m1' },
      { id: 'm2', name: 'm2.xml', section: 'mapping', mzTabVersion: '2.0.0-M', uri: 'https://x/m2' },
      ...Array.from({ length: 7 }, (_, i) => ({
        id: `e${i}`,
        name: `f${i}.mzTab`,
        section: 'example',
        mzTabVersion: '2.0.0-M',
        uri: `https://x/${i}`,
      })),
    ];
    const { el } = await render(entries);
    const items = Array.from(el.querySelectorAll('app-examples-card .example'));
    const withValidate = items.filter((li) => li.querySelector('button'));
    expect(withValidate.length).toBe(6);
    expect(withValidate.map((li) => li.querySelector('a')?.textContent)).not.toContain('m1.xml');
    expect(items.length).toBe(8);
    expect(el.querySelector('a[href="https://x/m1"]')?.closest('li')?.querySelector('button')).toBeNull();
    expect(el.querySelector('a[href="https://x/6"]')).toBeNull();
  });
});
