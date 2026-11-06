import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DocMetadataGroup, DocMetadataPage, DocSection, DocTablePage, DocTableQuery, DocumentSource } from '@lifs-tools/mztabm-editor';

import { ValidationsService } from '../../api/services/validations.service';
import { quietRequest } from '../../core/http-error.interceptor';

/**
 * The document of a validation result, read-only: the library views get their data through the result's
 * metadata and table endpoints. The result page sets `validationId` when it has the record.
 */
@Injectable()
export class ResultDocumentSource extends DocumentSource {
  private readonly api = inject(ValidationsService);
  readonly editable = false;
  validationId = '';

  metadataGroups(): Observable<DocMetadataGroup[]> {
    return this.api.listMetadataGroups({ id: this.validationId }, quietRequest());
  }

  metadataPage(group: string, page: number, size: number, key?: string): Observable<DocMetadataPage> {
    return this.api.getMetadataPage({ id: this.validationId, group, page, size, ...(key ? { key } : {}) }, quietRequest());
  }

  tablePage(section: DocSection, q: DocTableQuery): Observable<DocTablePage> {
    return this.api.getTablePage(
        {
          id: this.validationId,
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
}
