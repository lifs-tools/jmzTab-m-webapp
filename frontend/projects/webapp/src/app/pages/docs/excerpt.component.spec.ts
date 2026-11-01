import { TestBed } from '@angular/core/testing';

import { TourStepDto } from '../../api/models/tour-step-dto';
import { ExcerptComponent } from './excerpt.component';

const FILE = [
  'COM\tcomment',
  'MTD\tmzTab-version\t2.0.0-M',
  'MTD\tassay[1]-sample_ref\tsample[1]',
  'MTD\tassay[1]-ms_run_ref\tms_run[1]',
  '',
];

function render(step: TourStepDto) {
  const fixture = TestBed.createComponent(ExcerptComponent);
  fixture.componentRef.setInput('lines', FILE);
  fixture.componentRef.setInput('step', step);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

const STEP: TourStepDto = {
  id: 'samples',
  title: 'Samples',
  lines: [2, 3],
  target: { tab: 'metadata', key: 'assay[1]-sample_ref' },
};

describe('ExcerptComponent', () => {
  it('shows tabs as arrows with a text alternative', () => {
    const el = render(STEP);
    const arrows = el.querySelectorAll('pre span[aria-hidden="true"].tab');
    // two tabs on each of the two lines
    expect(arrows.length).toBe(4);
    expect(arrows[0].textContent).toBe('→');
    expect(el.querySelector('pre .visually-hidden')?.textContent).toBe('tab');
    expect(el.querySelector('pre')?.textContent).not.toContain('\t');
  });

  it("marks the step's element", () => {
    const el = render(STEP);
    const marks = Array.from(el.querySelectorAll('mark'));
    expect(marks.length).toBe(1);
    expect(marks[0].textContent).toContain('assay[1]-sample_ref');
    expect(marks[0].textContent).not.toContain('mzTab-version');
  });

  it('numbers the lines from the range start', () => {
    const el = render(STEP);
    const numbers = Array.from(el.querySelectorAll('.no')).map((n) => n.textContent?.trim());
    expect(numbers).toEqual(['2', '3']);
    expect(el.textContent).not.toContain('comment');
  });

  it('describes the excerpt for assistive technology', () => {
    const el = render(STEP);
    expect(el.querySelector('pre')?.getAttribute('aria-label')).toBe('Lines 2 to 3 of the file');
  });
});
