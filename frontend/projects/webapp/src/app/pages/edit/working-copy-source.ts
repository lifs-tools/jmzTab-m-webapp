import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, throwError } from 'rxjs';
import {
  DocDuplicateColumn,
  DocEdit,
  DocMetadataGroup,
  DocMetadataPage,
  DocSection,
  DocTablePage,
  DocTableHeader,
  DocTableQuery,
  DocumentSource,
} from '@lifs-tools/mztabm-editor';

import { WorkingCopiesService } from '../../api/services/working-copies.service';
import { quietRequest } from '../../core/http-error.interceptor';
import { EditOps, WorkingCopyStore, serverMessage } from './working-copy.store';

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
    return this.api.listWorkingCopyMetadataGroups({ id: this.id }, quietRequest());
  }

  metadataPage(group: string, page: number, size: number, key?: string): Observable<DocMetadataPage> {
    return this.api.getWorkingCopyMetadataPage({ id: this.id, group, page, size, ...(key ? { key } : {}) }, quietRequest());
  }

  tablePage(section: DocSection, q: DocTableQuery): Observable<DocTablePage> {
    return this.api.getWorkingCopyTablePage(
      {
        id: this.id,
        section,
        page: q.page,
        size: q.size,
        ...(q.sort ? { sort: q.sort, descending: q.descending ?? false } : {}),
        // one comma-joined value: the server splits it, and the URL stays short
        ...(q.columns ? { columns: [q.columns.join(',')] } : {}),
        ...(q.filters?.length ? { filter: q.filters.map((f) => `${f.column}:${f.text}`) } : {}),
      },
      quietRequest(),
    );
  }

  /**
   * Edits go through the store. A refused edit fails with its `status` and, for a 400, the server's reason as
   * `detail`, which the views show.
   */
  override applyEdits(ops: DocEdit[]): Observable<void> {
    const columns = ops.some((o) => COLUMN_OPS.has(o.op));
    // DocEdit mirrors the server's EditOperation records field by field
    return this.store.edit(ops as unknown as EditOps, columns).pipe(
      map(() => undefined),
      catchError((err: unknown) => {
        const status = err instanceof HttpErrorResponse ? err.status : undefined;
        return throwError(() => ({ status, detail: status === 400 ? serverMessage(err) : null }));
      }),
    );
  }

  override duplicateColumns(header: DocTableHeader): Observable<DocDuplicateColumn[]> {
    return this.api.getWorkingCopyDuplicateColumns({ id: this.id, header }, quietRequest());
  }
}
