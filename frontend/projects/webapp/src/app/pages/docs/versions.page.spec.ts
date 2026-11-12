import { ViewportScroller } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { Observable, of, Subject, throwError } from 'rxjs';

import { TourDto } from '../../api/models/tour-dto';
import { QUICK_FIX_TITLES } from '../../core/quick-fix-labels';
import { TutorialStore } from '../../core/tutorial.store';
import { VERIFIED_CHANGES, VersionsPage } from './versions.page';

const TEACHING_21 = 'teaching-lipidomics-2.1.mztab';
const FILE = [
  'COM\tcomment',
  'MTD\tmzTab-version\t2.1.0-M',
  'MTD\tmzTab-profile\tM+S+F+E',
  'MTD\tmzTab-ID\tTEACHING',
];
const TOUR: TourDto = {
  exampleId: TEACHING_21,
  steps: [
    { id: 'what-file', chapter: 'metadata', title: 'What the file is', text: 'Not this one.', lines: [2, 4] },
    {
      id: 'profile',
      chapter: 'profile',
      title: 'The profile',
      text: 'mzTab-profile states which sections the file has.',
      lookFor: 'mzTab-profile: M+S+F+E',
      lines: [3, 3],
      target: { tab: 'metadata', key: 'mzTab-profile' },
    },
  ],
};
const ENGINE_20 = { line: '2.0', mzTabVersions: ['2.0.0-M'], jmztabmVersion: '3.0.9', available: true };
const ENGINE_21 = { line: '2.1', mzTabVersions: ['2.1.0-M'], jmztabmVersion: '2.1.0', available: true };
const HINT = 'https://dev.example.org/mztabvalidator/';

describe('VersionsPage', () => {
  /** Per call of scrollToAnchor: the anchor, and whether the profile excerpt was in the page. */
  let scrolled: { anchor: string; excerpt: boolean }[];

  /**
   * `null` answers app-info with an error; `'pending'` leaves it unanswered (loading). `opts.tour` delivers the
   * tour of the 2.1 teaching file (at once by default); `opts.fragment` is the route's fragment.
   */
  async function render(info: object | null | 'pending' = {
        version: '2.0.0',
        buildDate: '2026-10-01',
        engines: [
          { line: '2.0', mzTabVersions: ['2.0.0-M'], jmztabmVersion: '3.0.9', available: true },
          { line: '2.1', mzTabVersions: ['2.1.0-M'], available: false },
        ],
        defaultLine: '2.0',
        retentionDays: 7,
        customMappingsEnabled: true,
      }, opts: { tour?: Observable<TourDto>; fragment?: string | null } = {}) {
    scrolled = [];
    const route = opts.fragment === undefined ? [] : [
      { provide: ActivatedRoute, useValue: { snapshot: { fragment: opts.fragment } } },
    ];
    await TestBed.configureTestingModule({
      imports: [VersionsPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: TutorialStore,
          useValue: {
            tour: (id: string) => (id === TEACHING_21 ? (opts.tour ?? of(TOUR)) : throwError(() => new Error(id))),
            lines: (id: string) => (id === TEACHING_21 ? of(FILE) : throwError(() => new Error(id))),
            examples: () => () => new Map(),
          },
        },
        ...route,
      ],
    }).compileComponents();
    vi.spyOn(TestBed.inject(ViewportScroller), 'scrollToAnchor').mockImplementation((anchor) =>
      scrolled.push({ anchor, excerpt: !!document.querySelector('app-excerpt') }),
    );
    const fixture = TestBed.createComponent(VersionsPage);
    const request = TestBed.inject(HttpTestingController).expectOne((r) => r.url.endsWith('rest/v2/app-info'));
    if (info === 'pending') {
      fixture.detectChanges();
    } else {
      request.flush(info ?? 'no', info ? {} : { status: 500, statusText: 'Server Error' });
      await fixture.whenStable();
    }
    return { el: fixture.nativeElement as HTMLElement, fixture };
  }

  it('lists each mzTab-M version with its jmzTab-m version and availability', async () => {
    const { el } = await render();
    const section = el.querySelector('#about-versions')!;
    expect(section.querySelector('h2')?.id).toBe('versions-engines');
    expect(section.querySelector('h2')?.textContent?.trim()).toBe('Versions and engines');
    const rows = Array.from(section.querySelectorAll('tbody tr')).map((tr) =>
      Array.from(tr.querySelectorAll('th, td')).map((c) => c.textContent?.trim()),
    );
    expect(rows).toEqual([
      ['mzTab-M 2.0', 'jmzTab-m 3.0.9', 'Available'],
      ['mzTab-M 2.1', '—', 'Not available right now'],
    ]);
    expect(section.textContent?.replace(/\s+/g, ' ')).toContain(
      'mzTab-M 2.0 is validated by jmzTab-m 1.0.x and mzTab-M 2.1 by jmzTab-m 2.1.x. ' +
        'Once mzTab-M 2.1 is the default, 2.0 receives bug fixes only.',
    );
  });

  it('says so when no engine information is available', async () => {
    const { el } = await render(null);
    expect(el.querySelector('#about-versions table')).toBeNull();
    expect(el.textContent).toContain('Version information is not available right now.');
  });

  it('shows the profile step of the 2.1 teaching file', async () => {
    const { el } = await render();
    const section = el.querySelector('#what-changed')!.closest('section')!;
    const excerpt = section.querySelector('app-excerpt')!;
    expect(excerpt).toBeTruthy();
    // only the step's line, with its key marked
    expect(Array.from(excerpt.querySelectorAll('mark')).map((m) => m.textContent)).toEqual([
      expect.stringContaining('mzTab-profile'),
    ]);
    expect(excerpt.textContent).not.toContain('mzTab-ID');
    expect(section.textContent).toContain('The profile');
    expect(section.textContent).toContain('mzTab-profile states which sections the file has.');
    expect(section.textContent).not.toContain('Not this one.');
    expect(section.textContent).toContain(TEACHING_21);
    expect(section.textContent).toContain('CC BY 4.0');
  });

  it('lists only the verified changes', async () => {
    const { el } = await render();
    const table = el.querySelector('#what-changed')!.closest('section')!.querySelector('table')!;
    const rows = Array.from(table.querySelectorAll('tbody tr')).map((tr) =>
      Array.from(tr.querySelectorAll('th, td')).map((c) => c.textContent?.replace(/\s+/g, ' ').trim()),
    );
    expect(rows.map((r) => r.slice(0, 3))).toEqual(
      VERIFIED_CHANGES.map((c) => [c.change, c.meaning, c.mustChange]),
    );
    // each row shows its source, the class of jmzTab-m 2.1 linked to its source file; the engine test that checks
    // the row (TwoOneChangesTest) is kept in the data, for the build, and not shown to the reader
    expect(VERIFIED_CHANGES.length).toBeGreaterThan(0);
    const links = Array.from(table.querySelectorAll('tbody tr td.source a'));
    VERIFIED_CHANGES.forEach((c, i) => {
      expect(c.source.basis).toMatch(/^[A-Z]\w+$/);
      expect(c.source.test).toMatch(/^[a-z]\w+$/);
      expect(rows[i][3]).toBe(`jmzTab-m 2.1 ${c.source.basis}`);
      expect(links[i].textContent?.trim()).toBe(c.source.basis);
      expect(links[i].getAttribute('href')).toBe(
        `https://github.com/lifs-tools/jmzTab-m/blob/master/io/src/main/java/org/lifstools/mztab2/io/validators/${c.source.basis}.java`,
      );
    });
    expect(links.length).toBe(VERIFIED_CHANGES.length);
    expect(table.textContent).not.toContain('TwoOneChangesTest');
    expect(table.textContent).not.toMatch(/\w+Test\b/);
    expect(new Set(VERIFIED_CHANGES.map((c) => c.source.test)).size).toBe(VERIFIED_CHANGES.length);
  });

  it('claims a difference from 2.0 only with a test of the 2.0 engine', () => {
    for (const c of VERIFIED_CHANGES) {
      if (/\b2\.0 validator\b/.test(c.meaning)) expect(c.source.contrast).toMatch(/^[A-Z]\w+Test\.[a-z]\w+$/);
    }
    expect(VERIFIED_CHANGES.find((c) => c.change === 'study_variable[n]-factors')?.source.contrast).toBe(
      'FactorsTwoZeroTest.acceptsFactorsWithoutWarn2067',
    );
    expect(VERIFIED_CHANGES.map((c) => c.change)).not.toContain('Feature charge may be null');
  });

  it('lists no tool that writes 2.1 without a source', async () => {
    const { el } = await render();
    const tools = el.querySelector('#tools-21')!.closest('section')!;
    expect(tools.textContent).toContain('This list fills as tools adopt mzTab-M 2.1');
  });

  it('numbers the conversion steps', async () => {
    const { el } = await render();
    const section = el.querySelector('#converting')!.closest('section')!;
    const steps = Array.from(section.querySelectorAll('ol > li'));
    expect(steps.length).toBe(5);
    expect(steps[0].textContent).toContain('2.1.0-M');
    expect(steps[1].textContent).toContain('mzTab-profile');
    expect(steps[2].textContent).toContain(QUICK_FIX_TITLES['remove-mismatched-field']);
    expect(steps[2].textContent).toContain('Info-2057');
    expect(steps[3].textContent).toContain('study_variable_group');
    expect(steps[4].textContent).toContain('2.1');
  });

  it('links the development validator when 2.1 is unavailable', async () => {
    const hint = (el: HTMLElement) =>
      el.querySelector('#validating-21')!.closest('section')!.querySelector(`a[href="${HINT}"]`);
    const info = (engines: object[]) => ({ version: '2.0.0', engines, defaultLine: '2.0', unavailableHintUrl: HINT });

    expect(hint((await render(info([ENGINE_20, { ...ENGINE_21, available: false }]))).el)).toBeTruthy();
    TestBed.resetTestingModule();
    // the production rollout: no 2.1 engine at all
    expect(hint((await render(info([ENGINE_20]))).el)).toBeTruthy();
    TestBed.resetTestingModule();
    expect(hint((await render(info([ENGINE_20, ENGINE_21]))).el)).toBeNull();
    TestBed.resetTestingModule();
    // without a configured address there is nothing to link
    const { el } = await render({ ...info([ENGINE_20]), unavailableHintUrl: null });
    expect(el.querySelector('#validating-21')!.closest('section')!.querySelector('a[target=_blank]')).toBeNull();
    expect(el.querySelector('#validating-21')!.closest('section')!.textContent).toContain(
      'cannot validate mzTab-M 2.1 files right now',
    );
  });

  it('scrolls to a section of a deep link once the excerpt above it has rendered', async () => {
    const tour = new Subject<TourDto>();
    const { fixture } = await render(undefined, { tour, fragment: 'tools-21' });
    // the router scrolled at NavigationEnd, before the excerpt existed; nothing yet
    expect(scrolled).toEqual([]);
    tour.next(TOUR);
    tour.complete();
    await fixture.whenStable();
    expect(scrolled).toEqual([{ anchor: 'tools-21', excerpt: true }]);
  });

  it('scrolls once the teaching file has failed to load, and never without a fragment', async () => {
    const tour = new Subject<TourDto>();
    const { fixture } = await render(undefined, { tour, fragment: 'converting' });
    tour.error(new Error('gone'));
    await fixture.whenStable();
    expect(scrolled).toEqual([{ anchor: 'converting', excerpt: false }]);
    TestBed.resetTestingModule();
    await render(undefined, { fragment: null });
    expect(scrolled).toEqual([]);
  });

  it('says nothing about validating 2.1 here until app-info has arrived, nor when it failed', async () => {
    const claims = (el: HTMLElement) => el.querySelector('#validating-21')!.closest('section')!.textContent!;
    for (const state of ['pending', null] as const) {
      const { el } = await render(state);
      expect(claims(el)).not.toContain('cannot validate mzTab-M 2.1 files');
      expect(claims(el)).not.toContain('This validator validates mzTab-M 2.1 files');
      // the general text stays
      expect(claims(el)).toContain('mztab-m-2.1-default');
      TestBed.resetTestingModule();
    }
  });
});
