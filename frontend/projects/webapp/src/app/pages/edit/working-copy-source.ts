import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, defer, map, throwError } from 'rxjs';
import {
  DocDuplicateColumn,
  DocEdit,
  DocEditOptions,
  DocMetadataGroup,
  DocMetadataPage,
  DocSection,
  DocTablePage,
  DocTableHeader,
  DocTableQuery,
  DocumentSource,
  ImportPreviewView,
  ImportRequestView,
  ImportSource,
  StillMissingView,
} from '@lifs-tools/mztabm-editor';

import { JsonNode } from '../../api/models/json-node';
import { WorkingCopiesService } from '../../api/services/working-copies.service';
import { editorRequest, engineRequest, retryReads } from '../../core/request-timeout.interceptor';
import { EditOps, LAYOUT_OPS, MaybeSavedError, StoredImportPreview, WorkingCopyStore, serverMessage } from './working-copy.store';

/** Edits that change the columns: every view (and the page's cached headers) must read the table again. */
const COLUMN_OPS = new Set(['InsertColumn', 'DeleteColumn', 'RenameColumn', 'MergeColumns']);


/**
 * The working copy as the library's views see it: editable. Pages come from the copy's metadata and table
 * endpoints (rows carry their line); edits go through the store, which queues them and validates after a pause.
 */
@Injectable()
export class WorkingCopySource extends DocumentSource {
  private readonly api = inject(WorkingCopiesService);
  private readonly store = inject(WorkingCopyStore);
  readonly editable = true;
  override readonly changed = this.store.changed.asObservable();

  private get id(): string {
    return this.store.id();
  }

  metadataGroups(): Observable<DocMetadataGroup[]> {
    return this.api.listWorkingCopyMetadataGroups({ id: this.id }, editorRequest()).pipe(retryReads());
  }

  metadataPage(group: string, page: number, size: number, key?: string): Observable<DocMetadataPage> {
    return this.stamped(() =>
      this.api.getWorkingCopyMetadataPage({ id: this.id, group, page, size, ...(key ? { key } : {}) }, editorRequest()),
    );
  }

  /**
   * Each page carries `readAt`: the revision of the copy's last answer when the read was made. The server may
   * answer with a newer text (an edit was on its way), never an older one, so a line number of the page is valid
   * at least from `readAt` on; the store refuses an edit of it when the layout (lines, columns) changed since.
   */
  private stamped<T extends object>(read: () => Observable<T>): Observable<T & { readAt: number }> {
    // a read asked again (no answer) is stamped anew: the revision known when it is sent
    return defer(() => {
      const readAt = this.store.currentRevision();
      return read().pipe(map((page) => ({ ...page, readAt })));
    }).pipe(retryReads());
  }

  tablePage(section: DocSection, q: DocTableQuery): Observable<DocTablePage> {
    return this.stamped(() => this.api.getWorkingCopyTablePage(
      {
        id: this.id,
        section,
        page: q.page,
        size: q.size,
        ...(q.sortPosition
          ? { sortPosition: q.sortPosition, descending: q.descending ?? false }
          : q.sort
            ? { sort: q.sort, descending: q.descending ?? false }
            : {}),
        // positions tell apart columns of the same name, so they replace the names whenever the view has them
        ...(q.positions ? { positions: q.positions } : {}),
        // one comma-joined value: the server splits it, and the URL stays short
        ...(q.columns && !q.positions ? { columns: [q.columns.join(',')] } : {}),
        ...(filtersByName(q).length ? { filter: filtersByName(q).map((f) => `${f.column}:${f.text}`) } : {}),
        ...(filtersByPosition(q).length
          ? { positionFilter: filtersByPosition(q).map((f) => `${f.position}:${f.text}`) }
          : {}),
      },
      editorRequest(),
    ));
  }

  /**
   * Edits go through the store. A refused edit fails with its `status` and, for a 400, the server's reason as
   * `detail`, which the views show.
   */
  override applyEdits(ops: DocEdit[], options?: DocEditOptions): Observable<void> {
    // a raw line may become (or stop being) a row or an entry the views show
    const reload = ops.some((o) => COLUMN_OPS.has(o.op) || LAYOUT_OPS.has(o.op));
    // DocEdit mirrors the server's EditOperation records field by field
    return this.store.edit(ops as unknown as EditOps, reload, options?.readAt ?? null).pipe(
      map(() => undefined),
      catchError((err: unknown) => {
        const status = err instanceof HttpErrorResponse ? err.status : undefined;
        const detail = status === 400 ? serverMessage(err) : null;
        // the store reloaded and said that the change may or may not have been saved: not a plain conflict
        return throwError(() => (err instanceof MaybeSavedError ? { status, detail, maybeSaved: true } : { status, detail }));
      }),
    );
  }

  override duplicateColumns(header: DocTableHeader): Observable<DocDuplicateColumn[]> {
    // stamped like a page: the edits that resolve the list pass the stamp back
    return defer(() => {
      const readAt = this.store.currentRevision();
      return this.api
        .getWorkingCopyDuplicateColumns({ id: this.id, header }, editorRequest())
        .pipe(map((list) => list.map((d) => ({ ...d, readAt }))));
    }).pipe(retryReads());
  }
}

function filtersByName(q: DocTableQuery) {
  return (q.filters ?? []).filter((f) => f.position == null);
}

function filtersByPosition(q: DocTableQuery) {
  return (q.filters ?? []).filter((f) => f.position != null);
}

/** A failure as the library's sources pass it on: the HTTP status and the server's reason, if it gave one. */
function toDocError(err: unknown): Observable<never> {
  const status = err instanceof HttpErrorResponse ? err.status : undefined;
  return throwError(() => ({ status, detail: serverMessage(err) }));
}

/**
 * The working copy as the library's Add-from panel sees it: the kinds of import of its version, previews (at the
 * revision the store knows), applying a preview's batch through the store's queue, and the "still missing" rules.
 */
@Injectable()
export class WorkingCopyImportSource extends ImportSource {
  private readonly api = inject(WorkingCopiesService);
  private readonly store = inject(WorkingCopyStore);

  override importKinds(): Observable<string[]> {
    return this.api
      .listWorkingCopyImportKinds({ id: this.store.id() }, editorRequest())
      .pipe(retryReads(), catchError(toDocError));
  }

  override previewImport(req: ImportRequestView): Observable<ImportPreviewView> {
    return this.store
      .previewImport({ ...req, instance: req.instance as JsonNode })
      .pipe(catchError(toDocError));
  }

  override applyImport(p: ImportPreviewView): Observable<'applied' | 'stale'> {
    // the view is the store's preview as it came: the server's batch, and the generation it was made of
    return this.store.applyImport(p as StoredImportPreview).pipe(catchError(toDocError));
  }

  override stillMissing(): Observable<StillMissingView> {
    // decided by the engine: given 120 s, and not asked again by itself (the panel's Try again does)
    return this.api.getWorkingCopyStillMissing({ id: this.store.id() }, engineRequest()).pipe(catchError(toDocError));
  }
}
