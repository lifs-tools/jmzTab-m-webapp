import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { pageTitle } from '../../core/page-titles';
import { GuidePage } from './guide.page';

describe('GuidePage', () => {
  async function render(retentionDays: number | null = null) {
    await TestBed.configureTestingModule({
      imports: [GuidePage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const fixture = TestBed.createComponent(GuidePage);
    fixture.detectChanges();
    const req = TestBed.inject(HttpTestingController).expectOne((r) => r.url.endsWith('rest/v2/app-info'));
    if (retentionDays === null) req.flush('no', { status: 500, statusText: 'Server Error' });
    else req.flush({ version: '2.0.0', buildDate: '2026-10-01', engines: [], defaultLine: '2.0', retentionDays });
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('has a section for each topic and links the problems page', async () => {
    const el = await render();
    const sections = Array.from(el.querySelectorAll('.page section')).map((s) => ({
      labelledBy: s.getAttribute('aria-labelledby'),
      heading: s.querySelector('h2')?.textContent?.trim(),
      headingId: s.querySelector('h2')?.id,
    }));
    expect(sections.map((s) => s.heading)).toEqual([
      'Read a result and its guidance',
      'Fix a file in place',
      'When jmzTab-m stops reading',
      'Downloads and the resolution report',
      'How long your file is kept',
      'Files that are not UTF-8',
    ]);
    // each section is named by its own heading
    expect(sections.every((s) => s.labelledBy && s.labelledBy === s.headingId)).toBe(true);
    const problems = el.querySelector<HTMLAnchorElement>('a[href$="/problems"]');
    expect(problems?.textContent).toContain('Common problems');
  });

  it('names the labels of the editor exactly as the editor shows them', async () => {
    const text = (await render()).textContent!.replace(/\s+/g, ' ');
    for (const label of [
      'Fix this file',
      'Open a file to fix',
      'Download resolution report',
      'Resolve duplicate column',
      'Check CV rules',
      'Re-encode from Windows-1252',
      'Undo',
      'Redo',
    ]) {
      expect(text).toContain(label);
    }
  });

  it('states the limits and the retention rule', async () => {
    const text = (await render()).textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('100 steps');
    expect(text).toContain('5,000');
    expect(text).toContain('100,000 characters');
    expect(text).toContain('1,000,000 characters');
    expect(text).toContain('days after your last change');
  });

  it('names the retention of this instance', async () => {
    const text = (await render(14)).textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('kept for 14 days after your last change');
  });

  it('has a fixed page title', () => {
    expect(pageTitle('/guide')).toBe('mzTab-M Validator · Fixing a file');
  });
});
