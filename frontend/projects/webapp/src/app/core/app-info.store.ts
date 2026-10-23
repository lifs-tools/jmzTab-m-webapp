import { Injectable, Signal, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';

import { AppInfoDto } from '../api/models/app-info-dto';
import { AppInfoService } from '../api/services/app-info.service';

/**
 * Version, limits and analytics settings from `GET rest/v2/app-info`, loaded once.
 * Stays `undefined` while loading and when the request fails, so the shell still renders
 * (without analytics and retention-dependent bits).
 */
@Injectable({ providedIn: 'root' })
export class AppInfoStore {
  private readonly api = inject(AppInfoService);

  readonly info: Signal<AppInfoDto | undefined> = toSignal(
    this.api.getAppInfo().pipe(catchError(() => of(undefined))),
  );
}
