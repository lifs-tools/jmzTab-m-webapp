import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { TourDto } from '../../api/models/tour-dto';
import {
  TOUR_BACK,
  TOUR_CLOSE,
  TOUR_NEXT,
  VALIDATE_YOUR_OWN_FILE,
  tourSwitchLabel,
} from '../../core/tour-labels';
import { TourPanelComponent } from './tour-panel.component';

const TOUR: TourDto = {
  exampleId: 'teaching-lipidomics-2.0.mztab',
  title: 'A lipidomics file, step by step (mzTab-M 2.0)',
  counterpart: 'teaching-lipidomics-2.1.mztab',
  steps: [
    { id: 'sections', title: 'One file, four sections', text: 'The first field says what the line is.' },
    {
      id: 'what-file',
      title: 'What the file is',
      text: 'The first metadata lines identify the file.',
      lookFor: 'mzTab-version and mzTab-ID',
      target: { tab: 'metadata', group: 'general' },
    },
    { id: 'validate-yours', title: 'Validate your own file', text: 'Validate your own file with the form.' },
  ],
};

@Component({
  imports: [TourPanelComponent],
  template: `<app-tour-panel
    [tour]="tour"
    [index]="index()"
    [counterpartOffered]="offered()"
    [counterpartVersion]="version()"
    (back)="events.push('back')"
    (next)="events.push('next')"
    (close)="events.push('close')"
    (switchVersion)="events.push('switch')"
  />`,
})
class HostComponent {
  readonly tour = TOUR;
  readonly index = signal(0);
  readonly offered = signal(true);
  readonly version = signal<string | null>('2.1');
  readonly events: string[] = [];
}

describe('TourPanelComponent', () => {
  async function render(index = 0, offered = true) {
    await TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideRouter([])] }).compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.index.set(index);
    fixture.componentInstance.offered.set(offered);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const button = (text: string) =>
      Array.from(el.querySelectorAll<HTMLElement>('button, a')).find((b) => b.textContent?.trim() === text);
    return { fixture, el, host: fixture.componentInstance, button };
  }

  it('shows step n of m', async () => {
    const { el } = await render(1);
    expect(el.textContent).toContain('Step 2 of 3');
    expect(el.querySelector('h2')?.textContent?.trim()).toBe('What the file is');
    expect(el.textContent).toContain('The first metadata lines identify the file.');
    expect(el.textContent).toContain('Look for: mzTab-version and mzTab-ID');
    expect(el.textContent).toContain('A lipidomics file, step by step (mzTab-M 2.0)');
  });

  it('disables Back on the first step', async () => {
    const { el, button, host, fixture } = await render(0);
    expect((button(TOUR_BACK) as HTMLButtonElement).disabled).toBe(true);
    expect(el.textContent).not.toContain('Look for');
    button(TOUR_NEXT)!.click();
    button(TOUR_CLOSE)!.click();
    expect(host.events).toEqual(['next', 'close']);
    host.index.set(1);
    fixture.detectChanges();
    expect((button(TOUR_BACK) as HTMLButtonElement).disabled).toBe(false);
    button(TOUR_BACK)!.click();
    expect(host.events).toEqual(['next', 'close', 'back']);
  });

  it('offers Validate your own file on the last step', async () => {
    const { button } = await render(2);
    expect(button(TOUR_NEXT)).toBeUndefined();
    const link = button(VALIDATE_YOUR_OWN_FILE) as HTMLAnchorElement;
    expect(link.tagName).toBe('A');
    expect(link.getAttribute('href')).toBe('/');
  });

  it('keeps the focus in the panel when the pressed button goes away or is disabled', async () => {
    const { fixture, button, host } = await render(1);
    document.body.appendChild(fixture.nativeElement);
    try {
      button(TOUR_NEXT)!.focus();
      button(TOUR_NEXT)!.click();
      host.index.set(2);
      fixture.detectChanges();
      await fixture.whenStable();
      expect(document.activeElement).toBe(button(VALIDATE_YOUR_OWN_FILE));
      host.index.set(1);
      fixture.detectChanges();
      button(TOUR_BACK)!.focus();
      button(TOUR_BACK)!.click();
      host.index.set(0);
      fixture.detectChanges();
      await fixture.whenStable();
      expect(document.activeElement).toBe(button(TOUR_NEXT));
    } finally {
      fixture.nativeElement.remove();
    }
  });

  it('switches to the other version', async () => {
    const { button, host } = await render(1);
    button(tourSwitchLabel('2.1'))!.click();
    expect(host.events).toEqual(['switch']);
  });

  it('hides the version switch when the counterpart is not offered', async () => {
    const { el } = await render(1, false);
    expect(el.textContent).not.toContain('Show this in');
  });

  it('never offers to edit the file', async () => {
    const { el } = await render(1);
    expect(el.textContent).not.toMatch(/fix/i);
    expect(el.querySelector('a[href^="/edit"]')).toBeNull();
  });
});
