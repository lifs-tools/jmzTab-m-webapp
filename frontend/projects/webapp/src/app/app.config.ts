import { ViewportScroller } from '@angular/common';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withInMemoryScrolling } from '@angular/router';

import { routes } from './app.routes';
import { provideApiRoot } from './core/api-root';
import { httpErrorInterceptor } from './core/http-error.interceptor';

/** The app bar's height (--lifs-nav-height), kept above a section a link scrolls to. */
const APP_BAR_HEIGHT = 72;

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([httpErrorInterceptor])),
    // Fragments scroll to their section (docs anchors such as /docs/fixing#guide-fixing).
    provideRouter(
      routes,
      withInMemoryScrolling({ anchorScrolling: 'enabled', scrollPositionRestoration: 'enabled' }),
    ),
    // The router's anchor scrolling ignores CSS scroll-margin, so the offset is set here.
    provideAppInitializer(() => inject(ViewportScroller).setOffset([0, APP_BAR_HEIGHT])),
    // Root derived from <base href>, so the SPA works under any servlet context path
    // (/mztabvalidator, /mztabvalidator-dev, ...) and with ng serve's proxy (<base href="/">).
    provideApiRoot(),
  ],
};
