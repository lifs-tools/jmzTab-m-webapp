import { ViewportScroller } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { Observable, of, Subject, throwError } from 'rxjs';

import { ExampleDto } from '../../api/models/example-dto';
import { TourDto } from '../../api/models/tour-dto';
import { pageTitle } from '../../core/page-titles';
import { TutorialStore } from '../../core/tutorial.store';
import { formatRouteData } from './format-chapters';
import { FormatChapterPage } from './format-chapter.page';

const FILE = [
  'COM\tcomment',
  'MTD\tmzTab-version\t2.0.0-M',
  'MTD\tmzTab-ID\tTEACHING',
  'MTD\tassay[1]-sample_ref\tsample[1]',
  'SMH\tSML_ID\tchemical_name',
  'SML\t1\tCer',
];

const TOUR: TourDto = {
  exampleId: 'teaching-lipidomics-2.0.mztab',
  steps: [
    {
      id: 'sections',
      chapter: 'overview',
      title: 'One file, four sections',
      text: 'The first field says what the line is.',
      lines: [1, 6],
      target: undefined,
    },
    {
      id: 'what-file',
      chapter: 'metadata',
      title: 'What the file is',
      text: 'The first metadata lines identify the file.',
      lookFor: 'mzTab-version and mzTab-ID',
      lines: [2, 3],
      target: { tab: 'metadata', group: 'general' },
    },
    {
      id: 'samples',
      chapter: 'metadata',
      title: 'Samples',
      text: 'An assay joins a sample and a run.',
      lines: [4, 4],
      target: { tab: 'metadata', key: 'assay[1]-sample_ref' },
    },
    {
      id: 'summary-row',
      chapter: 'summary',
      title: 'The summary row',
      text: 'One lipid per row.',
      lines: [5, 6],
      target: { tab: 'summary', rowIndex: 0 },
    },
  ],
};

const ORIGINAL = 'https://github.com/HUPO-PSI/mzTab-M/blob/main/examples/2.0/x.mztab';

describe('FormatChapterPage', () => {
  let scrolled: { anchor: string; rendered: string[] }[];

  async function render(
    segment: string,
    opts: {
      tour?: Observable<TourDto>;
      offered?: string[];
      fragment?: string | null;
    } = {},
  ) {
    scrolled = [];
    const offered = new Set(opts.offered ?? ['teaching-lipidomics-2.0.mztab']);
    const examples = new Map<string, ExampleDto>(
      [...offered].map((id) => [id, { id, section: 'tutorial', originalUri: ORIGINAL }]),
    );
    TestBed.configureTestingModule({
      providers: [
        {
          provide: TutorialStore,
          useValue: {
            tour: () => opts.tour ?? of(TOUR),
            lines: () => of(FILE),
            offered: () => () => offered,
            examples: () => () => examples,
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { data: formatRouteData(segment), fragment: opts.fragment ?? null } },
        },
      ],
    });
    vi.spyOn(TestBed.inject(ViewportScroller), 'scrollToAnchor').mockImplementation((anchor) =>
      scrolled.push({
        anchor,
        rendered: Array.from(document.querySelectorAll('section[id]')).map((s) => s.id),
      }),
    );
    const fixture = TestBed.createComponent(FormatChapterPage);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => document.querySelectorAll('app-format-chapter-page').forEach((e) => e.remove()));

  it("shows the chapter's steps in order", async () => {
    const { el } = await render('metadata');
    const sections = Array.from(el.querySelectorAll('section[id^="step-"]'));
    expect(sections.map((s) => s.id)).toEqual(['step-what-file', 'step-samples']);
    expect(sections[0].querySelector('h2')?.textContent?.trim()).toBe('What the file is');
    expect(sections[0].textContent).toContain('The first metadata lines identify the file.');
    expect(sections[0].textContent).toContain('Look for');
    expect(sections[0].textContent).toContain('mzTab-version and mzTab-ID');
    expect(sections[1].textContent).not.toContain('Look for');
    // each step has its excerpt, and only the lines of the step
    expect(sections[0].querySelectorAll('app-excerpt').length).toBe(1);
    // the group "general" of the step is its two lines, not the assay line outside the range
    expect(Array.from(sections[0].querySelectorAll('mark')).map((m) => m.textContent)).toEqual([
      expect.stringContaining('mzTab-version'),
      expect.stringContaining('mzTab-ID'),
    ]);
    expect(sections[0].textContent).not.toContain('assay[1]-sample_ref');
    expect(el.textContent).not.toContain('The summary row');
  });

  it('shows the overview with its own steps and the explanation of the sections', async () => {
    const { el } = await render('');
    expect(Array.from(el.querySelectorAll('section[id^="step-"]')).map((s) => s.id)).toEqual([
      'step-sections',
    ]);
    for (const prefix of ['MTD', 'SML', 'SMF', 'SME', 'COM']) {
      expect(el.textContent).toContain(prefix);
    }
  });

  it('links the download and the HUPO-PSI original', async () => {
    const { el } = await render('metadata');
    const download = el.querySelector<HTMLAnchorElement>('a[download]')!;
    expect(download.getAttribute('href')).toBe(
      'rest/v2/examples/teaching-lipidomics-2.0.mztab/content',
    );
    expect(download.getAttribute('download')).toBe('teaching-lipidomics-2.0.mztab');
    const original = Array.from(el.querySelectorAll('a')).find((a) =>
      a.textContent?.includes('HUPO-PSI'),
    )!;
    expect(original.getAttribute('href')).toBe(ORIGINAL);
    expect(el.textContent).toContain('Adapted from an example of HUPO-PSI/mzTab-M');
    const licence = Array.from(el.querySelectorAll('a')).find((a) => a.textContent === 'CC BY 4.0')!;
    expect(licence.getAttribute('href')).toBe('https://creativecommons.org/licenses/by/4.0/');
  });

  it('uses the study design file for the study design page', async () => {
    const { el } = await render('study-design', { offered: ['study-design-2.1.mztab'] });
    expect(el.querySelector('a[download]')?.getAttribute('href')).toBe(
      'rest/v2/examples/study-design-2.1.mztab/content',
    );
  });

  it('renders the chapter when its example is not offered for validation', async () => {
    const { el } = await render('metadata', { offered: [] });
    expect(el.querySelectorAll('section[id^="step-"]').length).toBe(2);
    expect(el.querySelectorAll('app-excerpt').length).toBe(2);
    expect(el.querySelector('a[download]')?.getAttribute('href')).toBe(
      'rest/v2/examples/teaching-lipidomics-2.0.mztab/content',
    );
    // the attribution does not depend on the list: it links the examples folder
    expect(el.textContent).toContain('Adapted from an example of HUPO-PSI/mzTab-M');
    const original = Array.from(el.querySelectorAll('a')).find((a) =>
      a.textContent?.includes('HUPO-PSI'),
    )!;
    expect(original.getAttribute('href')).toBe(
      'https://github.com/HUPO-PSI/mzTab-M/tree/main/examples',
    );
    expect(el.textContent).not.toContain('Try it');
    expect(el.querySelector('button')).toBeNull();
  });

  it('shows an error note when the file cannot be loaded', async () => {
    const { el } = await render('metadata', { tour: throwError(() => new Error('boom')) });
    expect(el.textContent).toContain('could not be loaded');
    expect(el.querySelectorAll('section[id^="step-"]').length).toBe(0);
  });

  it('scrolls to the step of the link once the steps have rendered', async () => {
    const tour = new Subject<TourDto>();
    const { fixture } = await render('metadata', { tour, fragment: 'step-samples' });
    // nothing to scroll to before the steps arrive
    expect(scrolled).toEqual([]);
    tour.next(TOUR);
    tour.complete();
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(scrolled).toEqual([
      { anchor: 'step-samples', rendered: ['step-what-file', 'step-samples'] },
    ]);
  });

  it('does not scroll without a fragment', async () => {
    await render('metadata');
    expect(scrolled).toEqual([]);
  });

  it('has fixed page titles from the docs tree', () => {
    expect(pageTitle('/docs/format')).toBe('mzTab-M Validator · Understanding mzTab-M');
    expect(pageTitle('/docs/format/metadata')).toBe('mzTab-M Validator · Metadata (MTD)');
    expect(pageTitle('/docs/format/study-design')).toBe(
      'mzTab-M Validator · Describing your study design',
    );
  });
});
