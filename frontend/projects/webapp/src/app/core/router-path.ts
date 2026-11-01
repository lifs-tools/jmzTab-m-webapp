import { inject, Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';

/** The router's current path, without query or fragment, updated after every navigation. Injection context only. */
export function routerPath(): Signal<string> {
  const router = inject(Router);
  const path = () => router.url.split(/[?#]/)[0];
  return toSignal(
    router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(path),
    ),
    { initialValue: path() },
  );
}
