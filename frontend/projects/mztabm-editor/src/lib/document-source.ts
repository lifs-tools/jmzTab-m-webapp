import { Observable } from 'rxjs';

/*
 * The library's view of a document. It deliberately defines its own minimal DTO interfaces, shaped like the
 * validator's REST DTOs, so that it imports nothing from a host application (or from a generated API client).
 * The host adapts its own client to DocumentSource.
 */

/** A metadata group (the MTD lines sharing a key prefix) and the number of its entries. */
export interface DocMetadataGroup {
  name?: string;
  count?: number;
}

export interface DocMetadataEntry {
  key?: string;
  value?: string;
  lineNumber?: number;
}

/** One page of the entries of a metadata group. */
export interface DocMetadataPage {
  group?: string;
  page?: number;
  size?: number;
  total?: number;
  entries?: DocMetadataEntry[];
}

/** The table sections of an mzTab-M document (small molecule summary, feature and evidence). */
export type DocSection = 'sml' | 'smf' | 'sme';

/** A filter on one column: rows whose cell contains the text. */
export interface DocColumnFilter {
  column: string;
  text: string;
}

/** What a table view asks for. */
export interface DocTableQuery {
  /** 0-based. */
  page: number;
  size: number;
  sort?: string | null;
  descending?: boolean;
  /** The columns to show, the first one always included; absent means the source's default window. */
  columns?: string[] | null;
  filters?: DocColumnFilter[];
}

export interface DocTableRow {
  /** 0-based position among the rows of the whole table (not of the page). */
  index?: number;
  cells?: string[];
  /** 1-based line of the row in the document text; present for editable documents, absent for results. */
  lineNumber?: number | null;
}

/** One page of a table section. */
export interface DocTablePage {
  section?: 'SML' | 'SMF' | 'SME';
  page?: number;
  size?: number;
  totalRows?: number;
  /** Every column of the table, in order. */
  allColumns?: string[];
  /** The columns the rows carry, in order. */
  columns?: string[];
  rows?: DocTableRow[];
}

/*
 * A text edit of a document. This mirrors the server's EditOperation: the property `op` holds the simple
 * record name, e.g. { "op": "SetCell", "line": 3, "cell": 2, "text": "x" }. Line numbers are 1-based and
 * refer to the text as it is when the operation runs; cell 0 of a line is its prefix (MTD, SML, ...).
 */

/** Sets cell `cell` (at least 1; at most the number of cells, which appends one). */
export interface SetCell {
  op: 'SetCell';
  line: number;
  cell: number;
  text: string;
}
/** Sets the value (cell 2) of an MTD line. */
export interface SetMetadataValue {
  op: 'SetMetadataValue';
  line: number;
  value: string;
}
/** Sets the key (cell 1) of an MTD line. */
export interface RenameMetadataKey {
  op: 'RenameMetadataKey';
  line: number;
  key: string;
}
/** Inserts a line (tabs split it into cells) so that it becomes line `before`. */
export interface InsertLine {
  op: 'InsertLine';
  before: number;
  text: string;
}
export interface DeleteLine {
  op: 'DeleteLine';
  line: number;
}
/** Moves a line to stand before line `before` (numbered as before the move). */
export interface MoveRow {
  op: 'MoveRow';
  line: number;
  before: number;
}
/** Inserts a column at cell index `position` in a SMH, SFH or SEH header and its rows. */
export interface InsertColumn {
  op: 'InsertColumn';
  header: string;
  position: number;
  name: string;
  fill: string;
}
export interface DeleteColumn {
  op: 'DeleteColumn';
  header: string;
  name: string;
}
export interface RenameColumn {
  op: 'RenameColumn';
  header: string;
  name: string;
  newName: string;
}
/** Appends the line to the previous one with no separator. */
export interface JoinWithPrevious {
  op: 'JoinWithPrevious';
  line: number;
}
/** Splits the cell after the prefix at its first run of spaces. */
export interface SplitCells {
  op: 'SplitCells';
  line: number;
}
/** Replaces the whole line text (tabs split it into cells); the line ending stays. */
export interface SetRawLine {
  op: 'SetRawLine';
  line: number;
  text: string;
}

export type DocEdit =
  | SetCell
  | SetMetadataValue
  | RenameMetadataKey
  | InsertLine
  | DeleteLine
  | MoveRow
  | InsertColumn
  | DeleteColumn
  | RenameColumn
  | JoinWithPrevious
  | SplitCells
  | SetRawLine;

/**
 * Where the document views get their data from. The host provides one (`{ provide: DocumentSource, ... }`);
 * the views inject it. A read-only source (a validation result) has `editable = false` and no `applyEdits`.
 */
export abstract class DocumentSource {
  /** Whether the document can be edited; a read-only source leaves `applyEdits` undefined. */
  abstract readonly editable: boolean;

  abstract metadataGroups(): Observable<DocMetadataGroup[]>;

  /** The page of `group` (0-based); with `key` the source picks the page holding that entry instead. */
  abstract metadataPage(group: string, page: number, size: number, key?: string): Observable<DocMetadataPage>;

  abstract tablePage(section: DocSection, q: DocTableQuery): Observable<DocTablePage>;

  /**
   * Applies edits as one batch; completes when they are applied. Only on editable sources. An error carries
   * the HTTP-like `status`; on 409 (the document changed elsewhere) the host reloads and emits `changed`.
   */
  applyEdits?(ops: DocEdit[]): Observable<void>;

  /**
   * Emits when the document changed under the views (undo, redo, a quick fix, a reload after a conflict): each
   * view requests again what it shows. Edits a view applies itself are already shown by that view.
   */
  readonly changed?: Observable<void>;
}

/** Cell text and metadata values hold no tab or line break; only raw-line edits may contain tabs. */
export function invalidCellText(text: string): string | null {
  return /[\t\r\n]/.test(text) ? 'A cell cannot contain a tab or a line break.' : null;
}
