import { BreakpointObserver } from '@angular/cdk/layout';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { map, Subject } from 'rxjs';

import { DocsShellComponent } from './docs-shell.component';

@Component({ template: '<p>Page text</p>' })
class Blank {}

describe('DocsShellComponent', () => {
  /** What the BreakpointObserver stub reports for '(min-width: 1100px)'; nothing until a test says so. */
  let screen: Subject<boolean>;
  let queries: string[];

  beforeEach(() => {
    screen = new Subject();
    queries = [];
  });

  async function open(url: string) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'docs',
            component: DocsShellComponent,
            children: [
              { path: '', component: Blank },
              { path: 'fixing', component: Blank },
              { path: 'problems', component: Blank },
            ],
          },
        ]),
        {
          provide: BreakpointObserver,
          useValue: {
            observe: (q: string) => {
              queries.push(q);
              return screen.pipe(map((matches) => ({ matches, breakpoints: {} })));
            },
          },
        },
      ],
    });
    const harness = await RouterTestingHarness.create(url);
    return { harness, el: harness.routeNativeElement! };
  }

  it('shows the page title as its heading', async () => {
    const { el } = await open('/docs/problems');
    expect(el.querySelector('h1')?.textContent?.trim()).toBe('Common problems');
    expect(el.textContent).toContain('Page text');
  });

  it('marks the current page in the side navigation', async () => {
    const { el } = await open('/docs/fixing');
    const nav = el.querySelector('nav[aria-label="Documentation"]')!;
    const current = Array.from(nav.querySelectorAll('a[aria-current="page"]'));
    expect(current.map((a) => a.getAttribute('href'))).toEqual(['/docs/fixing']);
    expect(Array.from(nav.querySelectorAll('a')).map((a) => a.textContent?.trim())).toEqual([
      'Documentation',
      'Fixing a file',
      'Common problems',
    ]);
  });

  it('shows previous and next links in tree order', async () => {
    const { el, harness } = await open('/docs/fixing');
    const pager = () => el.querySelector('nav[aria-label="Previous and next page"]')!;
    const prev = pager().querySelector<HTMLAnchorElement>('a[rel=prev]');
    const next = pager().querySelector<HTMLAnchorElement>('a[rel=next]');
    expect(prev?.getAttribute('href')).toBe('/docs');
    expect(prev?.textContent).toContain('Documentation');
    expect(next?.getAttribute('href')).toBe('/docs/problems');
    expect(next?.textContent).toContain('Common problems');

    await harness.navigateByUrl('/docs/problems');
    expect(pager().querySelector('a[rel=prev]')?.getAttribute('href')).toBe('/docs/fixing');
    expect(pager().querySelector('a[rel=next]')).toBeNull();
  });

  it('collapses the side navigation below 1100 px', async () => {
    const { el, harness } = await open('/docs/fixing');
    const nav = () => el.querySelector('nav[aria-label="Documentation"]')!;
    expect(nav().querySelector('ul')).toBeTruthy();
    expect(nav().querySelector('button')).toBeNull();

    // delivered after creation: the layout must follow the observer, not its first reading
    screen.next(false);
    await harness.fixture.whenStable();
    const contents = nav().querySelector<HTMLButtonElement>('button');
    expect(contents?.textContent?.trim()).toBe('Contents');
    expect(contents?.getAttribute('aria-expanded')).toBe('false');
    expect(nav().querySelector('ul')).toBeNull();

    contents!.click();
    await harness.fixture.whenStable();
    expect(contents?.getAttribute('aria-expanded')).toBe('true');
    expect(nav().querySelector('a[aria-current="page"]')?.getAttribute('href')).toBe(
      '/docs/fixing',
    );

    screen.next(true);
    await harness.fixture.whenStable();
    expect(nav().querySelector('button')).toBeNull();
    // one query, observed once per component
    expect(new Set(queries)).toEqual(new Set(['(min-width: 1100px)']));
  });
});
