import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { GuidanceEntry } from '../../api/models/guidance-entry';
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
  async function render(entries: GuidanceEntry[] | 'error') {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ProblemsPage);
    fixture.detectChanges();
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
});
