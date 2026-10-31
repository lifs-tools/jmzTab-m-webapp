import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import {
  DocEdit,
  DocMetadataGroup,
  DocMetadataPage,
  DocSection,
  DocTablePage,
  DocTableQuery,
  DocumentSource,
} from '@lifs-tools/mztabm-editor';

import { WorkingCopiesService } from '../../api/services/working-copies.service';
import { quietRequest } from '../../core/http-error.interceptor';
import { EditOps, WorkingCopyStore } from './working-copy.store';

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

  override applyEdits(ops: DocEdit[]): Observable<void> {
    // DocEdit mirrors the server's EditOperation records field by field
    return this.store.edit(ops as unknown as EditOps).pipe(map(() => undefined));
  }
}
