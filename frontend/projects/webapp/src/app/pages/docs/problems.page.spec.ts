import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ViewportScroller } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';

import { GuidanceEntry } from '../../api/models/guidance-entry';
import { pageTitle } from '../../core/page-titles';
import { ProblemsPage } from './problems.page';

const ENTRIES: GuidanceEntry[] = [
  {
    id: 'a',
    codes: ['Error-1'],
    title: 'Column missing',
    explanation: 'A column is absent.',
    fixHere: 'Add it.',
    fixInTool: 'Export it from your tool.',
    tools: [],
  },
  {
    id: 'b',
    codes: ['Error-2'],
    title: 'Bad unit',
    explanation: 'The unit is unknown.',
    fixHere: 'Pick one.',
    fixInTool: 'Set a unit in your tool.',
    tools: [{ software: 'MyTool', fixInTool: 'In MyTool, open Settings.' }],
  },
];

describe('ProblemsPage', () => {
  /** Ids of the entries rendered when the page scrolled to an anchor, per call. */
  let scrolled: { anchor: string; rendered: string[] }[];

  async function render(entries: GuidanceEntry[] | 'error', fragment: string | null = null) {
    scrolled = [];
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { fragment } } },
      ],
    });
    vi.spyOn(TestBed.inject(ViewportScroller), 'scrollToAnchor').mockImplementation((anchor) =>
      scrolled.push({
        anchor,
        rendered: Array.from(document.querySelectorAll('article[id]')).map((a) => a.id),
      }),
    );
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ProblemsPage);
    fixture.detectChanges();
    await fixture.whenStable();
    // nothing to scroll to before the catalog arrives
    expect(scrolled).toEqual([]);
    const req = http.expectOne((r) => r.url.endsWith('rest/v2/guidance'));
    if (entries === 'error') req.flush('boom', { status: 500, statusText: 'Server Error' });
    else req.flush(entries);
    await fixture.whenStable();
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  }

  it('lists every catalog entry with its title, explanation and fix in tool', async () => {
    const t = await render(ENTRIES);
    for (const s of [
      'Column missing',
      'A column is absent.',
      'Export it from your tool.',
      'Bad unit',
      'The unit is unknown.',
      'Set a unit in your tool.',
      'Pick one.',
    ]) {
      expect(t).toContain(s);
    }
  });

  it('shows a note when the catalog cannot be loaded', async () => {
    expect(await render('error')).toContain('could not be loaded');
  });

  it('scrolls to the entry of the link once the catalog has rendered', async () => {
    await render(ENTRIES, 'b');
    // the router scrolled at NavigationEnd, before the entries existed; the page does it again after they render
    expect(scrolled).toEqual([{ anchor: 'b', rendered: ['a', 'b'] }]);
  });

  it('does not scroll without a fragment or when the catalog fails', async () => {
    await render(ENTRIES);
    expect(scrolled).toEqual([]);
    TestBed.resetTestingModule();
    await render('error', 'b');
    expect(scrolled).toEqual([]);
  });

  it('has a fixed page title', () => {
    expect(pageTitle('/docs/problems')).toBe('mzTab-M Validator · Common problems');
  });
});
