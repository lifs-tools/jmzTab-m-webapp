import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AboutMzTabmComponent } from './about-mztabm.component';

describe('AboutMzTabmComponent', () => {
  async function render() {
    await TestBed.configureTestingModule({
      imports: [AboutMzTabmComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(AboutMzTabmComponent);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

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
});
