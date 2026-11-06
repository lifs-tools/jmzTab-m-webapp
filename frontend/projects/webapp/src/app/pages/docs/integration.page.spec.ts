import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { IntegrationPage } from './integration.page';

describe('IntegrationPage', () => {
  async function render() {
    await TestBed.configureTestingModule({
      imports: [IntegrationPage],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(IntegrationPage);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const link = (el: HTMLElement, href: string) => el.querySelector<HTMLAnchorElement>(`a[href="${href}"]`);

  it('has the sections REST API, libraries and self-hosting', async () => {
    const el = await render();
    expect(Array.from(el.querySelectorAll('h2')).map((h) => [h.id, h.textContent?.trim()])).toEqual([
      ['rest-api', 'REST API'],
      ['libraries', 'Libraries for Python and R'],
      ['self-hosting', 'Self-hosting'],
    ]);
  });

  it('links the Swagger UI under the context path', async () => {
    const el = await render();
    // relative, so that it resolves under <base href>
    const swagger = link(el, 'swagger-ui/index.html');
    expect(swagger).toBeTruthy();
    expect(swagger?.closest('section')?.querySelector('h2')?.id).toBe('rest-api');
  });

  it('links pymzTab-m and rmzTab-m', async () => {
    const el = await render();
    expect(link(el, 'https://github.com/lifs-tools/pymzTab-m')?.textContent?.trim()).toBe('pymzTab-m');
    expect(link(el, 'https://github.com/lifs-tools/rmzTabM')?.textContent?.trim()).toBe('rmzTab-m');
  });

  it('links the self-hosting documentation on GitHub', async () => {
    const el = await render();
    const docs = link(el, 'https://github.com/lifs-tools/jmzTab-m-webapp/tree/master/docs/self-hosting');
    expect(docs).toBeTruthy();
    expect(docs?.getAttribute('target')).toBe('_blank');
    expect(docs?.getAttribute('rel')).toBe('noopener');
  });
});
