import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { pageTitle } from '../../core/page-titles';
import { DocsHomePage } from './docs-home.page';
import { DOCS_TREE } from './docs-tree';

describe('DocsHomePage', () => {
  it('links every chapter', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(DocsHomePage);
    await fixture.whenStable();
    const links = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a'));
    expect(links.map((a) => [a.getAttribute('href'), a.textContent?.trim()])).toEqual(
      DOCS_TREE.map((n) => [n.path, n.title]),
    );
  });

  it('has a fixed page title', () => {
    expect(pageTitle('/docs')).toBe('mzTab-M Validator · Documentation');
  });
});
