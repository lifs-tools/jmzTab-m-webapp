import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppBarComponent } from './app-bar.component';

describe('AppBarComponent', () => {
  async function render(): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [AppBarComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(AppBarComponent);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the five nav items in order', async () => {
    const el = await render();
    const items = Array.from(el.querySelectorAll('.nav__item')).map((a) => a.textContent?.trim());
    expect(items).toEqual(['Validate', 'Examples', 'REST API', 'About', 'LIFS Tools ↗']);
  });

  it('opens the REST API and external links in a new tab', async () => {
    const el = await render();
    const rest = el.querySelector<HTMLAnchorElement>('a.nav__item[href="swagger-ui/index.html"]');
    expect(rest?.target).toBe('_blank');
    expect(rest?.rel).toContain('noopener');
    const gh = el.querySelector<HTMLAnchorElement>(
      'a[href="https://github.com/lifs-tools/jmzTab-m-webapp"]',
    );
    expect(gh?.target).toBe('_blank');
    expect(gh?.getAttribute('aria-label')).toBeTruthy();
  });

  it('shows the app name with LIFS Tools and the logo', async () => {
    const el = await render();
    expect(el.querySelector('.brand__name')?.textContent).toContain('mzTab-M Validator');
    expect(el.querySelector('.brand__name')?.textContent).toContain('LIFS Tools');
    expect(el.querySelector('img')?.getAttribute('src')).toBe('brand/lifs-bildmarke.png');
  });

  it('has a menu button for narrow screens', async () => {
    const el = await render();
    expect(el.querySelector('button.menu-button')?.getAttribute('aria-label')).toBe(
      'Open navigation menu',
    );
  });
});
