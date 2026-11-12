import { BreakpointObserver } from '@angular/cdk/layout';
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { map, Subject } from 'rxjs';

import { DocsTocComponent } from './docs-toc.component';

@Component({ template: '' })
class Blank {}

describe('DocsTocComponent', () => {
  /** What the BreakpointObserver stub reports for '(min-width: 1100px)'; nothing until a test says so. */
  let screen: Subject<boolean>;
  let base: HTMLBaseElement;
  let content: HTMLElement;

  beforeEach(() => {
    screen = new Subject();
    // the app runs under a servlet context path
    base = document.createElement('base');
    base.href = '/mztabvalidator/';
    document.head.prepend(base);
    content = document.createElement('div');
    content.innerHTML =
      '<h2 id="a">First</h2><p>Text</p><h3 id="b">Second</h3><h2>Not linkable</h2>';
    document.body.append(content);
  });

  afterEach(() => {
    base.remove();
    content.remove();
  });

  async function render(): Promise<ComponentFixture<DocsTocComponent>> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'docs/fixing', component: Blank }]),
        {
          provide: BreakpointObserver,
          useValue: {
            observe: () => screen.pipe(map((matches) => ({ matches, breakpoints: {} }))),
          },
        },
      ],
    });
    await TestBed.inject(Router).navigateByUrl('/docs/fixing');
    const fixture = TestBed.createComponent(DocsTocComponent);
    fixture.componentRef.setInput('container', content);
    await fixture.whenStable();
    return fixture;
  }

  const links = (f: ComponentFixture<DocsTocComponent>) =>
    Array.from((f.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>('a'));

  it('lists the h2 and h3 sections with ids', async () => {
    const f = await render();
    expect(links(f).map((a) => a.textContent?.trim())).toEqual(['First', 'Second']);
    expect((f.nativeElement as HTMLElement).querySelector('nav')?.getAttribute('aria-label')).toBe(
      'On this page',
    );
  });

  it('toc links keep the route and fragment', async () => {
    const f = await render();
    // a bare #a would resolve against <base href> to the start page
    expect(links(f)[0].getAttribute('href')).toMatch(/\/docs\/fixing#a$/);
  });

  it('lists sections that are rendered later', async () => {
    const f = await render();
    content.insertAdjacentHTML('beforeend', '<h2 id="late">Later</h2>');
    await vi.waitFor(() => expect(links(f).map((a) => a.textContent?.trim())).toContain('Later'));
  });

  it('shows nothing for a page without sections', async () => {
    content.innerHTML = '<p>Only text</p>';
    const f = await render();
    expect((f.nativeElement as HTMLElement).querySelector('nav, details')).toBeNull();
  });

  it('marks the section in view', async () => {
    const tops: Record<string, number> = { a: 0, b: 500 };
    for (const id of ['a', 'b']) {
      vi.spyOn(content.querySelector(`#${id}`)!, 'getBoundingClientRect').mockImplementation(
        () => ({ top: tops[id] }) as DOMRect,
      );
    }
    const f = await render();
    expect(links(f).map((a) => a.getAttribute('aria-current'))).toEqual(['location', null]);

    tops['a'] = -300;
    tops['b'] = 40;
    window.dispatchEvent(new Event('scroll'));
    await new Promise((resolve) => requestAnimationFrame(resolve));
    await f.whenStable();
    expect(links(f).map((a) => a.getAttribute('aria-current'))).toEqual([null, 'location']);
  });

  it('is a collapsible list below 1100 px', async () => {
    const f = await render();
    // delivered after creation: the layout must follow the observer, not its first reading
    screen.next(false);
    await f.whenStable();
    const details = (f.nativeElement as HTMLElement).querySelector('details');
    expect(details?.querySelector('summary')?.textContent?.trim()).toBe('On this page');
    expect(details?.open).toBe(false);
    expect(links(f).map((a) => a.textContent?.trim())).toEqual(['First', 'Second']);
  });
});
