import { DOCUMENT, FactoryProvider, inject } from '@angular/core';

import { ApiConfiguration } from '../api/api-configuration';

/**
 * The API root for a document base URI: its directory without the trailing slash, so the
 * generated paths (`/rest/v2/...`) land under the servlet context path, e.g.
 * `https://h/mztabvalidator/` -> `https://h/mztabvalidator` -> `.../mztabvalidator/rest/v2/...`.
 */
export function apiRootFromBase(baseUri: string): string {
  return new URL('.', baseUri).href.replace(/\/$/, '');
}

/** Provides the generated client's ApiConfiguration with a root derived from `<base href>`. */
export function provideApiRoot(): FactoryProvider {
  return {
    provide: ApiConfiguration,
    useFactory: () => {
      const config = new ApiConfiguration();
      config.rootUrl = apiRootFromBase(inject(DOCUMENT).baseURI);
      return config;
    },
  };
}
