import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AppInfoService } from '../api/services/app-info.service';
import { apiRootFromBase, provideApiRoot } from './api-root';

describe('provideApiRoot', () => {
  let base: HTMLBaseElement | undefined;

  afterEach(() => {
    base?.remove();
    base = undefined;
  });

  function useBase(href: string): void {
    base = document.createElement('base');
    base.href = href;
    document.head.prepend(base);
  }

  function appInfoRequestUrl(): string {
    TestBed.configureTestingModule({
      providers: [provideApiRoot(), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(AppInfoService).getAppInfo().subscribe();
    const req = TestBed.inject(HttpTestingController).expectOne(() => true);
    req.flush({});
    return req.request.url;
  }

  it('calls the API under the base href context path', () => {
    useBase('/mztabvalidator/');
    expect(appInfoRequestUrl()).toBe(`${location.origin}/mztabvalidator/rest/v2/app-info`);
  });

  it('calls /rest at the root with <base href="/"> (dev server proxy)', () => {
    useBase('/');
    expect(appInfoRequestUrl()).toBe(`${location.origin}/rest/v2/app-info`);
  });

  it('derives the root from a base URI without the trailing slash', () => {
    expect(apiRootFromBase('https://h/mztabvalidator-dev/')).toBe('https://h/mztabvalidator-dev');
    expect(apiRootFromBase('https://h/mztab-validator/index.html')).toBe(
      'https://h/mztab-validator',
    );
    expect(apiRootFromBase('https://h/')).toBe('https://h');
  });
});
