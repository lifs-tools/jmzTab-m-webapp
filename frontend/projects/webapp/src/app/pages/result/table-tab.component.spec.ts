import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { TablePage } from '../../api/models/table-page';
import { TableTabComponent } from './table-tab.component';

const ALL = Array.from({ length: 120 }, (_, i) => (i === 0 ? 'SML_ID' : `col[${i}]`));

function page(over: Partial<TablePage> = {}): TablePage {
  const columns = over.columns ?? ALL.slice(0, 50);
  return {
    section: 'SML',
    page: 0,
    size: 50,
    totalRows: 846,
    allColumns: ALL,
    columns,
    rows: [{ index: 0, cells: columns.map((_, i) => (i === 2 ? 'null' : `v${i}`)) }],
    ...over,
  };
}

describe('TableTabComponent', () => {
  let http: HttpTestingController;

  function render() {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(TableTabComponent);
    fixture.componentRef.setInput('validationId', 'abc');
    fixture.componentRef.setInput('section', 'sml');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const next = (): TestRequest => http.expectOne((r) => r.url.includes('/validations/abc/tables/sml'));
    const settle = async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    return { fixture, el, next, settle };
  }

  afterEach(() => vi.useRealTimers());

  it('requestsOnlyTheColumnWindow', async () => {
    const { el, next, settle } = render();
    const first = next();
    expect(first.request.params.has('columns')).toBe(false);
    first.flush(page());
    await settle();
    expect(el.querySelectorAll('thead tr:first-child th').length).toBe(50);
    expect(el.querySelector('.window')?.textContent).toContain('Columns 1–50 of 120');
    expect(el.querySelector('.pager')?.textContent).toContain('1–50 of 846');
    expect(el.querySelectorAll('td.null').length).toBe(1);

    el.querySelector<HTMLButtonElement>('button[aria-label="Next columns"]')!.click();
    await settle();
    const second = next();
    expect(second.request.params.get('columns')).toBe([ALL[0], ...ALL.slice(50, 100)].join(','));
    expect(second.request.params.get('page')).toBe('0');
    second.flush(page({ columns: [ALL[0], ...ALL.slice(50, 100)] }));
    await settle();
    expect(el.querySelector('.window')?.textContent).toContain('Columns 51–100 of 120');
  });

  it('sort click requests sort and descending', async () => {
    const { el, next, settle } = render();
    next().flush(page());
    await settle();
    const sortBtn = () => el.querySelectorAll<HTMLButtonElement>('thead .sort')[1];
    sortBtn().click();
    await settle();
    let r = next();
    expect(r.request.params.get('sort')).toBe(ALL[1]);
    expect(r.request.params.get('descending')).toBe('false');
    r.flush(page());
    await settle();
    sortBtn().click();
    await settle();
    r = next();
    expect(r.request.params.get('descending')).toBe('true');
    r.flush(page());
    await settle();
    sortBtn().click();
    await settle();
    r = next();
    expect(r.request.params.has('sort')).toBe(false);
    r.flush(page());
  });

  it('column filter is debounced and sent as filter=COL:text', async () => {
    const { el, next, settle } = render();
    next().flush(page());
    await settle();
    vi.useFakeTimers();
    const input = el.querySelectorAll<HTMLInputElement>('thead input')[1];
    input.value = 'a:b,c';
    input.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(299);
    http.expectNone((r) => r.url.includes('/tables/sml'));
    vi.advanceTimersByTime(2);
    vi.useRealTimers();
    await settle();
    const r = next();
    expect(r.request.params.getAll('filter')).toEqual([`${ALL[1]}:a:b,c`]);
    expect(r.request.params.get('page')).toBe('0');
    r.flush(page());
  });

  it('encodes plus signs, spaces and percent in filters', async () => {
    const { el, next, settle } = render();
    next().flush(page());
    await settle();
    vi.useFakeTimers();
    const input = el.querySelectorAll<HTMLInputElement>('thead input')[1];
    input.value = '[M+H]+ 5%';
    input.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(301);
    vi.useRealTimers();
    await settle();
    const r = next();
    expect(r.request.urlWithParams).toContain('filter=col%5B1%5D%3A%5BM%2BH%5D%2B%205%25');
    r.flush(page());
  });

  it('shows an inline error on 400', async () => {
    const { el, next, settle } = render();
    next().flush(page());
    await settle();
    el.querySelector<HTMLButtonElement>('button[aria-label="Next columns"]')!.click();
    await settle();
    next().flush({}, { status: 400, statusText: 'Bad Request' });
    await settle();
    expect(el.querySelector('[role="alert"]')).toBeTruthy();
    expect(el.querySelectorAll('tbody tr').length).toBe(1);
    // the controls still describe the columns that are shown
    expect(el.querySelector('.window')?.textContent).toContain('Columns 1–50 of 120');
    expect(el.querySelector<HTMLButtonElement>('button[aria-label="Next columns"]')!.disabled).toBe(false);
    expect(el.querySelector<HTMLButtonElement>('button[aria-label="Previous columns"]')!.disabled).toBe(true);
    expect(el.querySelectorAll('thead tr:first-child th').length).toBe(50);
    http.expectNone((r) => r.url.includes('/tables/sml'));
  });

  it('offers a retry when the first load fails', async () => {
    const { el, next, settle } = render();
    next().flush({}, { status: 500, statusText: 'Server Error' });
    await settle();
    el.querySelector<HTMLButtonElement>('.retry')!.click();
    await settle();
    next().flush(page());
    await settle();
    expect(el.querySelectorAll('tbody tr').length).toBe(1);
    expect(el.querySelector('[role="alert"]')).toBeNull();
  });

  it('does not refetch an equal query', async () => {
    const { el, next, settle } = render();
    next().flush(page());
    await settle();
    el.querySelector<HTMLButtonElement>('button[aria-label="Previous page"]')!.click();
    vi.useFakeTimers();
    const input = el.querySelectorAll<HTMLInputElement>('thead input')[1];
    input.value = '';
    input.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(301);
    vi.useRealTimers();
    await settle();
    http.expectNone((r) => r.url.includes('/tables/sml'));
  });

  it('picker caps the selection at 50 and keeps the first column', async () => {
    const { el, next, settle } = render();
    next().flush(page());
    await settle();
    el.querySelector<HTMLButtonElement>('.pick-open')!.click();
    await settle();
    const boxes = () => Array.from(el.querySelectorAll<HTMLInputElement>('.picker input[type="checkbox"]'));
    expect(boxes()[0].checked).toBe(true);
    expect(boxes()[0].disabled).toBe(true);
    // the first window (50) is preselected, so the cap is reached
    expect(boxes().filter((b) => b.checked).length).toBe(50);
    expect(boxes()[60].disabled).toBe(true);
    expect(el.querySelector('.picker')?.textContent).toContain('Up to 50 columns.');
    boxes()[10].click();
    await settle();
    expect(boxes()[60].disabled).toBe(false);
    boxes()[60].click();
    await settle();
    el.querySelector<HTMLButtonElement>('.pick-apply')!.click();
    await settle();
    const r = next();
    const cols = r.request.params.get('columns')!.split(',');
    expect(cols.length).toBe(50);
    expect(cols[0]).toBe('SML_ID');
    expect(cols).toContain(ALL[60]);
    expect(cols).not.toContain(ALL[10]);
    r.flush(page({ columns: cols }));
    await settle();
    expect(el.querySelector('.window')).toBeNull();
  });
});
