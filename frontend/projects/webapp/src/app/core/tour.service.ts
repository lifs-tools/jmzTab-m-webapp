import { Injectable, inject } from '@angular/core';
import { NavigationStart, Router } from '@angular/router';
import { Observable, filter, from, map, switchMap, takeUntil, tap } from 'rxjs';

import { ExamplesService } from '../api/services/examples.service';

/** The tour that "Take the tour" starts: the lipidomics teaching file on the 2.0 line. */
export const INTRO_TOUR = 'teaching-lipidomics-2.0.mztab';

/**
 * An example id, as the server accepts it in a path, except ids of dots only: `.` and `..` would make
 * `rest/v2/examples/../validations` another endpoint once the browser normalises the path.
 */
export const TOUR_ID_PATTERN = /^(?!\.+$)[A-Za-z0-9._-]{1,100}$/;

/**
 * Starts the tour of a tutorial example: validates the example and opens its result at a step,
 * `/result/<id>?tour=<exampleId>&step=<step>`. The result page shows the tour panel from there.
 */
@Injectable({ providedIn: 'root' })
export class TourService {
  private readonly api = inject(ExamplesService);
  private readonly router = inject(Router);
  /** The validations this session started for a tour; an expired one is not started again (no loop). */
  private readonly created = new Set<string>();

  /**
   * `step` is 1-based; `replaceUrl` keeps an expired result out of the history when the tour restarts.
   * A navigation that starts while the example is validated (the user went elsewhere) abandons the tour.
   */
  start(exampleId: string, step = 1, replaceUrl = false): Observable<void> {
    return this.api.validateExample({ id: exampleId, body: { level: 'INFO' } }).pipe(
      takeUntil(this.router.events.pipe(filter((e) => e instanceof NavigationStart))),
      tap((record) => record.id && this.created.add(record.id)),
      switchMap((record) =>
        from(this.router.navigate(['/result', record.id], { queryParams: { tour: exampleId, step }, replaceUrl })),
      ),
      map(() => undefined),
    );
  }

  /** Whether this session's tour created the validation, so that it is not restarted when it is gone. */
  createdByTour(validationId: string): boolean {
    return this.created.has(validationId);
  }
}
