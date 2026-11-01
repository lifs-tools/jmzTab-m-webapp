import { computed, inject, Injectable, Injector, runInInjectionContext, Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, Observable, of, shareReplay } from 'rxjs';

import { ExampleDto } from '../api/models/example-dto';
import { TourDto } from '../api/models/tour-dto';
import { ExamplesService } from '../api/services/examples.service';
import { quietRequest } from './http-error.interceptor';

/** The tutorial examples of the documentation: their files, tours and what this instance offers. */
@Injectable({ providedIn: 'root' })
export class TutorialStore {
  private readonly api = inject(ExamplesService);
  private readonly injector = inject(Injector);
  private readonly tours = new Map<string, Observable<TourDto>>();
  private readonly files = new Map<string, Observable<readonly string[]>>();
  private listed?: Signal<ReadonlyMap<string, ExampleDto> | undefined>;
  private offeredIds?: Signal<ReadonlySet<string> | undefined>;

  /** The steps of an example; requested once, a failed request is tried again by the next caller. */
  tour(exampleId: string): Observable<TourDto> {
    let tour = this.tours.get(exampleId);
    if (!tour) {
      tour = this.api.getExampleTour({ id: exampleId }, quietRequest()).pipe(shareReplay(1));
      this.tours.set(exampleId, tour);
    }
    return tour;
  }

  /** The lines of an example's file; line 1 is `lines[0]`. */
  lines(exampleId: string): Observable<readonly string[]> {
    let file = this.files.get(exampleId);
    if (!file) {
      file = this.api.getExampleContent({ id: exampleId }, quietRequest()).pipe(
        map((text) => {
          const lines = text.split(/\r?\n/);
          if (lines[lines.length - 1] === '') lines.pop();
          return lines;
        }),
        shareReplay(1),
      );
      this.files.set(exampleId, file);
    }
    return file;
  }

  /**
   * The tutorial examples that this instance lists by id; undefined until the list has arrived. An example
   * whose engine is not available is not listed, so it is neither offered for validation nor in `examples`.
   */
  examples(): Signal<ReadonlyMap<string, ExampleDto> | undefined> {
    this.listed ??= runInInjectionContext(this.injector, () =>
      toSignal(
        this.api.listExamples(undefined, quietRequest()).pipe(
          map(
            (list) =>
              new Map(
                list.filter((e) => e.section === 'tutorial' && e.id).map((e) => [e.id!, e]),
              ) as ReadonlyMap<string, ExampleDto>,
          ),
          catchError(() => of(new Map<string, ExampleDto>() as ReadonlyMap<string, ExampleDto>)),
        ),
        { initialValue: undefined },
      ),
    );
    return this.listed;
  }

  /** The ids of the tutorial examples that can be validated here. */
  offered(): Signal<ReadonlySet<string> | undefined> {
    // read outside the computed: examples() creates its signal on first use
    const listed = this.examples();
    this.offeredIds ??= computed(() => {
      const ids = listed();
      return ids && new Set(ids.keys());
    });
    return this.offeredIds;
  }
}
