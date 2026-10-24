import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';

import { MatomoService } from '../core/matomo.service';

/** The footer's Analytics choice: allow or refuse anonymous usage statistics (Matomo). */
@Component({
  selector: 'app-analytics-consent-dialog',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>Usage statistics</h2>
    <mat-dialog-content>
      <p class="lifs-note">
        This site can count visits with Matomo to learn how the validator is used. Nothing is
        tracked unless you allow it, and you can change your choice here at any time. See the
        <a href="https://lifs-tools.org/imprint-privacy-policy.html" target="_blank" rel="noopener"
          >Imprint &amp; Privacy Policy</a
        >.
      </p>
      <p id="consent-question" class="question">Allow anonymous usage statistics?</p>
      <p class="lifs-label" aria-live="polite">
        Current choice: {{ given() ? 'allowed' : 'not allowed' }}
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end" role="group" aria-labelledby="consent-question">
      <button mat-stroked-button type="button" (click)="choose(false)">No</button>
      <button mat-flat-button type="button" (click)="choose(true)">Yes</button>
    </mat-dialog-actions>
  `,
  styles: `
    .question {
      font-weight: 500;
      color: var(--lifs-ink);
      margin: 16px 0 6px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnalyticsConsentDialogComponent {
  private readonly matomo = inject(MatomoService);
  private readonly dialogRef = inject(MatDialogRef<AnalyticsConsentDialogComponent>);

  protected readonly given = signal(this.matomo.consentGiven());

  protected choose(given: boolean): void {
    this.matomo.setConsent(given);
    this.given.set(given);
    this.dialogRef.close(given);
  }
}
