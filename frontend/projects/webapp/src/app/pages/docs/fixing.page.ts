import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { QUICK_FIX_PREVIEW_LABEL, quickFixAllLabel } from '../../core/quick-fix-labels';
import { AppInfoStore } from '../../core/app-info.store';
import { EDIT_LINE_AS_TEXT } from '../../core/editor-labels';

/** "Fixing a file": how to read a result, fix a file in the editor, and what is kept and for how long. */
@Component({
  selector: 'app-fixing-page',
  imports: [RouterLink],
  templateUrl: './fixing.page.html',
  styleUrl: './fixing.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FixingPage {
  protected readonly previewLabel = QUICK_FIX_PREVIEW_LABEL;
  protected readonly allLabel = quickFixAllLabel('N');
  protected readonly editLineAsTextLabel = EDIT_LINE_AS_TEXT;
  protected readonly info = inject(AppInfoStore).info;
}
