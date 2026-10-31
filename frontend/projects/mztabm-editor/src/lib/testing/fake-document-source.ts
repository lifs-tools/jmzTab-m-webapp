import { Observable, Subject, Subscriber } from 'rxjs';

import {
  DocEdit,
  DocMetadataGroup,
  DocMetadataPage,
  DocSection,
  DocTablePage,
  DocTableQuery,
  DocumentSource,
} from '../document-source';

/** One request the views made, to be answered (or failed) by the test. */
export class Pending<A, T> {
  /** Unsubscribed before it was answered: a newer request replaced it. */
  cancelled = false;
  private settled = false;
  /** Taken by expectOne; a taken request is not matched again. */
  taken = false;

  constructor(
    readonly args: A,
    private readonly sub: Subscriber<T>,
  ) {}

  reply(value: T): void {
    this.settled = true;
    this.sub.next(value as T);
    this.sub.complete();
  }

  /** `status` is what the views look at, as on an HttpErrorResponse. */
  fail(status: number): void {
    this.settled = true;
    this.sub.error({ status });
  }

  /** Called when the subscriber goes away. */
  release(): void {
    if (!this.settled) this.cancelled = true;
  }
}

export interface PageArgs {
  group: string;
  page: number;
  size: number;
  key?: string;
}
export interface TableArgs {
  section: DocSection;
  q: DocTableQuery;
}

/** A DocumentSource that records every request; the test answers them one by one. */
export class FakeDocumentSource extends DocumentSource {
  editable = false;
  readonly groups: Pending<void, DocMetadataGroup[]>[] = [];
  readonly pages: Pending<PageArgs, DocMetadataPage>[] = [];
  readonly tables: Pending<TableArgs, DocTablePage>[] = [];

  private record<A, T>(list: Pending<A, T>[], args: A): Observable<T> {
    return new Observable<T>((sub) => {
      const p = new Pending(args, sub);
      list.push(p);
      return () => p.release();
    });
  }

  override metadataGroups(): Observable<DocMetadataGroup[]> {
    return this.record(this.groups, undefined);
  }
  override metadataPage(group: string, page: number, size: number, key?: string): Observable<DocMetadataPage> {
    return this.record(this.pages, { group, page, size, ...(key ? { key } : {}) });
  }
  override tablePage(section: DocSection, q: DocTableQuery): Observable<DocTablePage> {
    return this.record(this.tables, { section, q });
  }

  /** The single untaken request matching; fails the test when there is none or more than one. */
  private one<A, T>(list: Pending<A, T>[], match: (a: A) => boolean, what: string): Pending<A, T> {
    const found = list.filter((p) => !p.taken && match(p.args));
    if (found.length !== 1) throw new Error(`expected exactly one ${what} request, found ${found.length}`);
    found[0].taken = true;
    return found[0];
  }
  private none<A, T>(list: Pending<A, T>[], match: (a: A) => boolean, what: string): void {
    const found = list.filter((p) => !p.taken && match(p.args));
    if (found.length) throw new Error(`expected no ${what} request, found ${found.length}`);
  }

  oneGroups(): Pending<void, DocMetadataGroup[]> {
    return this.one(this.groups, () => true, 'metadata groups');
  }
  onePage(group: string): Pending<PageArgs, DocMetadataPage> {
    return this.one(this.pages, (a) => a.group === group, `metadata page of ${group}`);
  }
  nonePage(group: string): void {
    this.none(this.pages, (a) => a.group === group, `metadata page of ${group}`);
  }
  oneTable(): Pending<TableArgs, DocTablePage> {
    return this.one(this.tables, () => true, 'table');
  }
  noneTable(): void {
    this.none(this.tables, () => true, 'table');
  }
}

/** An editable FakeDocumentSource: records each applyEdits batch, and `changed` is the test's to emit. */
export class EditableFakeDocumentSource extends FakeDocumentSource {
  override editable = true;
  readonly edits: Pending<DocEdit[], void>[] = [];
  readonly changes = new Subject<void>();
  override readonly changed = this.changes.asObservable();

  override applyEdits(ops: DocEdit[]): Observable<void> {
    return new Observable<void>((sub) => {
      const p = new Pending<DocEdit[], void>(ops, sub);
      this.edits.push(p);
      return () => p.release();
    });
  }

  /** The single batch not yet taken. */
  oneEdit(): Pending<DocEdit[], void> {
    const found = this.edits.filter((p) => !p.taken);
    if (found.length !== 1) throw new Error(`expected exactly one edit batch, found ${found.length}`);
    found[0].taken = true;
    return found[0];
  }
  noneEdit(): void {
    const found = this.edits.filter((p) => !p.taken);
    if (found.length) throw new Error(`expected no edit batch, found ${found.length}`);
  }
}
