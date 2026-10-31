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
  /** The column's cell index, which tells apart columns of the same name; only for a source with positions. */
  position?: number;
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
  /**
   * The columns to show by cell index, instead of `columns`; only for a source whose pages report
   * `columnPositions`. Unlike names, positions tell apart columns of the same name.
   */
  positions?: number[] | null;
  /** The column to sort by, by cell index, instead of `sort`; only for a source with positions. */
  sortPosition?: number | null;
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
  /**
   * For an editable document: the cell index in the line (the prefix is cell 0) of each of `columns`. Edits
   * address cells by it, so that columns of the same name are told apart.
   */
  columnPositions?: number[] | null;
}

/** The header line of a table section. */
export type DocTableHeader = 'SMH' | 'SFH' | 'SEH';

export const HEADER_OF: Record<DocSection, DocTableHeader> = { sml: 'SMH', smf: 'SFH', sme: 'SEH' };

/** A row in which columns of the same name hold different values. */
export interface DocDuplicateSample {
  lineNumber?: number;
  /** The row's cell at each of the duplicate's positions, in their order. */
  values?: string[];
}

/** A column name that a header holds more than once. */
export interface DocDuplicateColumn {
  name?: string;
  /** The cell indexes of the columns of that name, in line order. */
  positions?: number[];
  /** Rows in which at least two of these cells hold a value (not empty, not null) and the values differ. */
  conflictingRows?: number;
  /** Some of those rows. */
  samples?: DocDuplicateSample[];
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
/** Deletes a column; with `position` (a cell index) the header cell there must hold `name`, else the first match. */
export interface DeleteColumn {
  op: 'DeleteColumn';
  header: string;
  name: string;
  position?: number | null;
}
/** Renames a column (no other column may have `newName`); `position` as for DeleteColumn. */
export interface RenameColumn {
  op: 'RenameColumn';
  header: string;
  name: string;
  newName: string;
  position?: number | null;
}
/**
 * Merges columns of the same name into the one at cell index `keep`: per row the kept cell keeps its value
 * unless it is empty or null, else it takes the first value among `others` (in order) that is neither; then the
 * `others` columns are removed.
 */
export interface MergeColumns {
  op: 'MergeColumns';
  header: string;
  name: string;
  keep: number;
  others: number[];
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
  | MergeColumns
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
   * the HTTP-like `status`; on 409 (the document changed elsewhere) the host reloads and emits `changed`. A
   * refused edit (400) may carry `detail`, the reason in words for the user.
   */
  applyEdits?(ops: DocEdit[]): Observable<void>;

  /** The column names the header holds more than once; only on editable sources. */
  duplicateColumns?(header: DocTableHeader): Observable<DocDuplicateColumn[]>;

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

/** What a view says when an edit was not saved: the source's reason for a refused edit, else a general text. */
export function saveErrorOf(err: unknown, fallback: string): string {
  const e = typeof err === 'object' && err !== null ? (err as { status?: unknown; detail?: unknown }) : {};
  return e.status === 400 && typeof e.detail === 'string' && e.detail.trim() ? e.detail : fallback;
}
