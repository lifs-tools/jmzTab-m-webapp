import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { MetadataHighlight, MetadataTabComponent } from './metadata-tab.component';

describe('MetadataTabComponent', () => {
  let http: HttpTestingController;

  function render() {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(MetadataTabComponent);
    fixture.componentRef.setInput('validationId', 'abc');
    fixture.detectChanges();
    return fixture;
  }
  const isGroups = (u: string) => /\/validations\/abc\/metadata(\?.*)?$/.test(u);
  const isPage = (g: string) => (u: string) => u.includes(`/validations/abc/metadata/${g}`);

  it('loads a metadata group only when opened', async () => {
    const fixture = render();
    http.expectOne((r) => isGroups(r.url)).flush([
      { name: 'general', count: 2 },
      { name: 'instrument', count: 1 },
    ]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const titles = Array.from(el.querySelectorAll('mat-panel-title')).map((t) => t.textContent?.trim());
    expect(titles).toEqual(['general (2)', 'instrument (1)']);

    const general = http.expectOne((r) => isPage('general')(r.url));
    expect(general.request.params.get('page')).toBe('0');
    expect(general.request.params.get('size')).toBe('50');
    general.flush({
      group: 'general',
      page: 0,
      size: 50,
      total: 2,
      entries: [
        { lineNumber: 1, key: 'mzTab-version', value: '2.0.0-M' },
        { lineNumber: 2, key: 'mzTab-ID', value: '<b>x</b>' },
      ],
    });
    fixture.detectChanges();
    expect(el.textContent).toContain('mzTab-version');
    expect(el.querySelector('.value b')).toBeNull();
    http.expectNone((r) => isPage('instrument')(r.url));

    const headers = el.querySelectorAll<HTMLElement>('.mat-expansion-panel-header');
    headers[1].click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    http.expectOne((r) => isPage('instrument')(r.url)).flush({ group: 'instrument', page: 0, size: 50, total: 1, entries: [] });
  });

  async function settle(fixture: { detectChanges(): void; whenStable(): Promise<unknown> }) {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const entries = (n: number) =>
    Array.from({ length: n }, (_, i) => ({ lineNumber: i + 1, key: `sample[${60 + i}]-description`, value: `v${i}` }));

  async function renderHighlighted(highlight: MetadataHighlight) {
    const fixture = render();
    fixture.componentRef.setInput('highlight', highlight);
    http.expectOne((r) => isGroups(r.url)).flush([
      { name: 'general', count: 1 },
      { name: 'sample', count: 70 },
    ]);
    await settle(fixture);
    return fixture;
  }

  it('expands the highlighted group and asks for the page that holds the key', async () => {
    const key = 'sample[60]-description';
    const fixture = await renderHighlighted({ group: 'sample', keys: [key], focusKey: key });
    const req = http.expectOne((r) => isPage('sample')(r.url));
    expect(req.request.params.get('key')).toBe(key);
    req.flush({ group: 'sample', page: 1, size: 50, total: 70, entries: [{ lineNumber: 9, key, value: 'x' }] });
    http.expectOne((r) => isPage('general')(r.url)).flush({ group: 'general', page: 0, size: 50, total: 1, entries: [] });
    await settle(fixture);
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.entry--hl .key')?.textContent).toBe(key);
    expect(document.activeElement).toBe(el.querySelector('.entry--hl'));
    expect(el.querySelector('.range, .pager span')?.textContent).toContain('51');
  });

  it('marks every highlighted key on the page', async () => {
    const all = entries(3);
    const fixture = await renderHighlighted({ group: 'sample', keys: all.map((e) => e.key), focusKey: null });
    http.expectOne((r) => isPage('sample')(r.url)).flush({ group: 'sample', page: 0, size: 50, total: 3, entries: all });
    http.expectOne((r) => isPage('general')(r.url)).flush({ group: 'general', page: 0, size: 50, total: 1, entries: [] });
    await settle(fixture);
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.entry--hl').length).toBe(3);
  });

  it('outlines a group-only highlight', async () => {
    const fixture = await renderHighlighted({ group: 'sample', keys: [], focusKey: null });
    http.expectOne((r) => isPage('sample')(r.url)).flush({ group: 'sample', page: 0, size: 50, total: 1, entries: [] });
    http.expectOne((r) => isPage('general')(r.url)).flush({ group: 'general', page: 0, size: 50, total: 1, entries: [] });
    await settle(fixture);
    const panels = (fixture.nativeElement as HTMLElement).querySelectorAll('mat-expansion-panel');
    expect(panels[1].classList.contains('panel--hl')).toBe(true);
    expect(panels[0].classList.contains('panel--hl')).toBe(false);
  });
});
