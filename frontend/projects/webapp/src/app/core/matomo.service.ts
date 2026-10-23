import { DOCUMENT, DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

import { AnalyticsDto } from '../api/models/analytics-dto';

type MatomoCommand = unknown[];
type MatomoWindow = Window & { _paq?: MatomoCommand[] };

const CONSENT_KEY = 'mztabm-validator.analytics-consent';

/**
 * Result ids are capability URLs (anyone holding one can view or delete the result), so they
 * must never reach analytics: query strings and fragments are dropped, the segment after
 * `/result/` or `/validate/` and any UUID-shaped segment (in any case) become `:id`.
 */
export function redactUrl(url: string): string {
  return url
    .replace(/[?#].*$/s, '')
    .replace(/\/(result|validate)\/[^/]+/gi, '/$1/:id')
    .replace(/\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?=\/|$)/gi, '/:id');
}

/** Unknown routes are tracked under one fixed path: a mistyped link may hold an id. */
const NOT_FOUND_PATH = '/not-found';

/** Fixed page titles per route; never the document title (it may name a file). */
export function pageTitle(path: string): string {
  const routes: Record<string, string> = {
    '/': 'Validate',
    '/examples': 'Examples',
    '/about': 'About',
  };
  if (path.startsWith('/result/') || path.startsWith('/validate/')) {
    return 'mzTab-M Validator · Result';
  }
  return `mzTab-M Validator · ${routes[path] ?? 'Not found'}`;
}

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
  /** The previous tracked (already redacted) URL, used as the referrer of the next view. */
  private lastTrackedUrl: string | null = null;
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

  private trackPageView(routerUrl: string): void {
    const path = this.isWildcardRoute() ? NOT_FOUND_PATH : redactUrl(routerUrl) || '/';
    const url = redactUrl(this.appBase() + path);
    // First view: the browser referrer, redacted (it may be an old result link).
    const referrer = this.lastTrackedUrl ?? redactUrl(this.document.referrer ?? '');
    this.push(['setReferrerUrl', referrer]);
    this.push(['setCustomUrl', url]);
    this.push(['setDocumentTitle', pageTitle(path)]);
    this.push(['trackPageView']);
    this.lastTrackedUrl = url;
  }

  /** Whether the current navigation ended on the `**` route. */
  private isWildcardRoute(): boolean {
    let route = this.router.routerState.snapshot.root;
    while (route.firstChild) {
      route = route.firstChild;
    }
    return route.routeConfig?.path === '**';
  }

  /** Origin plus the <base href> path without its trailing slash, e.g. https://h/mztabvalidator. */
  private appBase(): string {
    const origin = this.window()?.location.origin ?? '';
    const href = this.document.querySelector('base')?.getAttribute('href') ?? '/';
    const basePath = new URL(href, origin || 'http://localhost').pathname.replace(/\/$/, '');
    return origin + basePath;
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
