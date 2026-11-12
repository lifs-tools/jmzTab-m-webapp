import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { pageTitle } from '../../core/page-titles';
import { DocsHomePage } from './docs-home.page';
import { DOCS_TREE } from './docs-tree';

describe('DocsHomePage', () => {
  async function render(): Promise<HTMLElement> {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(DocsHomePage);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('explains what mzTab-M is, its uses and audiences', async () => {
    const el = await render();
    const headings = Array.from(el.querySelectorAll('h2')).map((h) => h.textContent?.trim());
    expect(headings.slice(0, 3)).toEqual(['What it is', 'What it is for', 'Who uses it']);
    expect(el.textContent).toContain('The HUPO-PSI standard for reporting the results');
  });

  it('describes mzTab-M and jmzTab-m, with the old About ids', async () => {
    const el = await render();
    const section = el.querySelector('#about-formats')!;
    expect(section.querySelector('h2')?.id).toBe('formats');
    expect(section.querySelector('a[href="https://github.com/HUPO-PSI/mzTab"]')).toBeTruthy();
    expect(section.querySelector('a[href="https://github.com/lifs-tools/jmzTab-m"]')).toBeTruthy();
    expect(section.textContent).toContain('mzTab 1.0 validation was retired in version 2.0.0.');
  });

  it('links every chapter', async () => {
    const el = await render();
    const links = Array.from(el.querySelectorAll('.chapters a'));
    expect(links.map((a) => [a.getAttribute('href'), a.textContent?.trim()])).toEqual(
      DOCS_TREE.map((n) => [n.path, n.title]),
    );
  });

  it('puts every link in the page body into prose, so it is underlined', async () => {
    const el = await render();
    const links = Array.from(el.querySelectorAll('.page a:not(.chapter)'));
    expect(links.length).toBeGreaterThan(0);
    expect(links.every((a) => a.closest('.lifs-prose') !== null)).toBe(true);
  });

  it('has a fixed page title', () => {
    expect(pageTitle('/docs')).toBe('mzTab-M Validator · Documentation');
  });
});
