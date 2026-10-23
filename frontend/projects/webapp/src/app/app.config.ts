import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { provideApiRoot } from './core/api-root';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(),
    provideRouter(routes),
    // Root derived from <base href>, so the SPA works under any servlet context path
    // (/mztabvalidator, /mztabvalidator-dev, ...) and with ng serve's proxy (<base href="/">).
    provideApiRoot(),
  ],
};
