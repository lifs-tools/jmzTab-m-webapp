import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { MetadataTabComponent } from './metadata-tab.component';

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
});
