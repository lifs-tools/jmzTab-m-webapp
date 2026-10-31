import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatExpansionPanel } from '@angular/material/expansion';
import { By } from '@angular/platform-browser';

import { DocumentSource } from './document-source';
import { MetadataHighlight, MetadataViewComponent } from './metadata-view.component';
import { FakeDocumentSource } from './testing/fake-document-source';

/** The views take their inputs from a template, as in the apps; assigning inputs after createComponent hides bugs. */
@Component({
  imports: [MetadataViewComponent],
  template: `<mztabm-metadata-view [highlight]="highlight()" />`,
})
class HostComponent {
  readonly highlight = signal<MetadataHighlight | null>(null);
}

describe('MetadataViewComponent', () => {
  let src: FakeDocumentSource;

  function render(highlight: MetadataHighlight | null = null) {
    src = new FakeDocumentSource();
    TestBed.configureTestingModule({ providers: [{ provide: DocumentSource, useValue: src }] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    if (highlight) fixture.componentInstance.highlight.set(highlight); // arrives after the first render, as a jump does
    return fixture;
  }
  const setHighlight = (f: { componentInstance: HostComponent }, h: MetadataHighlight | null) =>
    f.componentInstance.highlight.set(h);

  it('loads a metadata group only when opened', async () => {
    const fixture = render();
    src.oneGroups().reply([
      { name: 'general', count: 2 },
      { name: 'instrument', count: 1 },
    ]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const titles = Array.from(el.querySelectorAll('mat-panel-title')).map((t) => t.textContent?.trim());
    expect(titles).toEqual(['general (2)', 'instrument (1)']);

    const general = src.onePage('general');
    expect(general.args.page).toBe(0);
    expect(general.args.size).toBe(50);
    general.reply({
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
    src.nonePage('instrument');

    const headers = el.querySelectorAll<HTMLElement>('.mat-expansion-panel-header');
    headers[1].click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    src.onePage('instrument').reply({ group: 'instrument', page: 0, size: 50, total: 1, entries: [] });
  });

  async function settle(fixture: { detectChanges(): void; whenStable(): Promise<unknown> }) {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const entries = (n: number) =>
    Array.from({ length: n }, (_, i) => ({ lineNumber: i + 1, key: `sample[${60 + i}]-description`, value: `v${i}` }));

  async function renderHighlighted(highlight: MetadataHighlight) {
    const fixture = render(highlight);
    src.oneGroups().reply([
      { name: 'general', count: 1 },
      { name: 'sample', count: 120 },
    ]);
    await settle(fixture);
    return fixture;
  }

  it('expands the highlighted group and asks for the page that holds the key', async () => {
    const key = 'sample[60]-description';
    const fixture = await renderHighlighted({ group: 'sample', keys: [key], focusKey: key, moveFocus: true });
    const req = src.onePage('sample');
    expect(req.args.key).toBe(key);
    req.reply({ group: 'sample', page: 1, size: 50, total: 70, entries: [{ lineNumber: 9, key, value: 'x' }] });
    src.onePage('general').reply({ group: 'general', page: 0, size: 50, total: 1, entries: [] });
    await settle(fixture);
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.entry--hl .key')?.textContent).toBe(key);
    expect(document.activeElement).toBe(el.querySelector('.entry--hl'));
    expect(el.querySelector('.range, .pager span')?.textContent).toContain('51');
  });

  it('marks every highlighted key on the page', async () => {
    const all = entries(3);
    const fixture = await renderHighlighted({ group: 'sample', keys: all.map((e) => e.key), focusKey: null, moveFocus: true });
    src.onePage('sample').reply({ group: 'sample', page: 0, size: 50, total: 3, entries: all });
    src.onePage('general').reply({ group: 'general', page: 0, size: 50, total: 1, entries: [] });
    await settle(fixture);
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.entry--hl').length).toBe(3);
  });

  it('outlines a group-only highlight', async () => {
    const fixture = await renderHighlighted({ group: 'sample', keys: [], focusKey: null, moveFocus: true });
    src.onePage('sample').reply({ group: 'sample', page: 0, size: 50, total: 1, entries: [] });
    src.onePage('general').reply({ group: 'general', page: 0, size: 50, total: 1, entries: [] });
    await settle(fixture);
    const panels = (fixture.nativeElement as HTMLElement).querySelectorAll('mat-expansion-panel');
    expect(panels[1].classList.contains('panel--hl')).toBe(true);
    expect(panels[0].classList.contains('panel--hl')).toBe(false);
  });

  const pageOf = (page: number, key: string) => ({
    group: 'sample', page, size: 50, total: 120, entries: [{ lineNumber: page + 1, key, value: 'x' }],
  });
  const empty = (g: string) => ({ group: g, page: 0, size: 50, total: 1, entries: [] });
  const K = 'sample[60]-description';

  async function openedAtKey() {
    const fixture = await renderHighlighted({ group: 'sample', keys: [K], focusKey: K, moveFocus: true });
    src.onePage('sample').reply(pageOf(1, K));
    src.onePage('general').reply(empty('general'));
    await settle(fixture);
    await settle(fixture);
    return fixture;
  }
  const buttons = (f: { nativeElement: unknown }, label: string) =>
    (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!;

  it('does not steal focus when the user pages away and back', async () => {
    const fixture = await openedAtKey();
    buttons(fixture, 'Next page').click();
    await settle(fixture);
    const next = src.onePage('sample');
    expect(next.args.key).toBeUndefined();
    next.reply(pageOf(2, 'sample[100]-x'));
    await settle(fixture);
    const prevBtn = buttons(fixture, 'Previous page');
    prevBtn.focus();
    prevBtn.click();
    await settle(fixture);
    const back = src.onePage('sample');
    expect(back.args.key).toBeUndefined();
    back.reply(pageOf(1, K));
    await settle(fixture);
    await settle(fixture);
    expect(document.activeElement).toBe(prevBtn);
  });

  it('repeats the jump for a new highlight object with the same key', async () => {
    const fixture = await openedAtKey();
    buttons(fixture, 'Next page').click();
    await settle(fixture);
    src.onePage('sample').reply(pageOf(2, 'sample[100]-x'));
    await settle(fixture);
    setHighlight(fixture, { group: 'sample', keys: [K], focusKey: K, moveFocus: true });
    await settle(fixture);
    const req = src.onePage('sample');
    expect(req.args.key).toBe(K);
    req.reply(pageOf(1, K));
    await settle(fixture);
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(document.activeElement).toBe(el.querySelector('.entry--hl'));
  });

  it('expands a collapsed panel again for a new highlight object', async () => {
    const fixture = await openedAtKey();
    const el = fixture.nativeElement as HTMLElement;
    const header = el.querySelectorAll<HTMLElement>('.mat-expansion-panel-header')[1];
    header.click();
    await settle(fixture);
    expect(header.getAttribute('aria-expanded')).toBe('false');
    setHighlight(fixture, { group: 'sample', keys: [K], focusKey: K, moveFocus: true });
    await settle(fixture);
    expect(header.getAttribute('aria-expanded')).toBe('true');
  });

  it('scrolls to the entry without taking the focus when the jump does not move it', async () => {
    const fixture = await renderHighlighted({ group: 'sample', keys: [K], focusKey: K, moveFocus: false });
    src.onePage('sample').reply(pageOf(1, K));
    src.onePage('general').reply(empty('general'));
    await settle(fixture);
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.entry--hl')).toBeTruthy();
    expect(el.contains(document.activeElement)).toBe(false);
  });

  it('focuses the entry only once its panel has finished expanding', async () => {
    const fixture = await renderHighlighted({ group: 'sample', keys: [K], focusKey: K, moveFocus: true });
    // The panel is still expanding: its content stays hidden, and browsers ignore focus() on hidden elements.
    const content = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.mat-expansion-panel-content')[1];
    content.style.visibility = 'hidden';
    src.onePage('sample').reply(pageOf(1, K));
    src.onePage('general').reply(empty('general'));
    await settle(fixture);
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.contains(document.activeElement)).toBe(false);

    content.style.visibility = '';
    fixture.debugElement.queryAll(By.directive(MatExpansionPanel))[1].componentInstance.afterExpand.emit();
    await settle(fixture);
    expect(document.activeElement).toBe(el.querySelector('.entry--hl'));
  });

  it('moves from one group to another', async () => {
    const fixture = await openedAtKey();
    const key = 'general-x';
    setHighlight(fixture, { group: 'general', keys: [key], focusKey: key, moveFocus: true });
    await settle(fixture);
    const req = src.onePage('general');
    expect(req.args.key).toBe(key);
    req.reply({ group: 'general', page: 0, size: 50, total: 1, entries: [{ lineNumber: 1, key, value: 'v' }] });
    await settle(fixture);
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.mat-expansion-panel-header')[0].getAttribute('aria-expanded')).toBe('true');
    expect(document.activeElement).toBe(el.querySelector('[tabindex="-1"].entry'));
    expect(document.activeElement?.textContent).toContain(key);
  });
});
