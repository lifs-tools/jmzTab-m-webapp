import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AppInfoDto } from '../api/models/app-info-dto';
import { AppInfoStore } from '../core/app-info.store';
import { MatomoService } from '../core/matomo.service';
import { SiteFooterComponent } from './site-footer.component';

describe('SiteFooterComponent', () => {
  async function render(info: AppInfoDto | undefined): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [SiteFooterComponent],
      providers: [{ provide: AppInfoStore, useValue: { info: signal(info) } }],
    }).compileComponents();
    const fixture = TestBed.createComponent(SiteFooterComponent);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function linkTexts(el: HTMLElement): string[] {
    return Array.from(el.querySelectorAll('.footer__links a, .footer__links button')).map(
      (a) => a.textContent?.trim() ?? '',
    );
  }

  it('footer names FZ Jülich and never ISAS', async () => {
    const el = await render({ analytics: undefined });
    expect(el.querySelector('img[alt="Forschungszentrum Jülich"]')).toBeTruthy();
    for (const alt of ['Uni Wien', 'MPI-CBG', 'FZ Borstel', 'BMFTR', 'BMBWF']) {
      expect(el.querySelector(`img[alt="${alt}"]`)).toBeTruthy();
    }
    expect(el.textContent).not.toContain('ISAS');
    expect(el.innerHTML).not.toContain('ISAS');
  });

  it('copyright shows the current year', async () => {
    const el = await render(undefined);
    expect(el.textContent).toContain(`© 2016 - ${new Date().getFullYear()} LIFS Tools Consortium`);
  });

  it('shows the legal links opening in a new tab', async () => {
    const el = await render(undefined);
    expect(linkTexts(el)).toEqual([
      'Imprint & Privacy Policy',
      'Impressum & Datenschutzerklärung',
      'Terms of Service',
    ]);
    const tos = el.querySelector<HTMLAnchorElement>(
      'a[href="https://lifs-tools.org/terms-of-service.html"]',
    );
    expect(tos?.target).toBe('_blank');
    expect(tos?.rel).toContain('noopener');
  });

  it('hides the Analytics link without analytics', async () => {
    const el = await render({ analytics: null as unknown as undefined, version: '2.0.0' });
    expect(linkTexts(el)).not.toContain('Analytics');
  });

  it('shows the Analytics link with analytics', async () => {
    const el = await render({ analytics: { siteId: '42', trackerBase: 'https://x/' } });
    expect(linkTexts(el)).toContain('Analytics');
  });

  it('the Analytics link opens the consent choice', async () => {
    const setConsent = vi.fn();
    TestBed.overrideProvider(MatomoService, {
      useValue: { consentGiven: () => false, setConsent },
    });
    const el = await render({ analytics: { siteId: '42', trackerBase: 'https://x/' } });
    const analytics = Array.from(el.querySelectorAll<HTMLButtonElement>('.footer__links button'));
    analytics.find((b) => b.textContent?.trim() === 'Analytics')?.click();
    const dialog = document.querySelector<HTMLElement>('mat-dialog-container');
    expect(dialog?.textContent).toContain('Allow anonymous usage statistics?');
    const yes = Array.from(dialog?.querySelectorAll('button') ?? []).find(
      (b) => b.textContent?.trim() === 'Yes',
    );
    yes?.click();
    expect(setConsent).toHaveBeenCalledWith(true);
  });
});
