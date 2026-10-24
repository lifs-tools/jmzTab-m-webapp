import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, provideRouter } from '@angular/router';

import { ExampleDto } from '../../api/models/example-dto';
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
    uri: 'https://example.org/lip.mzTab',
  },
  { id: 'map', name: 'mapping.xml', section: 'mapping', description: 'A mapping', uri: 'https://example.org/map.xml' },
];

describe('ExamplesPage', () => {
  async function render() {
    await TestBed.configureTestingModule({
      imports: [ExamplesPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const http = TestBed.inject(HttpTestingController);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(ExamplesPage);
    http.expectOne((r) => r.url.endsWith('rest/v2/examples')).flush(EXAMPLES);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement, http, navigate };
  }

  function validateButton(el: HTMLElement): HTMLButtonElement {
    return Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => b.textContent?.trim() === 'Validate',
    )!;
  }

  it('groups example files and mapping files', async () => {
    const { el } = await render();
    const headings = Array.from(el.querySelectorAll('h2')).map((h) => h.textContent?.trim());
    expect(headings).toEqual(['Example files', 'Example mapping files']);
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
    expect(buttons.length).toBe(1);
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
