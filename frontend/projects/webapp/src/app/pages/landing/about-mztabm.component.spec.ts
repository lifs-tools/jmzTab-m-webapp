import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { TourService } from '../../core/tour.service';
import { TutorialStore } from '../../core/tutorial.store';
import { AboutMzTabmComponent } from './about-mztabm.component';

const TOUR_ID = 'teaching-lipidomics-2.0.mztab';

describe('AboutMzTabmComponent', () => {
  let start: ReturnType<typeof vi.fn>;

  async function render(offered: string[] = [TOUR_ID]) {
    start = vi.fn(() => of(undefined));
    await TestBed.configureTestingModule({
      imports: [AboutMzTabmComponent],
      providers: [
        provideRouter([]),
        { provide: TourService, useValue: { start } },
        { provide: TutorialStore, useValue: { offered: () => signal(new Set(offered)) } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AboutMzTabmComponent);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const tourButton = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Take the tour');

  it('has three columns', async () => {
    const el = await render();
    const headings = Array.from(el.querySelectorAll('h3')).map((h) => h.textContent?.trim());
    expect(headings).toEqual(['What it is', 'What it is for', 'Who uses it']);
    expect(el.querySelectorAll('.about__col').length).toBe(3);
  });

  it('links Getting started and the documentation', async () => {
    const el = await render();
    expect(el.querySelector('a[href="/docs/getting-started"]')?.textContent).toContain('Get started with mzTab-M');
    expect(el.querySelector('a[href="/docs"]')?.textContent).toContain('Read the documentation');
  });

  it('Take the tour starts the 2.0 lipidomics tour', async () => {
    const el = await render();
    tourButton(el)!.click();
    expect(start).toHaveBeenCalledWith(TOUR_ID, 1);
  });

  it('offers the tour only when this instance offers its example', async () => {
    const el = await render(['teaching-lipidomics-2.1.mztab']);
    expect(tourButton(el)).toBeUndefined();
    expect(el.textContent).not.toContain('Take the tour');
  });
});
