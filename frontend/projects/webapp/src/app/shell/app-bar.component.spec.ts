import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { DOCS_TREE } from '../pages/docs/docs-tree';
import { AppBarComponent } from './app-bar.component';

@Component({ template: '' })
class Blank {}

describe('AppBarComponent', () => {
  async function render(url = '/'): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [AppBarComponent],
      providers: [
        provideRouter([
          { path: '', component: Blank },
          { path: 'examples', component: Blank },
          { path: 'docs', children: [{ path: '**', component: Blank }] },
        ]),
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AppBarComponent);
    await TestBed.inject(Router).navigateByUrl(url);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('marks only the active nav item, with a non-colour cue', async () => {
    const el = await render('/examples');
    const active = Array.from(el.querySelectorAll('.nav__item--active'));
    expect(active.map((a) => a.textContent?.trim())).toEqual(['Examples']);
    expect(active[0].getAttribute('aria-current')).toBe('page');
    const validate = Array.from(el.querySelectorAll('.nav__item')).find(
      (a) => a.textContent?.trim() === 'Validate',
    );
    expect(validate?.classList).not.toContain('nav__item--active');
    expect(validate?.hasAttribute('aria-current')).toBe(false);
  });

  it('shows the five nav items in order', async () => {
    const el = await render();
    const items = Array.from(el.querySelectorAll('.nav__item')).map((a) => a.textContent?.trim());
    expect(items).toEqual([
      'Validate',
      'Examples',
      'Documentation',
      'REST API',
      'LIFS Tools ↗',
    ]);
  });

  it('has no About item', async () => {
    const el = await render();
    const items = Array.from(el.querySelectorAll('.nav__item')).map((a) => a.textContent?.trim());
    expect(items).not.toContain('About');
  });

  function menuTexts(panelClass: string): string[] {
    return Array.from(document.querySelectorAll(`.${panelClass} [mat-menu-item]`)).map(
      (a) => a.textContent?.trim() ?? '',
    );
  }

  it('offers a Documentation menu with the chapters and All documentation', async () => {
    const el = await render();
    const trigger = el.querySelector<HTMLButtonElement>('button.nav__item')!;
    expect(trigger.textContent?.trim()).toBe('Documentation');
    trigger.click();
    await vi.waitFor(() => expect(menuTexts('app-bar-submenu').length).toBeGreaterThan(0));
    expect(menuTexts('app-bar-submenu')).toEqual([
      'All documentation',
      ...DOCS_TREE.map((n) => n.title),
    ]);
    const hrefs = Array.from(document.querySelectorAll('.app-bar-submenu a[mat-menu-item]')).map(
      (a) => a.getAttribute('href'),
    );
    expect(hrefs).toEqual(['/docs', ...DOCS_TREE.map((n) => n.path)]);
  });

  it('marks Documentation active on docs pages', async () => {
    const el = await render('/docs/problems');
    const trigger = el.querySelector('button.nav__item')!;
    expect(trigger.classList).toContain('nav__item--active');
    expect(
      Array.from(el.querySelectorAll('.nav__item--active')).map((a) => a.textContent?.trim()),
    ).toEqual(['Documentation']);
  });

  it('does not mark Documentation active elsewhere', async () => {
    const el = await render('/examples');
    expect(el.querySelector('button.nav__item')?.classList).not.toContain('nav__item--active');
  });

  it('lists the documentation under a heading in the narrow-screen menu', async () => {
    const el = await render();
    el.querySelector<HTMLButtonElement>('button.menu-button')!.click();
    await vi.waitFor(() => expect(menuTexts('app-bar-menu').length).toBeGreaterThan(0));
    const group = document.querySelector('.app-bar-menu [role=group]')!;
    expect(group.getAttribute('aria-label')).toBe('Documentation');
    expect(group.querySelector('.app-bar-menu__heading')?.textContent?.trim()).toBe(
      'Documentation',
    );
    expect(
      Array.from(group.querySelectorAll('[mat-menu-item]')).map((a) => a.textContent?.trim()),
    ).toEqual(['All documentation', ...DOCS_TREE.map((n) => n.title)]);
    expect(menuTexts('app-bar-menu')).toEqual([
      'Validate',
      'Examples',
      'All documentation',
      ...DOCS_TREE.map((n) => n.title),
      'REST API',
      'LIFS Tools ↗',
      'GitHub ↗',
    ]);
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
