import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** "For tools and services": the REST API, the Python and R libraries, and self-hosting. */
@Component({
  selector: 'app-integration-page',
  imports: [RouterLink],
  templateUrl: './integration.page.html',
  styleUrl: './integration.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntegrationPage {}
