import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/** Says which column names a table header holds more than once and, where it can, offers to resolve each. */
@Component({
  selector: 'mztabm-duplicate-note',
  template: `
    <div class="dup-note" role="note">
      <p>
        {{ names().length === 1 ? 'A column name appears' : 'Column names appear' }} more than once in this table;
        the engine cannot tell these columns apart.
      </p>
      <ul>
        @for (name of names(); track name) {
          <li>
            <code>{{ name }}</code>
            @if (canResolve()) {
              <button type="button" class="resolve-dup" [attr.aria-label]="'Resolve duplicate column ' + name" (click)="resolve.emit(name)">
                Resolve duplicate column
              </button>
            }
          </li>
        }
      </ul>
    </div>
  `,
  styles: `
    .dup-note {
      margin: 0 0 10px;
      padding: 8px 12px;
      border-left: 4px solid var(--mztabm-color-warn);
      background: color-mix(in srgb, var(--mztabm-color-warn) 8%, var(--mztabm-color-surface));
      font-family: var(--mztabm-font-family);
      font-size: 13px;
      color: var(--mztabm-color-text);
    }
    p,
    ul {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    li {
      margin-top: 6px;
    }
    code {
      font-family: var(--mztabm-font-mono);
      margin-right: 8px;
    }
    button.resolve-dup {
      border: 1px solid var(--mztabm-color-primary);
      background: var(--mztabm-color-surface);
      color: var(--mztabm-color-primary);
      padding: 4px 10px;
      font: inherit;
      font-size: 12px;
      cursor: pointer;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DuplicateNoteComponent {
  readonly names = input.required<readonly string[]>();
  readonly canResolve = input(false);
  readonly resolve = output<string>();
}
