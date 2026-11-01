import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** The short "About mzTab-M" section below the validator form: what the format is, what it is for, who uses it. */
@Component({
  selector: 'app-about-mztabm',
  imports: [RouterLink],
  templateUrl: './about-mztabm.component.html',
  styleUrl: './about-mztabm.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AboutMzTabmComponent {}
