# mzTab-M Editor, Milestone 2 — Repair First

**Date:** 2026-10-31
**Status:** Approved by the project lead on 2026-10-31.
**Builds on:**
- `lipidcompass/docs/design/mztabm-editor/specs/standalone-editor-design.md` (the "standalone design");
- `2026-10-23-validator-spa-design.md` (the "SPA design");
- `2026-10-23-linked-validation-messages-design.md` (the "linked-messages design");
- `2026-10-30-mztabm-version-engines-design.md` (the "engines design").

Everything in those documents that this one does not override still applies. §12 lists every override.

Decisions are tagged **[decided]** where the project lead decided them and **[my call]** where the author decided and the project lead can still correct it.

---

## 1. Intent

Milestone 2 turns jmzTab-m-webapp from a validator into a validator **and editor** for mzTab-M 2.0 and 2.1.

**Who comes and why [decided, project lead 2026-10-31].** Typically, users bring a file with:
- **syntax errors**;
- **missing mandatory or required elements**.

They need support and guidance to do one of two things:
- **fix the file in place**; or
- receive **a very specific resolution instruction** that tells them how to fix the problem in the tool that produced the file.

Authoring a new file from scratch matters too, but it is the less common case.

### Why this changes the standalone design

The standalone design's working copy holds the file as **jmzTab-m model objects** (one JSON row per metadata element and per table row). That only works for a file that jmzTab-m can turn into a model, and those are exactly the files users do *not* bring:

- **jmzTab-m builds no model once a single Error-level problem occurs while reading the file.**
  - `MzTabFileParser.getMZTabFile()` builds the model only if the error list has no errors (`MzTabFileParser.java:632`, both lines).
  - A partial model is never exposed after a line-level error.
- **Many syntax errors stop the parse altogether.** Examples:
  - a line without a valid prefix (Error-1000);
  - an MTD line that is not three tab-separated parts (Error-1008);
  - an unknown metadata key (Error-1009);
  - a missing mandatory column (Error-1004).

  Nothing after the offending line is checked.
- **A missing mandatory column aborts at the first one** (`SMHLineParser.java:100-106`), so a file missing three columns reports one at a time.
- **There is no lenient or recovering parse mode** in either line.

A model-based editor can therefore not open the typical file. **The editor must work on the file's text and use the engines to judge it** (§4, R1).

### Facts from real files (2026-10-31)

Saved validation reports of LipidCompass submissions were tallied: 169 historical structural reports and 180 structural + semantic baselines, all from jmzTab-m 1.0.6. Every distinct code found:

| Code | Files | What it was in practice | Kind |
|---|---|---|---|
| Error-1012 | 16 | Empty parameters `[, , , ]` (detector, factors), user params with no name `[, , , 25]` (about 270 occurrences in 3 files) | syntax |
| Error-1014 | 4 | DOIs written as `doi:https://…`, `doi:doi:…` | syntax |
| Error-1017 | 3 | E-mail reduced to its domain | syntax |
| Error-1000 | 2 | A newline embedded in a cell broke the line (fatal) | syntax |
| Error-1008 | 1 | A space instead of a tab in an MTD line (fatal) | syntax |
| Error-1013 | 1 | Empty parameter list `[, , , ]` | syntax |
| Error-2000 | 1 | Empty mandatory cell `instrument[1]` (fatal) | missing |
| Error-2008 | 1 | An assay refers to an undefined `sample[1]` (fatal) | missing / reference |
| Error-3015 | 1 | A CV label that does not match its accession; a UMLS id as NCIT | semantic |
| Warn-3005 | 88 + 49 | CV term outside the allowed roots: units, tissue "whole", CHMO terms as instrument names | semantic |
| Warn-3014 | 166 | A CV declared but never used | semantic |
| Info-3010, 2037/2038 | 170+ | Optional fields empty; summary-only file | informational |

- **Most files came from one producer.** 170 of 186 files declare `software[1]` = "LipidCompass curation script v0.0.7", the spreadsheet-template converter. The rest come from LipidDataAnalyzer, MassHunter and a handful of others.
- So these counts show mostly what template users type and what that converter writes, not what native exporters do.
- Even so, the pattern supports the project lead's statement: **hard errors are syntax and missing elements; semantic problems are many but mostly warnings.**

---

## 2. Goals, non-goals, anti-goals

### Goals

1. **Explain every problem specifically.** For every message, the user sees:
   - what is wrong, in plain words;
   - where it is, quoting the actual offending line and highlighting the cell;
   - how to fix it here;
   - how to fix it in the producing tool.

   This applies on the validator's result page too, for users who will fix the file in their tool and never open the editor.
2. **Open any file that can be read as text, fix it in place and download it**, however broken it is. This includes files that jmzTab-m cannot turn into a model.
3. **Re-check after every fix with the reference implementation** of the file's version, and show what got fixed and what is still open.
4. **One-click fixes** for the common mechanical problems in §5.6, always previewed, undoable and re-validated.
5. **Author a new file**, and edit a valid one with guided forms, in 2.0 or 2.1 (phase 2b, §6). This carries the standalone design's wizard, CV pickers and reference handling forward.
6. **Ship the editor as packages**, as in standalone design §5.6, and have LipidCompass adopt it (phase 2c).

### Non-goals

- Everything listed as a non-goal in the standalone and engines designs, in particular:
  - collaborative editing;
  - accounts;
  - mzTab 1.0;
  - converting 2.0 to 2.1.
- **Guessing intent.** The editor never "fixes" a scientific value (a term, a unit, a number) without the user choosing it.

### Anti-goals (failure even if it "works")

- **A verdict that is not the reference implementation's.** Guidance and quick fixes help the user act on a message; they never decide whether a file is valid. Only an engine re-validating the text does.
- **Silent rewrites.** Opening a file and downloading it without edits must give back the same bytes (§5.2). An edit changes only what it says it changes.
- **Vague advice.** "Check the specification" or a bare error code is a failure. Every message with a catalog entry gives a concrete next step, and every message without one shows at least jmzTab-m's own fix text and cause.
- **A dead end.** A file the parser gives up on must still open in the editor, with the line it gave up on shown and editable.
- Everything listed as an anti-goal in the standalone design, in particular:
  - a sixth orphan;
  - two copies of the editor;
  - a freezing browser tab.

---

## 3. Current state (facts this design relies on)

**jmzTab-m (both lines behave alike here):**

- **Error catalogue.** It has three classes:
  - `FormatErrorType` (1000–1038);
  - `LogicalErrorType` (2000–2068);
  - `CrossCheckErrorType` (3000–3016).

  Each code has four texts in `api/src/main/resources/conf1_1/mztab_*_error.properties`: `_code_`, `_original_` (the message), `_cause_` and `_fix_`.
- **What reaches users today.** The fix text is exported as `ValidationMessage.suggestion`. **The cause is not exported.**
- **Messages carry a location.** That is the linked-messages design:
  - section;
  - row index;
  - column;
  - metadata key and group;
  - element path;
  - line number.

  **The raw line text is not carried.** Some messages truncate the line to 19 characters.
- **Mandatory checks run in three places:**
  - mandatory columns: in the header parser, which aborts;
  - mandatory metadata: right after the read loop, on the partial `Metadata`;
  - profile requirements: only once a model exists.
- **The single-line parsers are public.** `MTDLineParser` and `SMHLineParser`/`SMLLineParser` can parse one line on its own (the tests do).
- **`MzTabNonValidatingWriter` writes incomplete models.** It still throws on null list entries and on indexed elements without an id.

**jmzTab-m-webapp (`feat/validator-spa`, dd85a5f):**

- **Each validation session keeps the original upload**, as `MZTABFILE/upload.mztab`. Next to it, it stores:
  - the `ValidationRecord`;
  - the sections as cells, in `SECTIONS/*.jsonl`.

  Sessions are deleted 7 days after they were last modified. Storage is capped at 10 GiB in total and 64 MB per upload.
- **The engines validate text.** `POST /engine/v1/validations` takes TSV or JSON and streams `outcome`, `metadata`, `table`, `row` and `end` events. The section events are re-rendered from the model, so they exist only when a model was built.
- **The result page links messages to their place in the viewer.** It has a split layout, jump-to and highlight, a server-paged table with sort, filter and a column window, and lazily loaded metadata groups.
- **There is no per-code help text** anywhere. The only fix text shown is jmzTab-m's `suggestion`.
- **CV suggestions are not exposed.** `OlsMappingCvSuggestionService` lives in the engines and is not exposed. **Profiles' field rules are not exposed either.**
- **`/convert` and `/convertPlain` are implemented** through the engines.

---

## 4. Decisions

| # | Decision | Status |
|---|---|---|
| R0 | Users mostly need to fix syntax errors and missing mandatory elements, in place or in their tool, with specific guidance. | decided |
| R1 | **The working copy is the file's text**, stored as lines and cells, not as jmzTab-m model objects. This overrides the standalone design's proposed row layout (§5.2 there); SQLite stays (decided 2026-10-22). | my call |
| R2 | **Two ways of editing.** *Repair* edits lines and cells and works on any file. *Guided* offers forms and pickers, and is available once the file's engine can build a model from it. | my call |
| R3 | **The engine re-validates the assembled text after every change.** That is the only source of verdicts. The engine contract for validation stays as it is. | my call |
| R4 | **Guidance per message:** what's wrong, where (with the quoted line), fix here, fix in your tool. It comes from jmzTab-m's texts plus a **guidance catalog** curated in this repo. | my call |
| R5 | **Quick fixes** are previewed, undoable text edits for the patterns in §5.6. They never pick a scientific value. | my call |
| R6 | **Guidance also appears on the validator result page and in the REST result**, not only in the editor. | my call |
| R7 | **Phasing:** 2a repair and guidance; 2b guided authoring and editing in 2.0 and 2.1; 2c packages and LipidCompass adoption (§6). | my call |
| R8 | **The working copy's version is whatever the text declares**, re-detected after every edit, and routed like an upload. This overrides engines design §5.7 ("cannot change"), because a missing or broken `mzTab-version` line is itself a typical repair. | my call |
| R9 | **Every edit is undoable**, and the original upload stays downloadable. | my call |
| R10 | **The editor's UI is an Angular library project from the start** (`frontend/projects/mztabm-editor`), used by the SPA. Phase 2c only publishes it. | my call |
| R11 | **Column names are unique within a table header.** No edit creates a repeated column name; a file loaded with one is marked and resolved: the editor offers to keep one of the columns or to merge them (§5.3). | project lead's requirement, 2026-10-31 |

---

## 5. Design

### 5.1 User journeys

**A. "Tell me how to fix it in my tool" (no editing).**
1. The user validates a file as today.
2. On the result page, each message group opens a **guidance panel** (§5.5).
3. A **resolution report** download lists every problem with its tool-side instruction and the affected lines. It is a Markdown file the user can forward to the tool's developer.

**B. "Fix it here."**
1. On a result, the user presses **Fix this file**. A working copy is created from the session's stored upload, and the editor opens on the same layout as the result page: messages on the left, the document on the right.
2. The user picks a message and gets to its place. The guidance panel shows the problem, then either:
   - a quick fix with a before/after preview; or
   - the field to edit, with a hint.
3. Edits are saved as they are made. Structural re-validation runs shortly after the user pauses. The message list shows "fixed", "new" and "still open" against the previous run.
4. When the remaining messages are acceptable, the user downloads the file as `.mzTab`. JSON is offered only when the file is valid.
5. Alternatively, the user can start from the landing page: **Open a file to fix** uploads, validates and opens the editor in one step.

**C. "Start a new file" (phase 2b).**
1. The user chooses mzTab-M 2.0 or 2.1.
2. The engine of that version produces a minimal skeleton.
3. The guided wizard from the standalone design leads through it, with the same validation loop.

### 5.2 Working copy

**What it is.** A working copy is a stored copy of the file's text:

- It belongs to one browser session, under an unguessable UUID.
- It has the same retention as results: deleted 7 days after its last change, as today's cleanup does by modification time.
- It is subject to the same storage caps.
- It lives under the existing storage root, in a new slot `WORKINGCOPY/` next to the session it came from.

**Store: SQLite [decided 2026-10-22].** The schema is the builder's. It must hold:

- **Lines:** one row per line of the file, with:
  - its position;
  - its section (`MTD`, `SMH`, `SML`, `SFH`, `SMF`, `SEH`, `SME`, `COM`, or `?` for a line without a valid prefix);
  - its cells, as a JSON array split on tabs.

  The raw text is exact: it can be rebuilt from the cells.
- **Edits:** an append-only log of the operations applied, enough to undo and redo them (R9).
- **Copy metadata:**
  - revision counter;
  - detected version;
  - line-ending style;
  - a byte-order mark if present;
  - source (validation id or upload);
  - the last validation's messages.

**Exactness.** The following must come back byte-identical:

- **An unedited copy.** This includes:
  - line endings (LF or CRLF, kept per file);
  - a trailing newline, or none;
  - a byte-order mark;
  - empty trailing cells.
- **Every line an edit does not touch.**

This is tested on real-shaped fixtures (§8).

**Encoding.** mzTab-M is UTF-8:

- **Not UTF-8.** If a file is not valid UTF-8, the editor does not open it silently. It shows the offending line and offers a one-click **re-encode from Windows-1252/Latin-1** (a quick fix, §5.6).
- **JSON uploads** are converted to TSV by the engine when the copy is created. If the JSON cannot be read as an mzTab-M model, the copy cannot be created, and the user gets the engine's messages.

**Concurrency.** Each copy has a revision number:

- Every edit request names the revision it was made against; a stale one is refused (409), and the editor reloads.
- Edits on one copy are serialised.
- Two tabs on one copy are therefore safe, not collaborative.

**Line numbers need no mapping.** Validation runs on the copy's assembled text, so a message's `line_number` *is* a working-copy line. The linked-messages locations (section, row index, column, metadata key) still drive jump-to, exactly as on the result page. This replaces the standalone design's line-mapping idea (§5.2 there).

### 5.3 Repair operations

These operations need only the line prefix and the tab layout, the same knowledge the viewer already uses. They carry no mzTab-M semantics (D5 stays intact).

**Operations:**

| Area | Operations |
|---|---|
| Metadata | Set the value of an MTD line; rename its key; insert or delete an MTD line. |
| Table cells and rows | Set a table cell; insert, delete or move a row. |
| Table columns | Insert, delete or rename a column. The header and every row of that section change together, so cell counts stay aligned. Delete and rename can name the column by its cell index as well as by name; merge combines columns of the same name (see *Duplicate column names*). |
| Line structure | Join a line with the previous one (an embedded newline); split a line into cells (spaces where tabs belong). |
| Raw edit | Edit the raw text of any single line. This is the fallback for anything else, including lines without a valid prefix. |

**Applying edits:**
- The client sends edits **in batches**. Each batch is one undo step and one revision.
- Every operation validates its arguments: line positions exist, columns exist, and cell text contains no tab or newline unless the operation is meant to split.
- Operations that do not fit are refused, not clamped.

**Duplicate column names** (R11, the project lead's requirement of 2026-10-31: "column names must be unique, there should be no case where there exist more than one with the same name, unless the user loads such a file. In the latter case, the editor should highlight the conflict and offer a resolution: pick a specific one, delete the others or offer a merge into one column."):
- *No edit creates a repeated column name; a file loaded with one is marked and resolved.* Inserting a column with a name the header already has, or renaming a column to such a name, is refused (400). So is any line edit of an SMH, SFH or SEH line that would repeat a name more often than the line already did: setting a cell, replacing the line, inserting a header line, and joining or splitting into one (ruling R-T10f). The refusal names the column. A repeat the loaded file already has stays editable, so it can be resolved.
- *Columns are addressed by position, never by name.* A working copy's table page reports the cell index of every column it returns (`columnPositions`), and the editor uses it for every cell edit. The editor also asks for a working copy's columns by position (`positions`, comma-joined cell indexes, at most 200, each once, not together with `columns`), and sorts and filters by position (`sortPosition`, `positionFilter=POSITION:text`), so a later column of a repeated name is shown, sorted and filtered as itself, and a column name with a comma needs no escaping. After the document changes elsewhere (undo, redo, a quick fix), positions may have moved: the editor fetches the column list first, keeps the sort and the filters whose position still holds the same name, and then asks by position again. A validation's table has no positions; there, a name requested twice returns its first and its second column.
- *Detection.* `GET /working-copies/{id}/columns/{header}/duplicates` (SMH, SFH or SEH) lists each name the first header line holds more than once, with its cell indexes, the number of rows in which at least two of these cells hold different values (a value is neither empty nor `null`), and up to 20 such rows. Empty header cells name no column. The engines report the same condition as Error-2062 (DuplicateColumnHeader); its guidance points to the resolution in the editor.
- *Marking.* The editor's table view marks every header cell whose name occurs more than once and labels it "Duplicate column name".
- *Resolutions* ("Resolve duplicate column"):
  1. **Keep one, delete the others.** The user picks the column to keep; the others are deleted by position, the highest first, so the remaining positions stay valid (`DeleteColumn` with `position`; the header cell there must hold the name, else 400).
  2. **Merge into one.** `MergeColumns(header, name, keep, others)`: in every row the kept cell keeps its value unless it is empty or `null`; then it takes the first value among `others`, in their order, that is neither. The `others` columns are then removed from the header and every row. All positions must hold the name, `keep` is not among `others`, and `others` has no repeats. The user chooses which column wins where the rows disagree (the first by default); the dialog shows how many rows disagree and examples of them.
  3. **Cancel.**
- Both resolutions are one batch, so one undo restores the file byte for byte (line images, as for every edit), within the usual batch budget.

### 5.4 The validation loop

**Structural re-validation:**
- It runs automatically after a batch, debounced while the user is still typing. A newer run supersedes an older one.
- It uses the copy's version engine and the active profile's level and error limit.
- **Performance [requirement].** It must finish on the largest real file (§3.3 of the standalone design: 32 MB, 846 × 1,990 SML) fast enough not to interrupt work. The builder measures this first (§9).

**Semantic validation** (CV rules, OLS lookups) is slower and needs network access, so it runs:
- on demand;
- before download, if the user asks for it.

**Comparing runs.** Messages are matched across runs by code and location:
- for metadata, by key;
- for tables, by section, row and column;
- by line only as a fallback.

The list marks each message as fixed, new or still open.

**Problems that hide others.** For codes that stop the parse (the aborting set, e.g. 1000, 1004, 1008, 1009, 2002, 2004, `NULL`), the guidance says: *"jmzTab-m stopped reading at this line, so problems further down are not reported yet."* The editor shows how far the file was read.

**Version changes.** After each batch, the version is detected from the copy's text, as for an upload (R8). If it changes, for example because a missing `mzTab-version` line was added, the next run goes to the new version's engine, and the editor says so.

### 5.5 Guidance

**Sources, in order:**

1. **jmzTab-m's own texts.** The `message`, the fix text (`suggestion`) and the **cause** (`_cause_`). The engines fill a new `cause` field in `engine-api`'s `ValidationMessage`, from the error type by its code. That needs no jmzTab-m change [my call]; exporting it upstream is jmzTab-m#245.
2. **The guidance catalog** (new, in this repo). It is curated by the LIFS team like the profile catalog: files plus an index under `server/src/main/resources/guidance/`, and a merged PR is the approval. An entry is keyed by code, and optionally by version and by a pattern in the message or the offending value, so that the two Error-1012 cases in §1 get different advice. An entry holds:
   - **title:** the problem in plain words (e.g. "Empty parameter");
   - **explanation:** why it is a problem, in two or three sentences;
   - **fix here:** a quick fix id (§5.6) or the field to edit, with a short hint;
   - **fix in your tool:** a generic instruction;
   - **tool variants (optional):** instructions for a producing tool, matched against the file's `MTD software[n]` names. The first entries are the LipidCompass curation script and LipidDataAnalyzer, the producers that dominate §1.
3. **A fallback** for codes without an entry. It shows the message, the fix text and the cause, so nothing is ever just a code.

**Specific, not generic.** Each guidance panel shows the instance data:
- the location in words, e.g. "line 57, `instrument[1]-detector`";
- the offending line quoted from the copy or the stored upload, with the cell highlighted;
- the filled-in message parameters.

Repeated problems are grouped with a count and the list of lines, e.g. "270 user parameters without a name, in lines 61–330".

**Severity is the rule's.** For CV-rule messages, the panel names the rule's requirement level (MUST, SHOULD, MAY) from the profile, next to the message level. 1.0.6 reported violations of a required rule as **Warn**-3005; 1.0.8 and 2.1 take the rule's level (§10).

**Where guidance appears:**
- in the result page's message groups (journey A);
- in the editor;
- as the resolution report, a Markdown download per validation;
- in the REST result of `GET /rest/v2/validations/{id}`, as additional optional fields.

The legacy `/validate` and `/validatePlain` responses stay unchanged.

**Catalog checks (CI):**
- every code in the catalog exists in at least one engine's error catalogue (the engines export their code list at build time);
- every quick-fix id exists;
- every entry has both a "fix here" and a "fix in your tool" text.

### 5.6 Quick fixes (first set)

Each quick fix:
- is a deterministic text edit, computed by the webapp from the message, its location and the copy's lines;
- is shown as a before/after preview;
- is applied as one undoable batch;
- is followed by re-validation, so the engine confirms the problem is gone.

"Apply to all *n* like this" is offered where the same pattern repeats. A quick fix never chooses a CV term, unit or value for the user; where one is needed, it opens the field instead.

| Pattern (from §1) | Quick fix |
|---|---|
| Error-1012/1013, an empty parameter or list `[, , , ]` on an optional field | Remove the line. On a mandatory field: open the field (in phase 2b with the CV picker). |
| Error-1012, a user parameter without a name `[, , , 25]` | Open the field with the name slot focused. Offer the same name for all *n* similar lines. |
| Error-1014, a DOI written as a URL or with a doubled prefix | Normalise to `doi:10.…`. |
| Error-1000, a line without a prefix that continues the previous line | Join it with the previous line, replacing the newline with a space. |
| Error-1008, an MTD line with spaces where the tab belongs | Split into key and value at the first run of spaces. |
| Error-1004, a missing mandatory column | Insert the column in the header and in every row, with empty or `null` cells as the column allows. Re-validation then reveals the next missing column. |
| Missing mandatory metadata (e.g. `mzTab-ID`, `mzTab-version`, or an element referenced but not defined, Error-2008) | Insert the line or a minimal element with its value field focused. |
| Warn-3014, a CV declared but never used | Remove its `cv[n]` lines and renumber the remaining `cv[n]`. |
| The file is not UTF-8 | Re-encode from Windows-1252/Latin-1, after showing the affected lines. |

Error-1017 (an e-mail address) and Error-2000 (an empty mandatory cell) have no automatic fix: the right value is the user's. They jump to the field.

**This is not format logic in the sense of D5 [my call].** D5 rules out a second parser, writer or validator. Quick fixes are edits a user could make by hand, and the engine judges the result. The webapp implements them in Java, next to the working copy.

### 5.7 Guided editing (phase 2b)

**When it is available.** Guided editing is offered once the copy's engine reports a built model (`outcome.parsed` = true). It brings the standalone design's §5.3 editor across, adapted:

- **Wizard and widgets.** It keeps:
  - the step structure, locking and reference highlighting;
  - the reference graph;
  - the bulk import and CV widgets.

  The widgets use Angular Material/CDK and `--mztabm-*` tokens.
- **Compound operations** need the model, for example:
  - adding assay *n* and with it `abundance_assay[n]` in every row;
  - removing a referenced element.

  These run **in the engine of the copy's version**: it parses the text, applies the operation to the model, and writes the affected sections back with `MzTabNonValidatingWriter`. The webapp replaces those lines.
- **The first guided change reformats the file.** The writer rewrites a section in jmzTab-m's own layout, and the editor tells the user this once, before the first guided change. Repair edits never reformat.
- **Version-specific forms come from the version's own reference.** They are generated at build time from each line's OpenAPI model (engines design §5.7, kept).
- **CV pickers** use two new engine operations:
  - the active profile's **field rules**: path, allowed ontologies and roots, requirement level, combination logic, custom terms allowed. The profile is parsed by jmzTab-m's JAXB model.
  - **term suggestions**: `OlsMappingCvSuggestionService`, made profile-aware.

  This replaces `generate-cv-mapping.ts`, as the standalone design §5.4 intended.
- **New files:** the engine of the chosen version writes a minimal skeleton.

### 5.8 Engine contract changes

| Phase | Operation | Notes |
|---|---|---|
| 2a | `ValidationMessage.cause` | A new optional field, filled from jmzTab-m's `_cause_`. |
| 2a | Error-code export | A build-time resource listing the engine's codes, for the catalog check. |
| 2b | Field rules for a mapping | Mapping XML in, rules out. |
| 2b | CV suggestions | Mapping, field path and query in; terms out. |
| 2b | Skeleton | Out: a minimal valid TSV for the engine's version. |
| 2b | Model edit | TSV and an operation in; rewritten sections or validation messages out. |

All of these are per version, and they keep the engines design's routing, error codes, busy handling and size limits.

### 5.9 Webapp REST API (capabilities; the builder designs the shapes)

Under `/rest/v2/working-copies`:

- **Create.** A copy can be created:
  - from a validation id;
  - from an upload (multipart, which validates first);
  - for a new file (phase 2b, with version).
- **Read:**
  - copy metadata: revision, version, counts, last validation summary;
  - metadata lines, paged;
  - table sections, paged, sorted and filtered, with a column window, as the result API does today;
  - raw lines by range.
- **Edit and validate:**
  - apply an edit batch, naming the revision;
  - undo and redo;
  - validate (structural, or semantic with a profile);
  - list messages with their guidance;
  - preview and apply a quick fix, for one occurrence or for all similar ones.
- **Download and delete:**
  - download as `.mzTab` (always) or JSON (only when valid);
  - download the original upload;
  - download the resolution report;
  - delete the copy.

**Constraints:**
- **No anonymous parameters in SQL.** Every request argument that reaches a SQLite statement does so **only as a named parameter in a static statement**: no positional `?` parameters and no concatenated SQL [decided, standing project rule].
- **Every parameter is validated and constrained.** Out-of-range values are rejected, not clamped [decided, standing project rule]. That covers:
  - UUIDs;
  - positions, which must be within the copy;
  - page sizes, capped as in the result API;
  - column names, which must be in the header;
  - cell text, which must contain no tab or newline outside split operations;
  - batch size.
- **Caps of a copy [final review, 2026-10-31].** A copy has at most 2,000,000 lines and a line at most 20,000 cells. A file over either cap is refused while it is read (422, error code `too_large`, a message naming the cap), before the whole file is held anywhere; the import runs in chunks. No edit goes beyond the caps (400). Table reads do not pad short rows: a missing cell reads as empty where it is read. The duplicate-column scan costs at most the cells of each row.
- **No listing endpoint.** Copies are found only by their id.
- **Legacy contracts are unchanged.** `/validate`, `/validatePlain`, `/convert` and `/convertPlain` stay as they are.
- **The TypeScript client is regenerated** from the springdoc spec, and the committed spec file is updated as today.

### 5.10 SPA

- **Result page (2a, all users):**
  - each message group gets the guidance panel;
  - a **Download resolution report** action;
  - a **Fix this file** button, offered when the stored upload is readable as text.
- **Editor route** `edit/:workingCopyId` (2a). It uses the result page's split layout and linking, with these additions:
  - editable metadata values and table cells;
  - a raw-line editor for lines without a valid prefix or with the wrong number of parts, showing the line with its tabs made visible;
  - a quick-fix button on message cards, with the preview;
  - a fixed / new / still-open status per message, and how far the parser got;
  - undo and redo;
  - a save indicator and the retention notice ("kept for 7 days after your last change");
  - download actions.
- **Landing page:** **Open a file to fix**, next to the validator form; and in phase 2b, **Start a new file** (2.0 or 2.1).
- **Viewer replacement.** The read-only viewer of the result page becomes the editor's components in read-only mode (SPA design M2). The behaviour is the same, so there is one implementation.
- **Theming.** The editor components live in `projects/mztabm-editor` (R10). Their `--mztabm-*` tokens default from `--lifs-*` (SPA design).

### 5.11 Packages and LipidCompass (phase 2c)

These are as in the standalone design, §5.6 and §5.8:

- the Angular library and the custom element on npmjs.org under `@lifs-tools`;
- the webjar on m.lifs-tools.org;
- LipidCompass replaces its `modules/mztabm-editor` with the package and deletes its copy.

The element's inputs gain the working copy's repair mode, so a LipidCompass user can fix a submission file in place.

---

## 6. Phasing

| Phase | Delivers | Done when |
|---|---|---|
| **2a Repair and guidance** | Working copies (SQLite, text). Repair operations, undo, the validation loop. `cause` in messages. The guidance catalog with entries for every code in §1. Quick fixes of §5.6. The guidance panel and resolution report on the result page. The editor route. Downloads. Docs. | A file with each §1 problem can be opened, fixed and downloaded valid in the SPA, on both engines. An unedited copy downloads byte-identical. The largest real file stays responsive. Both deployments have the persistent storage volume (standalone design §5.2). |
| **2b Guided editing and authoring** | Engine operations for field rules, CV suggestions, skeleton and model edits. Wizard, CV pickers and reference handling ported from the LipidCompass module. New files in 2.0 and 2.1. | A new 2.0 and a new 2.1 file can be authored to valid. A valid file can be extended by an assay with its columns. |
| **2c Packages and adoption** | Library, element and webjar published. LipidCompass adoption and deletion of its copy. | LipidCompass uses the package; `modules/mztabm-editor` is gone. |

Phase 2a ships on its own and is used day to day by the validator's users, so it avoids the "sixth orphan" anti-goal.

---

## 7. Documentation

These docs are part of each phase.

- **Phase 2a:**
  - the SPA user guide: understanding a validation result; fixing a file in place; quick fixes; undo; downloading; retention;
  - a public **"Common problems and how to fix them in your tool"** page, generated from the guidance catalog, for developers of mzTab-M exporters;
  - the self-hosting docs: the working-copy storage slot, caps, and the volume requirement.
- **Phase 2b:** authoring a new file; the guided forms; CV pickers; and the note that guided changes reformat a section.
- **Phase 2c:** the "Embedding the editor" guide (standalone design §6), and the LipidCompass embedded help and screenshots.

The project lead reviews the documentation of each phase.

---

## 8. Testing

**Fixtures.** Synthetic files, one per pattern in §1, written for the tests. Real submission files are not copied into the repo.

**Tests:**
- **Exactness:**
  - an unedited copy round-trips byte-identically, for LF and CRLF, with and without a byte-order mark, with and without a trailing newline;
  - an edit leaves every other line unchanged.
- **Quick fixes:** for each fix, the engine's re-validation no longer reports the targeted message, on both engines.
- **Repair operations:**
  - column insert and delete keep cell counts aligned;
  - argument validation refuses out-of-range positions and stale revisions.
- **SQL:** every SQLite statement is static with named parameters, enforced by a test, e.g. ArchUnit or a source scan. The webapp has no SQL today, so this test is new.
- **Guidance catalog:** the CI checks of §5.5.
- **Performance:** a build-time measurement on a generated file of the largest real dimensions (846 × 1,990 SML, 1,521 assays) covering:
  - creating a copy;
  - paging;
  - a single-cell edit;
  - a column insert;
  - assembling the text;
  - a structural validation.
- **End to end:** journeys A and B in the SPA.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Re-validating a 32 MB file after every batch is too slow | Measure first. Debounce and supersede runs. If needed, validate structure on demand only for files above a size threshold, and say so in the UI. |
| An aborting error hides the rest, so users fix one problem at a time | Say so in the guidance. The re-validation is quick for typical files (median 0.28 MB). Upstream: report all missing columns (jmzTab-m#244). |
| Catalog advice drifts from jmzTab-m's codes | CI check against each engine's exported code list. |
| Quick fixes make things worse | Preview, undo, and engine re-validation after every fix. Each fix's test asserts the engine verdict. |
| Producer-specific advice goes stale | Tool variants carry the tool version they were written for. |
| Anonymous storage of unpublished data in working copies | Same as results: unguessable ids, no listing, 7-day retention stated, and delete on demand. |

**Measurement for the first row (2026-10-31).** `LargeFilePerformanceTest` (`@Tag("performance")`, run with `-Dgroups=performance`) generates a 31 MB file (846 SML rows of 1,990 columns, 1,521 assays, 5,540 metadata lines) and times the working-copy operations on an Apple M4 (arm64, 10 CPUs, 32 GB), Java 25.0.3, with the real mzTab-M 2.0 engine on the same machine. Two runs, in milliseconds:

| Operation | Budget | Run 1 | Run 2 |
|---|---|---|---|
| create a copy from the upload | 10,000 | 841 | 639 |
| lines page (200 lines) | 300 | 13 | 16 |
| table page (SML, 50 rows, 1,990 columns; sorted) | 300 | 54; 44 | 58; 54 |
| one SetCell batch (1 cell; the editor sends one per edit) | 100 | 37 | 38 |
| one SetCell batch of 100 cells (reported) | none | 152 | 169 |
| InsertColumn across all rows | 2,000 | 941 | 788 |
| assembling the text for validation | 3,000 | 198 | 107 |
| one structural validation on the 2.0 engine (reported) | none | 1,692 | 1,479 |

All asserted budgets hold, by a factor of two or more. The structural validation reads the whole file (parsed, messages capped at 100) in about 1.5 s, so re-validating after the idle period of 1.5 s is acceptable at this size and no size threshold is needed. The 100-cell batch is above the 100 ms budget, which was set for a single edit; a bulk edit of that size is not an interactive case. The generated file is deliberately not a valid study (semantic checks are not run).


---

## 10. Findings outside this repo

These were found while preparing this design and are not trivially fixable here. Filed as GitHub issues on 2026-10-31 at the project lead's request.

1. **jmzTab-m: garbled templates.** [jmzTab-m#243](https://github.com/lifs-tools/jmzTab-m/issues/243). The `StringList`, `DoubleList` and `IntegerList` message templates reuse `{1}` where the split character belongs, and `StringList` starts with `==`. So those messages come out garbled. On both lines.
2. **jmzTab-m: missing mandatory columns are reported one at a time.** [jmzTab-m#244](https://github.com/lifs-tools/jmzTab-m/issues/244). `SMHLineParser.refine` throws at the first missing mandatory column. Reporting all of them would save users several round trips.
3. **jmzTab-m: the cause text is not exported.** [jmzTab-m#245](https://github.com/lifs-tools/jmzTab-m/issues/245). `_cause_` does not reach `ValidationMessage`. The engines work around this (§5.5), but other API users would benefit from it upstream.
4. **jmzTab-m: required CV rules reported as warnings. Already fixed.** 1.0.6 reported a violation of a REQUIRED CV rule as **Warn**-3005. Both lines now take the rule's requirement level (commit 4129df19, jmzTab-m#186), so no issue was filed.
5. **lipidcompass-submissions: the template converter writes the most common errors.** The LipidCompass curation script v0.0.7, which produced 170 of 186 tallied files:
   - writes empty parameters for blank template cells;
   - declares a fixed list of CVs whether or not they are used;
   - has produced mangled DOIs and e-mail addresses.

   Fixing it there would remove most of §1 at the source. The current converter (v0.1.0) already fixed the empty detector, cell-type and disease parameters and embedded tabs and newlines. The rest is [lipidcompass-submissions#213](https://github.com/lifs-tools/lipidcompass-submissions/issues/213).

---

## 11. Left to the builder

- The SQLite schema, the edit-log format, and how undo is stored.
- The REST shapes and the guidance fields' JSON.
- The debounce timing and how superseded validation runs are cancelled.
- The exact aborting-code list, derived from jmzTab-m's throw sites.
- The resolution report's layout.
- Grid editing details (cell editor, keyboard navigation), within the SPA's existing table and accessibility patterns.

---

## 12. Overrides of earlier designs

| Earlier design | What changes here |
|---|---|
| Standalone §5.2, working-copy layout ([proposed] there): one jmzTab-m JSON row per element/row | The working copy is text lines and cells (R1). SQLite stays. |
| Standalone §5.2, validation locations by line mapping | Not needed: the text is validated as is (§5.2). |
| Standalone §5.2, the operation list (set field, add element, bulk import) | Split into repair operations (2a, text) and compound model operations in the engines (2b). |
| Standalone §5.3, client-side store mirroring the model | 2a: the client mirrors lines and cells. 2b: the guided forms as there. |
| Standalone §5.7 and SPA design M1/M2, milestone 2 as one block | Phases 2a, 2b and 2c (R7). |
| Engines §5.7, "a working copy records its version; it cannot change" | The version is detected from the text after each edit (R8). |
| Engines §5.7, "all parsing … of a working copy go[es] through the engine" | Still true for validation, conversion and model operations. Repair edits are text edits in the webapp (§5.3). |
| SPA design §5.2 "Read-only sections" and §5.3, the result page's read-only viewer | Becomes the editor's components in read-only mode (§5.10). |
