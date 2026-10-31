import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ResultDocumentSource } from './result-document-source';

describe('ResultDocumentSource', () => {
  let http: HttpTestingController;
  let source: ResultDocumentSource;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), ResultDocumentSource] });
    http = TestBed.inject(HttpTestingController);
    source = TestBed.inject(ResultDocumentSource);
    source.validationId = 'abc';
  });

  it('is a read-only source', () => {
    expect(source.editable).toBe(false);
    expect(source.applyEdits).toBeUndefined();
  });

  it('reads the metadata groups and pages of the result', () => {
    const groups: unknown[] = [];
    source.metadataGroups().subscribe((g) => groups.push(...g));
    http.expectOne((r) => /\/validations\/abc\/metadata(\?.*)?$/.test(r.url)).flush([{ name: 'general', count: 2 }]);
    expect(groups).toEqual([{ name: 'general', count: 2 }]);

    source.metadataPage('sample', 1, 50, 'sample[60]-description').subscribe();
    const page = http.expectOne((r) => r.url.includes('/validations/abc/metadata/sample'));
    expect(page.request.params.get('page')).toBe('1');
    expect(page.request.params.get('size')).toBe('50');
    expect(page.request.params.get('key')).toBe('sample[60]-description');
    page.flush({});

    source.metadataPage('sample', 0, 50).subscribe();
    const noKey = http.expectOne((r) => r.url.includes('/validations/abc/metadata/sample'));
    expect(noKey.request.params.has('key')).toBe(false);
    noKey.flush({});
  });

  it('maps a table query onto the table endpoint', () => {
    source
      .tablePage('smf', { page: 2, size: 25, sort: 'SMF_ID', descending: true, columns: ['SMF_ID', 'adduct'], filters: [{ column: 'adduct', text: 'a:b,c' }] })
      .subscribe();
    const r = http.expectOne((x) => x.url.includes('/validations/abc/tables/smf'));
    expect(r.request.params.get('page')).toBe('2');
    expect(r.request.params.get('size')).toBe('25');
    expect(r.request.params.get('sort')).toBe('SMF_ID');
    expect(r.request.params.get('descending')).toBe('true');
    expect(r.request.params.get('columns')).toBe('SMF_ID,adduct');
    expect(r.request.params.getAll('filter')).toEqual(['adduct:a:b,c']);
    r.flush({});
  });

  it('sends no sort, columns or filter when there are none', () => {
    source.tablePage('sml', { page: 0, size: 50, sort: null, descending: false }).subscribe();
    const r = http.expectOne((x) => x.url.includes('/validations/abc/tables/sml'));
    for (const p of ['sort', 'descending', 'columns', 'filter']) expect(r.request.params.has(p)).toBe(false);
    r.flush({});
  });

  it('encodes plus signs, spaces and percent in filters', () => {
    source.tablePage('sml', { page: 0, size: 50, filters: [{ column: 'col[1]', text: '[M+H]+ 5%' }] }).subscribe();
    const r = http.expectOne((x) => x.url.includes('/validations/abc/tables/sml'));
    expect(r.request.urlWithParams).toContain('filter=col%5B1%5D%3A%5BM%2BH%5D%2B%205%25');
    r.flush({});
  });
});
