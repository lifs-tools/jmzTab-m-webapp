import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, ElementRef, Injector, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  DocumentSource,
  MetadataHighlight,
  MetadataViewComponent,
  RawLineEditorComponent,
  TableHighlight,
  TableViewComponent,
} from '@lifs-tools/mztabm-editor';
import { map } from 'rxjs';

import { InsertLine } from '../../api/models/insert-line';
import { Line } from '../../api/models/line';
import { QuickFixPreview } from '../../api/models/quick-fix-preview';
import { ValidationMessage } from '../../api/models/validation-message';
import { WorkingCopyInfo } from '../../api/models/working-copy-info';
import { WorkingCopiesService } from '../../api/services/working-copies.service';
import { AppInfoStore } from '../../core/app-info.store';
import { quietRequest } from '../../core/http-error.interceptor';
import { QuickFixState } from '../result/guidance-panel.component';
import { DocTab, DocTarget, groupOf, targetOf } from '../result/message-groups';
import { FixRequest, MessagesTabComponent, MessagesViewState, ShowRequest } from '../result/messages-tab.component';
import { QuickFixDialogComponent } from './quick-fix-dialog.component';
import { WorkingCopySource } from './working-copy-source';
import { NotCheckedYet, WorkingCopyStore } from './working-copy.store';

const DAY_MS = 86_400_000;
const PREFIXES = ['SML', 'SMF', 'SME'] as const;
const SECTION_OF: Record<(typeof PREFIXES)[number], 'sml' | 'smf' | 'sme'> = { SML: 'sml', SMF: 'smf', SME: 'sme' };
const count = (n: number | undefined) => (n ?? 0).toLocaleString('en');

/** A line the metadata and table views cannot show, edited as raw text. */
interface RawLine {
  lineNumber: number;
  text: string;
}

/**
 * Whether a line opens the raw line editor: a line without a valid prefix, a blank line between two lines of
 * the same section, or an MTD line with only a key (2 cells) or more than key and value (more than 3 cells
 * before the trailing empty ones, which tools often write and the engine accepts).
 */
export function isUnreadable(line: Line, prev?: Line, next?: Line): boolean {
  const cells = line.cells ?? [];
  if (line.section === '?') return true;
  if (line.section === 'BLANK') {
    return !!prev && !!next && prev.section === next.section && prev.section !== 'BLANK' && prev.section !== 'COM';
  }
  if (line.section === 'MTD') {
    let used = cells.length;
    while (used > 0 && cells[used - 1] === '') used--;
    return cells.length === 2 || used > 3;
  }
  return false;
}

/**
 * The editor of a working copy (`edit/:id`, the id of the validation it is made from): messages on the left,
 * the document on the right, both live. Edits are saved as they are made and checked after a pause.
 */
@Component({
  selector: 'app-edit-page',
  imports: [
    DatePipe,
    RouterLink,
    MatButtonModule,
    MatProgressBarModule,
    MessagesTabComponent,
    MetadataViewComponent,
    TableViewComponent,
    RawLineEditorComponent,
  ],
  templateUrl: './edit.page.html',
  styleUrl: './edit.page.scss',
  providers: [
    WorkingCopyStore,
    WorkingCopySource,
    { provide: DocumentSource, useExisting: WorkingCopySource },
    MessagesViewState,
  ],
  host: {
    '(document:keydown)': 'onShortcut($event)',
    '(input)': 'store.activity()',
  },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EditPage {
  protected readonly store = inject(WorkingCopyStore);
  private readonly api = inject(WorkingCopiesService);
  private readonly appInfo = inject(AppInfoStore);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly info = this.store.info;
  protected readonly validation = this.store.validation;
  /** Relative to `<base href>`, so the links work under any servlet context path. */
  protected readonly base = computed(() => `rest/v2/working-copies/${this.store.id()}`);
  protected readonly reportUrl = computed(() => `rest/v2/validations/${this.store.id()}/report?format=md`);

  /** "Kept for N days after your last change": N is the server's retention, else what is left of it. */
  protected readonly retentionDays = computed(() => {
    const days = this.appInfo.info()?.retentionDays;
    if (days) return days;
    const expires = this.info()?.expiresAt;
    return expires ? Math.max(1, Math.round((Date.parse(expires) - Date.now()) / DAY_MS)) : null;
  });

  protected readonly messages = computed<ValidationMessage[]>(() =>
    this.store.messages().map((t) => t.message ?? { category: 'format', code: '', message: '' }),
  );
  protected readonly states = computed(() => this.store.messages().map((t) => t.state));
  /** A failed request for a check leaves the buttons usable: a preview then asks for the check again. */
  protected readonly quickFixState = computed<QuickFixState>(() =>
    this.store.current() || this.store.checkFailed() ? 'ready' : 'checking',
  );
  protected readonly readUpTo = computed(() => {
    const v = this.validation();
    return v && !v.parsed && v.readUpTo ? v.readUpTo : null;
  });
  protected readonly failure = computed(() => {
    const v = this.validation();
    return v?.status === 'FAILED' ? (v.failureReason ?? 'The file could not be checked.') : null;
  });
  /** Only for a check of the text as it is now: a run of an older text, whose recheck failed, says nothing. */
  protected readonly noProblems = computed(() => {
    const v = this.validation();
    return (
      !this.store.validating() &&
      this.store.current() &&
      !this.store.checkFailed() &&
      v?.status === 'DONE' &&
      !!v.parsed &&
      !(v.messages ?? []).length
    );
  });

  protected readonly tabs = computed(() => {
    const s = this.info()?.sections;
    const table = (id: DocTab, label: string, size?: { rows?: number; columns?: number } | null) => ({
      id,
      label,
      count: size ? `${count(size.rows)} × ${count(size.columns)}` : '—',
      disabled: !size,
    });
    return [
      { id: 'metadata' as DocTab, label: 'Metadata', count: `${count(s?.metadataLines)} lines`, disabled: false },
      table('summary', 'Summary', s?.sml),
      table('features', 'Features', s?.smf),
      table('evidence', 'Evidence', s?.sme),
    ];
  });
  protected readonly docTab = signal<DocTab>('metadata');
  protected readonly metadataHighlight = signal<MetadataHighlight | null>(null);
  /** The table highlight and the tab it belongs to. */
  private readonly tableJump = signal<{ tab: DocTab; highlight: TableHighlight } | null>(null);
  protected readonly tableHighlight = computed(() => {
    const j = this.tableJump();
    return j && j.tab === this.docTab() ? j.highlight : null;
  });
  protected readonly selected = signal<string | null>(null);
  protected readonly rawLine = signal<RawLine | null>(null);

  /** Table headers by prefix, for the cells of quoted data lines; read for the sections the guidance quotes. */
  protected readonly headers = signal<ReadonlyMap<string, readonly string[]>>(new Map());
  private readonly quotedPrefixes = computed(() => {
    const found = new Set<string>();
    for (const g of this.store.guidance()) {
      for (const l of g.lines ?? []) {
        const prefix = (l.text ?? '').split('\t', 1)[0];
        if ((PREFIXES as readonly string[]).includes(prefix)) found.add(prefix);
      }
    }
    return [...found];
  });
  private readonly headersAsked = new Set<string>();
  private inspection = 0;

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(
        map((p) => p.get('id') ?? ''),
        takeUntilDestroyed(),
      )
      .subscribe((id) => {
        this.rawLine.set(null);
        this.metadataHighlight.set(null);
        this.tableJump.set(null);
        this.selected.set(null);
        this.store.open(id);
      });
    this.store.notices.pipe(takeUntilDestroyed()).subscribe((text) => this.snackBar.open(text, 'Dismiss', { duration: 8000 }));
    this.store.changed.pipe(takeUntilDestroyed()).subscribe(() => {
      // columns may have been added or removed
      this.headersAsked.clear();
      this.headers.set(new Map());
    });
    effect(() => {
      const prefixes = this.quotedPrefixes();
      const id = this.store.id();
      untracked(() => prefixes.forEach((p) => this.readHeader(id, p as (typeof PREFIXES)[number])));
    });
  }

  private readHeader(id: string, prefix: (typeof PREFIXES)[number]): void {
    if (!id || this.headersAsked.has(prefix)) return;
    this.headersAsked.add(prefix);
    this.api
      .getWorkingCopyTablePage({ id, section: SECTION_OF[prefix], page: 0, size: 1 }, quietRequest())
      .subscribe({
        next: (page) => this.headers.update((h) => new Map(h).set(prefix, page.allColumns ?? [])),
        error: () => undefined,
      });
  }

  // ---- the document ----

  protected sectionOf(tab: DocTab): 'sml' | 'smf' | 'sme' {
    return tab === 'features' ? 'smf' : tab === 'evidence' ? 'sme' : 'sml';
  }

  /** A tab chosen by hand drops the jump, so the view shown does not replay it. */
  protected select(tab: DocTab): void {
    if (tab === this.docTab()) return;
    this.metadataHighlight.set(null);
    this.tableJump.set(null);
    this.docTab.set(tab);
  }

  protected onTabKey(event: KeyboardEvent): void {
    const enabled = this.tabs().filter((t) => !t.disabled);
    const i = enabled.findIndex((t) => t.id === this.docTab());
    let next = -1;
    if (event.key === 'ArrowRight') next = (i + 1) % enabled.length;
    else if (event.key === 'ArrowLeft') next = (i - 1 + enabled.length) % enabled.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = enabled.length - 1;
    if (next < 0) return;
    event.preventDefault();
    const id = enabled[next].id;
    this.select(id);
    this.host.nativeElement.querySelector<HTMLElement>(`#edit-tab-${id}`)?.focus();
  }

  /** A message chip: show its place in the document, and its line in the raw editor when no view can show it. */
  protected onShow(req: ShowRequest): void {
    const m = req.group.messages[req.index];
    if (!m) return;
    this.selected.set(req.group.key);
    const target = targetOf(m);
    if (target) this.jump(target, req);
    const line = m.line_number;
    if (line != null && line > 0) this.inspect(line, !target);
    else this.rawLine.set(null);
  }

  private jump(t: DocTarget, req: ShowRequest): void {
    const targets = req.all ? req.group.messages.map(targetOf).filter((o): o is DocTarget => !!o) : [t];
    this.docTab.set(t.tab);
    if (t.tab === 'metadata') {
      if (!t.group) return;
      const keys = targets.filter((o) => o.tab === 'metadata' && o.group === t.group && o.key).map((o) => o.key!);
      this.tableJump.set(null);
      this.metadataHighlight.set({ group: t.group, keys, focusKey: t.key ?? null, moveFocus: true });
      return;
    }
    const rows = targets.filter((o) => o.tab === t.tab && o.rowIndex != null).map((o) => o.rowIndex!);
    this.metadataHighlight.set(null);
    this.tableJump.set({
      tab: t.tab,
      highlight: { rows, focusRow: t.rowIndex ?? null, column: t.column ?? null, header: !!t.header, moveFocus: true },
    });
  }

  /**
   * Reads the line (and its neighbours) of a message. A line the views cannot show opens the raw line editor; an
   * MTD line of a message without a document target is shown in the metadata.
   */
  private inspect(n: number, jumpByLine: boolean): void {
    const run = ++this.inspection;
    const from = Math.max(1, n - 1);
    this.api.getWorkingCopyLines({ id: this.store.id(), from, count: n - from + 2 }, quietRequest()).subscribe({
      next: (lines) => {
        if (run !== this.inspection) return;
        const at = (k: number) => lines.find((l) => l.number === k);
        const line = at(n);
        if (!line) {
          this.rawLine.set(null);
          return;
        }
        if (isUnreadable(line, at(n - 1), at(n + 1))) {
          this.rawLine.set({ lineNumber: n, text: (line.cells ?? []).join('\t') });
          return;
        }
        this.rawLine.set(null);
        const key = line.cells?.[1];
        if (jumpByLine && line.section === 'MTD' && key) {
          this.docTab.set('metadata');
          this.tableJump.set(null);
          this.metadataHighlight.set({ group: groupOf(key), keys: [key], focusKey: key, moveFocus: true });
        }
      },
      error: () => {
        if (run === this.inspection) this.rawLine.set(null);
      },
    });
  }

  protected onRawSaved(): void {
    this.rawLine.set(null);
    this.store.changed.next();
  }

  // ---- quick fixes ----

  protected onFix(req: FixRequest): void {
    this.store.previewFix(req.fixId, req.messageIndex, req.all).subscribe({
      next: (preview) => this.openPreview(preview),
      error: (err: unknown) => {
        // not checked yet: the buttons say so, and a check has been asked for
        if (err instanceof NotCheckedYet) return;
        const message = err instanceof HttpErrorResponse && err.status === 400 ? err.error?.message : null;
        this.snackBar.open(message || 'The quick fix could not be prepared. Please try again.', 'Dismiss', {
          duration: 8000,
        });
      },
    });
  }

  private openPreview(preview: QuickFixPreview): void {
    const ref = this.dialog.open<QuickFixDialogComponent, QuickFixPreview, WorkingCopyInfo | null>(
      QuickFixDialogComponent,
      { data: preview, injector: this.injector, width: '680px', maxWidth: 'calc(100vw - 32px)' },
    );
    ref.afterClosed().subscribe((info) => {
      if (info && preview.fixId === 'add-missing-metadata') this.editInserted(preview);
    });
  }

  /** The inserted metadata line has no value on purpose: open it for the user to type it. */
  private editInserted(preview: QuickFixPreview): void {
    const insert = preview.batch?.operations?.find((o) => o.op === 'InsertLine') as InsertLine | undefined;
    const key = insert?.text?.split('\t')[1];
    if (!key) return;
    this.rawLine.set(null);
    this.tableJump.set(null);
    this.docTab.set('metadata');
    this.metadataHighlight.set({ group: groupOf(key), keys: [key], focusKey: key, moveFocus: true, edit: true });
  }

  // ---- undo, redo, downloads ----

  /**
   * Ctrl+Z undoes and Ctrl+Shift+Z redoes, unless the focus is in a field (where they edit its text) or a dialog
   * is open (a preview or a column resolution would then describe a text that changed under it).
   */
  protected onShortcut(event: KeyboardEvent): void {
    if (!(event.ctrlKey || event.metaKey) || event.altKey || event.key.toLowerCase() !== 'z') return;
    if (this.dialog.openDialogs.length) return;
    const target = event.target as HTMLElement | null;
    if (target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) return;
    if (this.store.view() !== 'ready') return;
    event.preventDefault();
    if (event.shiftKey) this.store.redo();
    else this.store.undo();
  }

  protected downloadJson(): void {
    const name = (this.info()?.fileName ?? 'file').replace(/\.[^.]*$/, '') + '.json';
    this.store.downloadJson().subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = name;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 0);
      },
      error: () => undefined,
    });
  }
}
