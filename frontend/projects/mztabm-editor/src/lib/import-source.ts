/*
 * Copyright 2026 LIFS Tools Consortium.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { Observable } from 'rxjs';

/*
 * What the Add-from panel needs of a host: the kinds of file the document takes, a preview of an import, applying
 * it, and the repository rules the document does not meet yet. As in document-source.ts, the library defines its
 * own view types, shaped like the validator's REST DTOs, and the host adapts its client to them.
 */

/** The kind of import of a vocabulary instance (JSON): the v3 reader's output, or a tool that writes the vocabulary. */
export const VOCABULARY_INSTANCE = 'vocabulary-instance';

/** What to preview: the host adds the revision of the document it knows. */
export interface ImportRequestView {
  kind: string;
  /** The file's content, parsed (JSON). */
  instance: unknown;
  /** The 1-based position of the dataset to import, or null for an instance with one dataset. */
  datasetIndex: number | null;
  /** The study id the document's mzTab-ID is made from (e.g. LCS-00123); not blank. */
  studyId: string;
  /** Lines the document already has with another value are kept (reported as CONFLICTS) instead of replaced. */
  keepExisting: boolean;
}

/** What one entry of an import does to the document. */
export type ImportSuggestionState = 'NEW' | 'REPLACES_EXISTING' | 'UNCHANGED' | 'CONFLICTS';

export interface ImportSuggestionView {
  key?: string;
  value?: string;
  state?: ImportSuggestionState;
}

/** A preview: what the import would change, and the batch that changes it, which only the host reads. */
export interface ImportPreviewView {
  kind?: string;
  /** The revision of the document the preview is of. */
  revision?: number;
  mapping?: string;
  mappingVersion?: string;
  suggestions?: ImportSuggestionView[];
  notes?: string[];
  /** The edits, as the host's edit endpoint takes them; passed back to {@link ImportSource.applyImport} as they are. */
  batch?: unknown;
}

/** One rule of the document's repository rules: a field of the model that takes terms. */
export interface FieldRuleView {
  id?: string;
  /** The rule's name; may be absent, then the path names it. */
  name?: string;
  /** Where the field is in the model, e.g. `/metadata/@quantificationMethod`. */
  path?: string;
  /** `MUST`, `SHOULD` or `MAY`. */
  requirement?: string;
  present?: boolean | null;
}

/** The MUST and SHOULD rules the document does not meet yet. */
export interface StillMissingView {
  /** The revision of the text the rules were checked against. */
  revision?: number;
  /**
   * Whether the text could be read into a model, so that the rules could be checked. If not, `missing` holds every
   * MUST and SHOULD rule, whose presence is unknown.
   */
  checked?: boolean;
  missing?: FieldRuleView[];
}

/**
 * Where the Add-from panel gets its data from; the host provides one (`{ provide: ImportSource, ... }`). A failed
 * request errors with the HTTP-like `status` and, where the host has one, the reason in words as `detail`, as
 * {@link DocumentSource.applyEdits} does.
 */
export abstract class ImportSource {
  /** The kinds of file the document takes, e.g. [{@link VOCABULARY_INSTANCE}]; empty if none for its version. */
  abstract importKinds(): Observable<string[]>;

  /** What the import would change; changes nothing. */
  abstract previewImport(req: ImportRequestView): Observable<ImportPreviewView>;

  /**
   * Applies a preview's batch whole, as one change. `'stale'`: the document changed since the preview, nothing was
   * applied, and the host has read the document again; a new preview is of the document as it is now.
   */
  abstract applyImport(p: ImportPreviewView): Observable<'applied' | 'stale'>;

  /** The repository rules the document does not meet yet. */
  abstract stillMissing(): Observable<StillMissingView>;
}

/** The reason a source gave for a failed request, or the fallback. */
export function importErrorOf(err: unknown, fallback: string): string {
  const e = typeof err === 'object' && err !== null ? (err as { detail?: unknown }) : {};
  return typeof e.detail === 'string' && e.detail.trim() ? e.detail : fallback;
}
