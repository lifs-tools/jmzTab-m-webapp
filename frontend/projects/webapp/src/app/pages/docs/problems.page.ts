import { ViewportScroller } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  Injector,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ActivatedRoute } from '@angular/router';
import { catchError, of } from 'rxjs';

import { GuidanceEntry } from '../../api/models/guidance-entry';
import { GuidanceService } from '../../api/services/guidance.service';
import { quietRequest } from '../../core/http-error.interceptor';

/** The public catalog of common problems, each with its explanation and how to fix it. */
@Component({
  selector: 'app-problems-page',
  imports: [MatProgressSpinnerModule],
  templateUrl: './problems.page.html',
  styleUrl: './problems.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProblemsPage {
  private readonly loaded = toSignal(
    inject(GuidanceService)
      .listGuidance(undefined, quietRequest())
      .pipe(catchError(() => of(null))),
    { initialValue: undefined },
  );
  protected readonly loading = computed(() => this.loaded() === undefined);
  protected readonly failed = computed(() => this.loaded() === null);
  protected readonly entries = computed<GuidanceEntry[]>(() => this.loaded() ?? []);

  constructor() {
    // A link to an entry (/docs/problems#id): the router scrolled at NavigationEnd, before the
    // catalog arrived, so scroll again once its entries are in the page.
    const fragment = inject(ActivatedRoute).snapshot.fragment;
    const scroller = inject(ViewportScroller);
    const injector = inject(Injector);
    effect(() => {
      if (fragment && this.loaded()) {
        afterNextRender(() => scroller.scrollToAnchor(fragment), { injector });
      }
    });
  }
}
