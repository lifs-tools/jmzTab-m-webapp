import { ChangeDetectionStrategy, Component, DOCUMENT, Injector, computed, inject } from '@angular/core';

import { RouterLink } from '@angular/router';

import { AppInfoStore } from '../core/app-info.store';

interface Logo {
  readonly alt: string;
  readonly src: string;
  readonly href?: string;
}

/** The lifs-tools.org footer: partners, funding, legal links and the copyright band. */
@Component({
  selector: 'app-site-footer',
  imports: [RouterLink],
  templateUrl: './site-footer.component.html',
  styleUrl: './site-footer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SiteFooterComponent {
  private readonly appInfo = inject(AppInfoStore);
  private readonly injector = inject(Injector);
  private readonly document = inject(DOCUMENT);

  protected readonly year = new Date().getFullYear();
  protected readonly analyticsEnabled = computed(() => !!this.appInfo.info()?.analytics);

  protected readonly partners: readonly Logo[] = [
    { alt: 'Uni Wien', src: 'brand/Uni_Wien_Logo_2016.png', href: 'https://www.univie.ac.at/' },
    { alt: 'MPI-CBG', src: 'brand/mpi-logo.png', href: 'https://www.mpi-cbg.de/' },
    {
      alt: 'FZ Borstel',
      src: 'brand/FZB_NeuesLogo_2017_Englisch_transparent.png',
      href: 'https://www.fz-borstel.de/',
    },
    {
      alt: 'Forschungszentrum Jülich',
      src: 'brand/320px-Logo_des_Forschungszentrums_Julich_seit_2018.svg.png',
      href: 'https://www.fz-juelich.de/',
    },
  ];

  /** The BMFTR logo carries its own "With funding from the:" line and is shown larger so that it stays legible. */
  protected readonly funders: readonly (Logo & { readonly size: 'large' | 'small' })[] = [
    { alt: 'BMFTR', src: 'brand/BMFTR_en_Web_RGB_gef_durch_cropped.png', size: 'large' },
    { alt: 'BMBWF', src: 'brand/Logo_BMBWF.png', size: 'small' },
  ];

  protected readonly legalLinks = [
    {
      label: 'Imprint & Privacy Policy',
      href: 'https://lifs-tools.org/imprint-privacy-policy.html',
    },
    {
      label: 'Impressum & Datenschutzerklärung',
      href: 'https://lifs-tools.org/impressum-datenschutz.html',
    },
    { label: 'Terms of Service', href: 'https://lifs-tools.org/terms-of-service.html' },
  ] as const;

  /** The dialog stack is loaded on demand to keep it out of the initial bundle. */
  protected async openAnalytics(): Promise<void> {
    const [{ MatDialog }, { AnalyticsConsentDialogComponent }] = await Promise.all([
      import('@angular/material/dialog'),
      import('./analytics-consent-dialog.component'),
    ]);
    this.injector.get(MatDialog).open(AnalyticsConsentDialogComponent, {
      width: '440px',
      maxWidth: 'calc(100vw - 32px)',
    });
  }

  protected toTop(): void {
    const win = this.document.defaultView;
    const reduce = win?.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? true;
    win?.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' });
    this.document.getElementById('top')?.focus({ preventScroll: true });
  }
}
