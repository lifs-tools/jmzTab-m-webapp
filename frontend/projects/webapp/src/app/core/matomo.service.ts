import { DOCUMENT, DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

import { AnalyticsDto } from '../api/models/analytics-dto';

type MatomoCommand = unknown[];
type MatomoWindow = Window & { _paq?: MatomoCommand[] };

const CONSENT_KEY = 'mztabm-validator.analytics-consent';

/**
 * Client-side Matomo with consent required (spec §5.4). Nothing is loaded unless the server
 * configures analytics; nothing is tracked until the visitor gives consent.
 */
@Injectable({ providedIn: 'root' })
export class MatomoService {
  private readonly document = inject(DOCUMENT);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  private initialised = false;
  private readonly consent = signal(this.readStoredConsent());

  init(analytics: AnalyticsDto | null | undefined): void {
    if (this.initialised || !analytics?.siteId || !analytics.trackerBase) {
      return;
    }
    const win = this.window();
    if (!win) {
      return;
    }
    this.initialised = true;
    const base = analytics.trackerBase.endsWith('/')
      ? analytics.trackerBase
      : `${analytics.trackerBase}/`;

    win._paq = win._paq ?? [];
    this.push(['requireConsent']);
    this.push(['setTrackerUrl', `${base}matomo.php`]);
    this.push(['setSiteId', analytics.siteId]);
    if (this.consent()) {
      // Keep Matomo's consent cookie in line with the choice stored in this browser.
      this.push(['rememberConsentGiven']);
    }

    // App info loads asynchronously, so the first navigation may already be over.
    if (this.router.navigated) {
      this.trackPageView(this.router.url);
    }
    this.router.events
      .pipe(
        filter((e): e is NavigationEnd => e instanceof NavigationEnd),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((e) => this.trackPageView(e.urlAfterRedirects));

    const script = this.document.createElement('script');
    script.async = true;
    script.src = `${base}matomo.js`;
    this.document.head.appendChild(script);
  }

  setConsent(given: boolean): void {
    this.consent.set(given);
    try {
      this.window()?.localStorage.setItem(CONSENT_KEY, given ? 'yes' : 'no');
    } catch {
      // Storage may be unavailable (private mode); Matomo keeps its own consent cookie.
    }
    if (this.initialised) {
      this.push([given ? 'rememberConsentGiven' : 'forgetConsentGiven']);
    }
  }

  consentGiven(): boolean {
    return this.consent();
  }

  private trackPageView(url: string): void {
    this.push(['setCustomUrl', url]);
    this.push(['setDocumentTitle', this.document.title]);
    this.push(['trackPageView']);
  }

  private push(command: MatomoCommand): void {
    this.window()?._paq?.push(command);
  }

  private window(): MatomoWindow | null {
    return this.document.defaultView as MatomoWindow | null;
  }

  private readStoredConsent(): boolean {
    try {
      return this.window()?.localStorage.getItem(CONSENT_KEY) === 'yes';
    } catch {
      return false;
    }
  }
}
