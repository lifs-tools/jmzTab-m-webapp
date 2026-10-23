import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Component } from '@angular/core';
import { MatomoService, redactUrl } from './matomo.service';

@Component({ template: '' })
class Blank {}

type PaqWindow = Window & { _paq?: unknown[][] };

describe('MatomoService', () => {
  const win = window as PaqWindow;

  function matomoScripts(): HTMLScriptElement[] {
    return Array.from(document.querySelectorAll<HTMLScriptElement>('script[src*="matomo.js"]'));
  }

  // Node 24 shadows jsdom's localStorage with an unavailable one; use an in-memory Storage.
  const storageDescriptor = Object.getOwnPropertyDescriptor(window, 'localStorage');

  beforeEach(() => {
    const data = new Map<string, string>();
    const storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'clear'> = {
      getItem: (k) => data.get(k) ?? null,
      setItem: (k, v) => void data.set(k, String(v)),
      removeItem: (k) => void data.delete(k),
      clear: () => data.clear(),
    };
    Object.defineProperty(window, 'localStorage', { value: storage, configurable: true });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'about', component: Blank },
          { path: 'result/:id', component: Blank },
          { path: '**', component: Blank },
        ]),
      ],
    });
  });

  afterEach(() => {
    matomoScripts().forEach((s) => s.remove());
    delete win._paq;
    if (storageDescriptor) {
      Object.defineProperty(window, 'localStorage', storageDescriptor);
    } else {
      delete (window as { localStorage?: Storage }).localStorage;
    }
  });

  it('does not load matomo without analytics', () => {
    TestBed.inject(MatomoService).init(null);
    expect(matomoScripts().length).toBe(0);
    expect(win._paq).toBeUndefined();
  });

  it('loads matomo with consent required', () => {
    TestBed.inject(MatomoService).init({ siteId: '42', trackerBase: 'https://x/' });
    const scripts = matomoScripts();
    expect(scripts.length).toBe(1);
    expect(scripts[0].src).toBe('https://x/matomo.js');
    expect(win._paq).toContainEqual(['requireConsent']);
    expect(win._paq).toContainEqual(['setTrackerUrl', 'https://x/matomo.php']);
    expect(win._paq).toContainEqual(['setSiteId', '42']);
    expect(win._paq).not.toContainEqual(['rememberConsentGiven']);
  });

  it('tracks the current page when initialised after the first navigation', async () => {
    await TestBed.inject(Router).navigateByUrl('/about');
    TestBed.inject(MatomoService).init({ siteId: '42', trackerBase: 'https://x/' });
    const paq = win._paq ?? [];
    expect(paq).toContainEqual(['setCustomUrl', `${location.origin}/about`]);
    const trackAt = paq.findIndex((c) => c[0] === 'trackPageView');
    expect(trackAt).toBeGreaterThan(paq.findIndex((c) => c[0] === 'requireConsent'));
    expect(paq.filter((c) => c[0] === 'trackPageView').length).toBe(1);
  });

  it('initialises only once', () => {
    const matomo = TestBed.inject(MatomoService);
    matomo.init({ siteId: '42', trackerBase: 'https://x/' });
    matomo.init({ siteId: '42', trackerBase: 'https://x/' });
    expect(matomoScripts().length).toBe(1);
  });

  it('tracks a page view with the routed url on navigation', async () => {
    TestBed.inject(MatomoService).init({ siteId: '42', trackerBase: 'https://x/' });
    win._paq!.length = 0;
    await TestBed.inject(Router).navigateByUrl('/about');
    expect(win._paq).toContainEqual(['setCustomUrl', `${location.origin}/about`]);
    expect(win._paq).toContainEqual(['trackPageView']);
  });

  it('never sends result ids, queries or fragments to analytics', async () => {
    const id = '0b6f3c2e-7d41-4a8e-9f0a-2c5d8e1b4a77';
    const router = TestBed.inject(Router);
    TestBed.inject(MatomoService).init({ siteId: '42', trackerBase: 'https://x/' });
    win._paq!.length = 0;
    await router.navigateByUrl(`/result/${id}?confirmDelete=true#x`);
    await router.navigateByUrl('/about');
    const paq = win._paq ?? [];
    expect(paq).toContainEqual(['setCustomUrl', `${location.origin}/result/:id`]);
    expect(paq).toContainEqual(['setDocumentTitle', 'mzTab-M Validator · Result']);
    expect(paq).toContainEqual(['setReferrerUrl', `${location.origin}/result/:id`]);
    const sent = JSON.stringify(paq);
    expect(sent).not.toContain(id);
    expect(sent).not.toContain('confirmDelete');
    expect(sent).not.toContain('#x');
  });

  it('redacts ids under the base href path', async () => {
    const base = document.createElement('base');
    base.href = '/mztabvalidator/';
    document.head.prepend(base);
    try {
      TestBed.inject(MatomoService).init({ siteId: '42', trackerBase: 'https://x/' });
      await TestBed.inject(Router).navigateByUrl('/result/abc-123');
      expect(win._paq).toContainEqual([
        'setCustomUrl',
        `${location.origin}/mztabvalidator/result/:id`,
      ]);
      expect(JSON.stringify(win._paq)).not.toContain('abc-123');
    } finally {
      base.remove();
    }
  });

  it('tracks unknown routes as a fixed /not-found page', async () => {
    const id = '0B6F3C2E-7D41-4A8E-9F0A-2C5D8E1B4A77';
    const router = TestBed.inject(Router);
    TestBed.inject(MatomoService).init({ siteId: '42', trackerBase: 'https://x/' });
    win._paq!.length = 0;
    await router.navigateByUrl(`/Result/${id}`);
    await router.navigateByUrl(`/results/${id.toLowerCase()}/delete?x=1`);
    const paq = win._paq ?? [];
    expect(paq).toContainEqual(['setCustomUrl', `${location.origin}/not-found`]);
    expect(paq).toContainEqual(['setDocumentTitle', 'mzTab-M Validator · Not found']);
    expect(paq.filter((c) => c[0] === 'setCustomUrl')).toEqual([
      ['setCustomUrl', `${location.origin}/not-found`],
      ['setCustomUrl', `${location.origin}/not-found`],
    ]);
    expect(JSON.stringify(paq).toLowerCase()).not.toContain(id.toLowerCase());
  });

  it('redactUrl replaces UUID-shaped segments anywhere, in any case', () => {
    expect(redactUrl('https://h/x/0B6F3C2E-7D41-4A8E-9F0A-2C5D8E1B4A77/y')).toBe(
      'https://h/x/:id/y',
    );
    expect(redactUrl('/Result/0b6f3c2e-7d41-4a8e-9f0a-2c5d8e1b4a77')).toBe('/Result/:id');
  });

  it('redactUrl replaces id segments and drops queries and fragments', () => {
    expect(redactUrl('https://h/ctx/result/abc?x=1#y')).toBe('https://h/ctx/result/:id');
    expect(redactUrl('/validate/abc/delete')).toBe('/validate/:id/delete');
    expect(redactUrl('/about?q=1')).toBe('/about');
  });

  it('remembers and forgets consent', () => {
    const matomo = TestBed.inject(MatomoService);
    matomo.init({ siteId: '42', trackerBase: 'https://x/' });
    expect(matomo.consentGiven()).toBe(false);
    matomo.setConsent(true);
    expect(win._paq).toContainEqual(['rememberConsentGiven']);
    expect(matomo.consentGiven()).toBe(true);
    matomo.setConsent(false);
    expect(win._paq).toContainEqual(['forgetConsentGiven']);
    expect(matomo.consentGiven()).toBe(false);
  });

  it('restores the stored consent choice', () => {
    TestBed.inject(MatomoService).setConsent(true);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const matomo = TestBed.inject(MatomoService);
    expect(matomo.consentGiven()).toBe(true);
    matomo.init({ siteId: '42', trackerBase: 'https://x/' });
    expect(win._paq).toContainEqual(['rememberConsentGiven']);
  });
});
