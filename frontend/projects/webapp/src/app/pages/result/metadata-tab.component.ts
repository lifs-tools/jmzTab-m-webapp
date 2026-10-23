import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { catchError, of, switchMap } from 'rxjs';

import { MetadataGroup } from '../../api/models/metadata-group';
import { MetadataPage } from '../../api/models/metadata-page';
import { ValidationsService } from '../../api/services/validations.service';
import { quietRequest } from '../../core/http-error.interceptor';

const PAGE_SIZE = 50;

/** Entries of one metadata group, paged by the server; created (and so loaded) only when its panel opens. */
@Component({
  selector: 'app-metadata-group',
  imports: [MatProgressBarModule],
  template: `
    @if (loading()) {
      <mat-progress-bar mode="indeterminate" class="bar" aria-label="Loading metadata" />
    }
    @if (error()) {
      <p class="lifs-note" role="alert">These entries could not be loaded.</p>
    }
    @if (data(); as d) {
      <dl class="entries">
        @for (e of d.entries; track e.lineNumber) {
          <div class="entry">
            <dt class="key">{{ e.key }}</dt>
            <dd class="value">{{ e.value }}</dd>
          </div>
        }
      </dl>
      @if (count() > pageSize) {
        <div class="pager">
          <span aria-live="polite">{{ range() }}</span>
          <button type="button" aria-label="Previous page" [disabled]="page() === 0" (click)="go(-1)">‹</button>
          <button type="button" aria-label="Next page" [disabled]="page() >= pages() - 1" (click)="go(1)">›</button>
        </div>
      }
    }
  `,
  styleUrl: './metadata-tab.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetadataGroupComponent {
  private readonly api = inject(ValidationsService);
  readonly validationId = input.required<string>();
  readonly group = input.required<string>();
  readonly count = input.required<number>();
  protected readonly pageSize = PAGE_SIZE;
  protected readonly page = signal(0);
  protected readonly error = signal(false);
  protected readonly data = signal<MetadataPage | undefined>(undefined);
  protected readonly loading = signal(true);
  protected readonly pages = computed(() => Math.max(1, Math.ceil(this.count() / PAGE_SIZE)));
  protected readonly range = computed(() => {
    const start = this.page() * PAGE_SIZE;
    return `${start + 1}–${Math.min(this.count(), start + PAGE_SIZE)} of ${this.count()}`;
  });

  constructor() {
    toObservable(computed(() => ({ id: this.validationId(), group: this.group(), page: this.page() })))
      .pipe(
        switchMap((q) => {
          this.loading.set(true);
          this.error.set(false);
          return this.api.getMetadataPage({ ...q, size: PAGE_SIZE }, quietRequest()).pipe(
            catchError(() => {
              this.error.set(true);
              return of(undefined);
            }),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((d) => {
        this.loading.set(false);
        if (d) this.data.set(d);
      });
  }

  protected go(delta: number): void {
    this.page.set(Math.min(Math.max(0, this.page() + delta), this.pages() - 1));
  }
}

/** One expansion panel per metadata group; the first group starts expanded. */
@Component({
  selector: 'app-metadata-tab',
  imports: [MatExpansionModule, MatProgressBarModule, MetadataGroupComponent],
  templateUrl: './metadata-tab.component.html',
  styleUrl: './metadata-tab.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetadataTabComponent {
  private readonly api = inject(ValidationsService);
  readonly validationId = input.required<string>();

  protected readonly groups = rxResource<MetadataGroup[], string>({
    params: () => this.validationId(),
    stream: ({ params }) => this.api.listMetadataGroups({ id: params }, quietRequest()),
  });
}
