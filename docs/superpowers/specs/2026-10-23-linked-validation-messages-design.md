# Linked, Grouped and Fix-First Validation Messages — Design

**Date:** 2026-10-23
**Status:** Approved 2026-10-23. Amended while planning: §5.1 base of the `1.0.x` branch (see §5.1 "Correction").
**Builds on:** `docs/superpowers/specs/2026-10-23-validator-spa-design.md` (validator SPA, milestone 1, PR #77)
**Repositories:** `lifs-tools/jmzTab-m` (two lines: 2.1.0-SNAPSHOT on `master`, and the 1.0.x line) and `lifs-tools/jmzTab-m-webapp`.

Decisions are tagged **[decided]** where the project lead decided them and **[my call]** where the author decided and the project lead can still correct it.

---

## 1. Intent

The validator's messages are hard to act on. A semantic message such as

> The object "/metadata/sample/@species" accessed by sample_species_may is optional, but was null or empty. Allowed terms are defined in OPTIONAL rule 'sample_species_may' …

says *what* is wrong in validator language, does not say *where* (line −1, no element), repeats once per affected element, and does not say *how to fix it*.

This work makes every message:

- **actionable** — it leads with a plain-language suggested fix; the diagnosis comes second;
- **located** — it carries the exact place in the file it refers to (line, section, row, column, metadata element or metadata group), recorded by jmzTab-m where the problem is detected;
- **grouped** — repeated messages from the same check collapse into one entry with its occurrences;
- **linked** — in the validator, each message or occurrence opens the document next to the message list and highlights the place.

### Why in jmzTab-m [decided]

jmzTab-m is the reference implementation and knows exactly which element a rule matched (its CV-rule handlers hold JXPath pointers such as `/metadata/sample[3]/species[1]`) and which line and row the parser was reading. Recording locations and fixes there benefits every consumer (CLI, pymzTab-m, rmzTab-m, LipidCompass) and keeps the webapp free of mzTab format logic (D5 of the parent designs).

---

## 2. Goals, non-goals, anti-goals

### Goals

1. jmzTab-m's `ValidationMessage` gains **optional** fields for a structured **location**, a **suggested fix** and the **rule id** of semantic checks. [decided: optional additions, existing contract unchanged]
2. jmzTab-m fills them for structural (format/logical) and semantic (cross-check / CV-mapping) messages wherever the information exists:
   - semantic "wrong or unexpected term" messages: the **exact matched element**; [decided]
   - semantic "element missing or empty" messages: the **closest affected metadata group** (e.g. `sample`); [decided]
   - structural messages: line, section and — where the parser knows it — row and column.
3. Every message type has a **plain-language suggested fix**; the emphasis is on what to do. [decided]
4. Lands on jmzTab-m **2.1.0-SNAPSHOT** first and is **backported to the 1.0.x line**. [decided]
5. The 1.0.x line moves to groupId **`org.lifs-tools`** and Java packages **`org.lifstools.mztab2`** (from `de.isas.mztab` / `de.isas.mztab2`), so that it can replace the 1.0.7-SNAPSHOT the validator uses without import changes. [decided]
6. The validator SPA groups repeated messages, shows the fix first, and links messages and occurrences to the document in a **side-by-side layout** (option B: messages left, document right; narrow screens fall back to jumping to the tab, option A). [decided]
7. Highlighting: a group opens its **first occurrence**; "highlight all" marks **every occurrence**; an occurrence chip opens that occurrence. [decided]

### Non-goals

- Changing what is validated or the severity of any check.
- Rewording or removing the existing `message` text (scripts may match it); the human-friendly text is the new `suggestion`, the old `message` stays as the diagnosis.
- Editing the file in the browser (milestone 2 editor). The fix is described, not applied.
- Translations (English only).
- Locations for the legacy `/rest/v2/validate` and `/validatePlain` response *shape* beyond the additive fields (they return the same `ValidationMessage` objects and therefore gain the fields automatically).

### Anti-goals

- A breaking change to `ValidationMessage` for existing clients (generated pymzTab-m / rmzTab-m clients, LipidCompass `validate.py`).
- Locations computed in the webapp by re-parsing or re-interpreting mzTab (format logic outside jmzTab-m).
- A location that points at the wrong row or element (worse than none). If jmzTab-m cannot determine a location precisely, it leaves the field empty.
- Fix texts that are vague ("check your file") or that suggest terms the active validation profile does not allow.
- The 1.0.x rename silently changing validation results of the deployed validator (see §5.1 versioning).

---

## 3. Current state (facts this design relies on)

1. **Schema.** `ValidationMessage` is defined in `jmzTab-m/api/src/main/resources/mzTab_m_openapi.yml` (~line 2471) with `code`, `category` (format/logical/cross_check), `message_type` (error/warn/info), `message`, `line_number`; Java model classes are generated from it (`api/src/generated-sources/...`) and mirrored in `api/src/main/java/org/lifstools/mztab2/model/ValidationMessage.java`.
2. **Message types and texts.** About 115 types: `FormatErrorType` (38), `LogicalErrorType` (63), `CrossCheckErrorType` (14), each created with a category and level. Texts live in `api/src/main/resources/conf1_1/mztab_{format,logical,crosscheck}_error.properties` as triples per type: `<prefix>_code_<Name>`, `<prefix>_original_<Name>` (message template with `{0}…` parameters) and `<prefix>_cause_<Name>` (explanation).
3. **Semantic checks.** `CvMappingValidator` evaluates each rule's `cvElementPath` with JXPath on the parsed `MzTab`; handlers (`EmptyRuleHandler`, `And/Or/XorValidationHandler`, `SharedParametersValidationHandler`, `ExtraParametersValidationHandler`, `CvTermValidationHandler`, `ResolvingCvRuleHandler`, …) receive `List<Pair<Pointer, Parameter>>` selections — the pointer's `asPath()` is the exact element (1-based JXPath indices) — but build messages via `MZTabError(type, -1, …)` with only the rule's generic path and id in the text. Empty selections (element missing) have no pointer.
4. **Structural checks** report a line number (`MZTabError.lineNumber`) from the parser; section and row are known to the parser at that point but not recorded.
5. **Lines of jmzTab-m:**
   - `master`: 2.1.0-SNAPSHOT, groupId `org.lifs-tools`, packages `org.lifstools.mztab2`.
   - The 1.0.7-SNAPSHOT jars the validator uses today (`org.lifs-tools`, `org.lifstools.mztab2`) were built from `master` at commit `53242461` (manifest `Branch: master`), before `master` moved to 2.1.0-SNAPSHOT. No branch carries that line forward.
   - The 1.0.x line with recent fixes: `fix/1.0.7-json-conversion-and-header-validation` (1.0.7-SNAPSHOT, groupId `de.isas.mztab`, packages `de.isas.mztab2`; recent commits "Report all missing abundance columns in SMH/SFH headers", "CLI: fail on unreadable JSON …"). `development` and `origin/1.0.7` are older `de.isas` states.
6. **Validator.** Messages travel unchanged from jmzTab-m into `ValidationRecord.messages` (persisted JSON), the CSV/JSON report and the SPA (`messages-tab.component`); the section viewer addresses metadata by group and key and tables by 0-based row index and column name (`SectionReader`, `TablePage`, `MetadataPage`). The SPA currently filters/pages messages flatly (25 per page).

---

## 4. Decisions

| # | Decision | Status |
|---|---|---|
| L1 | Locations, fixes and rule ids are recorded by jmzTab-m, as optional `ValidationMessage` fields. | decided |
| L2 | Exact matched element for semantic term messages; closest affected metadata group for "missing or empty". | decided |
| L3 | Grouping of repeated messages in the SPA. | decided |
| L4 | Fix-first, plain-language phrasing; existing `message` kept as the diagnosis. | decided (keeping `message`: my call) |
| L5 | 2.1.0-SNAPSHOT first, backport to 1.0.x. | decided |
| L6 | 1.0.x line renamed to `org.lifs-tools` / `org.lifstools.mztab2`. | decided |
| L7 | Side-by-side layout (B), falling back to jump-to-tab (A) on narrow screens. | decided |
| L8 | Group opens the first occurrence; "highlight all" marks all. | decided |
| L9 | The renamed 1.0.x line publishes **1.0.8-SNAPSHOT**, not 1.0.7-SNAPSHOT. | my call |
| L10 | Grouping key = level + message type code + `rule_id`. Amended while planning: `code` holds jmzTab-m's whole `MZTabError.toString()` (`[Error-2009] line 5: …`), so the type code is its `[Level-NNNN]` prefix; one type has exactly one template, so "type code" already is the parameter-free template. Messages without that prefix group by their `message` text. | my call |

---

## 5. Design

### 5.1 jmzTab-m 1.0.x line: rename and versioning

- **Correction (found while planning, my call):** `fix/1.0.7-json-conversion-and-header-validation` branches from `69e82300`, 153 commits before the `master` commit `53242461` that built the validator's 1.0.7-SNAPSHOT. It is a Java 11 / `javax.validation` / Spring 4.3 / Jackson 2.10 code base, while the validator (Spring Boot 3.5, Java 24, `jakarta.*`) needs the Java 24 / `jakarta` / Spring 6 / Jackson 2.19 code of `53242461`. A renamed `fix/1.0.7…` line could not replace the validator's dependency. Therefore:
  - Branch `1.0.x` is created from **`53242461`** — exactly the code the validator runs today, already `org.lifs-tools` / `org.lifstools.mztab2`, so the decided groupId and packages hold without a rename.
  - The two fixes of the `fix/1.0.7…` line that are not on `53242461` are ported: `910bc44b` (report all missing abundance columns in SMH/SFH headers, assays first) and `b7c36c3f` (CLI fails on unreadable JSON). The branch `fix/1.0.7-json-conversion-and-header-validation` and its PR #207 stay untouched for the project lead to close or keep.
  - The code diff of step 1 below becomes "list what `master` added after `53242461`": the #187 `ColumnPosition` API change (the reason for 2.1.0) is not ported; `09b055d6` "JSON fix" (`@JsonIdentityInfo` on metadata beans, changes the JSON shape) is listed in the comparison report as a candidate for the project lead, not ported.
  - The behavioural diff (step 2) compares the 1.0.7-SNAPSHOT build (`53242461`) with the `1.0.x` head; differences must be explained by the two ported fixes.
- Branch name `1.0.x`. [my call]
- groupId `de.isas.mztab` → `org.lifs-tools` in all poms; Java packages `de.isas.mztab2` → `org.lifstools.mztab2` (main, generated sources, tests, resources such as service files and Spring/XML references, the OpenAPI generator configuration). Artifact ids stay `jmztabm-*`.
- **Versioning [my call, L9]:** the renamed line publishes **1.0.8-SNAPSHOT**. Re-publishing **1.0.7-SNAPSHOT** from a different code line would silently change the validator on its next build (the 1.0.x fixes change validation results, e.g. all missing abundance columns are reported). The validator opts in explicitly by bumping `jmztabm.version` (§5.5).
- Before the rename, the 1.0.x line is compared with the `master`-built 1.0.7-SNAPSHOT (commit `53242461`) [my call on method]:
  1. **Code diff:** the commits on `master` between the point where `fix/1.0.7-json-conversion-and-header-validation` and `master` diverged and `53242461` are listed; every fix that is not 2.1-specific (2.1 model elements, `StudyVariableGroup`, rule-set changes for 2.1) is cherry-picked or re-applied to `1.0.x`.
  2. **Behavioural diff:** both builds validate the same corpus — all fixtures in `jmztabm-test-utils` plus every `*.mzTab` file in `lipidcompass-submissions` / `lipidcompass-studies` (1,483 files on 2026-10-23) — and the structural message lists are compared (code, level, line). The report on the private corpus contains counts only, no file names or message text.
  3. Differences explained by the 1.0.x line's own fixes (e.g. all missing abundance columns reported, unreadable JSON rejected) are **accepted improvements** — they are the project lead's recent work on that line. Any other difference is treated as a regression and fixed before 1.0.8-SNAPSHOT is consumed. The diff report is attached to the PR.
- **CI and publishing [my call]:** jmzTab-m's GitHub Actions workflow is extended so that pushes to `1.0.x` build, test and publish **1.0.8-SNAPSHOT** to the lifstools JFrog snapshot repository with the same credentials as `master`. The validator work (§5.5) needs the published snapshot; until then the SPA part is developed against hand-written fixture JSON containing the new fields, so it is not blocked.

### 5.2 `ValidationMessage` additions (both lines)

Additive, optional fields in `mzTab_m_openapi.yml` (snake_case like the existing ones):

```yaml
ValidationMessage:
  properties:
    # existing: code, category, message_type, message, line_number
    suggestion:
      type: string
      description: Plain-language description of how to fix the problem.
    rule_id:
      type: string
      description: Id of the CV mapping rule that produced a semantic message.
    location:
      $ref: '#/components/schemas/ValidationLocation'
ValidationLocation:
  type: object
  properties:
    section:            # where in the file
      enum: [mtd, smh, sml, sfh, smf, seh, sme, com, file]
    row_index:          # 0-based data row within sml/smf/sme, when known (see §5.3 for the exact definition)
      type: integer
    column:             # mzTab column header, e.g. "abundance_assay[3]", when known
      type: string
    metadata_key:       # exact MTD key, e.g. "assay[3]-separation", when known
      type: string
    metadata_group:     # MTD element group, e.g. "assay", "sample", "sample_processing"
      type: string
    element_path:       # JXPath of the matched model element, e.g. "/metadata/assay[3]/separation"
      type: string
```

- All new fields are optional; absent means "unknown". Existing fields keep their meaning. The line stays only in the existing top-level `line_number` (the location does not duplicate it, so the two can never disagree). [my call]
- One message = one occurrence. Grouping happens in consumers (§5.6), not by merging messages in jmzTab-m, so every occurrence keeps its own location and the message count stays what it is today. [my call]
- The Java model, the JSON (de)serialisation and the generated clients (pymzTab-m, rmzTab-m) gain the fields through the existing generators; no client code needs to change.
- `MZTabError` gains the means to carry a location and rule id, and `toValidationMessage()` copies them and the type's fix text (§5.4).

### 5.3 Recording locations (both lines)

**Semantic messages (`CvMappingValidator` and handlers):**
- Where a handler reports about selected elements, it emits one message per offending pointer with `element_path = pointer.asPath()`, plus the mzTab-level mapping of that path: `section` (`mtd` for `/metadata/…`, `sml`/`smf`/`sme` for the table beans), and `metadata_key` + `metadata_group` (MTD) or `row_index` + `column` (tables). The mapping from model path to mzTab key/column is done in jmzTab-m with its own naming (the same knowledge its writer uses), never in consumers.
- `EmptyRuleHandler` (element missing or empty): no pointer exists; `metadata_group` = the closest metadata element group named by the rule's `cvElementPath` (e.g. `/metadata/sample/@species` → `sample`; `/metadata/@quantificationMethod` → the scalar group `general`), `section = mtd`. If the rule addresses a table, `section` is that table and no row is given.
- Every semantic message sets `rule_id`.
- Where a handler today emits a single message covering several elements (e.g. "1 of N terms not allowed"), it is split so that each element has its own message and location — unless that would change the message *count semantics* that callers rely on (`maxErrors` truncation applies as today). [my call]

**Structural messages (parser):**
- The parser records `section` and `line_number` for every error, and `row_index` / `column` / `metadata_key` where it is processing a specific row, column or MTD line at the time.
- Header problems (e.g. missing `abundance_assay[3]`) carry the header section (`smh`/`sfh`/`seh`) and the column name.

Precision rule (anti-goal): a field is set only when jmzTab-m knows it for certain.

**Definition of `row_index` [my call]:** the 0-based position of the row in the parsed model's list for that section (`MzTab.getSmallMoleculeSummary()`, `getSmallMoleculeFeature()`, `getSmallMoleculeEvidence()`), i.e. the n-th data row of that section in file order, not counting header, comment or blank lines. This is exactly the index the validator's section store uses (`TableRow.index`, written in model order by `SectionWriter`), so a link needs no translation. Example: the second `SML` line of a file → `row_index = 1` → `TableRow.index = 1` → page `floor(1 / size)`.

**Acceptance bar for structural locations [my call]:** every structural message that has a line number also gets a `section`. `row_index`, `column` and `metadata_key` are set where the call site processes a specific row, column or MTD line; the work includes passing the parser's current section/row/column state to the places that create `MZTabError`s for header, MTD-line and data-row checks, but not restructuring the parser. Line numbers keep referring to the file jmzTab-m parsed; for JSON uploads the validator converts to TSV first, so the line refers to the generated TSV — links therefore use section/row/key, never the line.

**Splitting multi-element messages [my call]:** handlers emit one message per offending element by default; `maxErrors` applies to the emitted (split) messages: the parser caps the structural messages at it, and the validator now caps the semantic messages at it too, adding one info message that counts the omitted ones. Message counts for such rules rise; the release notes of jmzTab-m and the validator say so. The validator's grouping (§5.6) keeps the list readable.

### 5.4 Suggested fixes (both lines)

- Each message type gets a fourth property, `<prefix>_fix_<Name>`, next to `_original_` and `_cause_`, with the same `{0}…` parameters available. It is a short imperative sentence in plain language, naming the element and — for CV problems — the allowed terms or term roots with one concrete example term from the active profile's rule.
  - e.g. `c_fix_CvTermNotAllowed=Replace the term on "{1}" with a term allowed by the validation profile: {2}.`
  - e.g. `c_fix_RulePointerObjectNull=Add a value for "{0}". Allowed terms: {3}.` (rendered with the human rule description that `CvMappingUtils.niceToString` already builds)
- All ~115 types get a fix text. Where no useful fix exists, the fix restates the requirement in plain words. [my call]
- The texts are written once on 2.1.0-SNAPSHOT and copied to the 1.0.x line (only types present there).
- The project lead reviews the fix texts (they are user-facing copy) before the 1.0.8-SNAPSHOT is consumed by the validator.

### 5.5 Validator server (jmzTab-m-webapp)

- `jmztabm.version` → **1.0.8-SNAPSHOT** once §5.1–5.4 are published on the 1.0.x line. **The validator validates mzTab-M 2.0 only, through the 1.0.x line** (as decided for milestone 1); the 1.0.x line is therefore the critical path for the validator. The `master` (2.1.0-SNAPSHOT) work serves the CLI and library users of mzTab-M 2.1 and the validator's later move to 2.1, which is a separate decision. Both lines expose the same additive `ValidationMessage` fields, so the validator's OpenAPI document and TypeScript client do not depend on which line is used.
- `ValidationRecord.messages` persist the new fields unchanged (they are part of `ValidationMessage`).
- The CSV report adds columns `suggestion`, `rule_id`, `section`, `row`, `column`, `metadata_key`, `metadata_group` (formula-neutralised like the existing cells). The JSON report includes them automatically.
- The OpenAPI document and the generated TypeScript client are regenerated (`OpenApiSpecExportTest -Dopenapi.update=true`).
- No new endpoint is needed for linking: the SPA computes the target page from `row_index` and the page size (§5.6); if filters or sorting are active they are cleared when jumping, and the column window is moved to include `column`.

### 5.6 Validator SPA

**Message list (Messages tab):**
- Default view: **grouped by rule** — groups keyed by level + type code + `rule_id` (L10). Group header: level chip, the **fix** of the first occurrence (from `suggestion`), a separate count badge "N occurrences" (the fix text itself is never rewritten by the SPA) [my call], then the diagnosis (`message` of the first occurrence, shortened), rule id and validation profile, and occurrence chips (`assay[3]`, `row 2 · abundance_assay[3]`, `metadata group: sample (12)`), collapsed after 8 with "+N more".
- **Records without the new fields** (validated before the bump, kept for up to 7 days): messages show `message` as the headline, no fix line, no chips or links; they group by the same key (L10), since their `code` carries the same type prefix. No migration of persisted records. [my call]
- A toggle "show as flat list" keeps today's flat, filterable table (with a new Location column).
- Filters (text, level, category) apply to both views; group counts reflect filtered occurrences.
- Messages without any location still appear, without links.

**Layout B (side by side) [decided]:**
- On wide screens (≥ 1100 px [my call]) the result page shows the message list on the left (≈ 40 %) and the document (Metadata / Summary / Features / Evidence tabs) on the right; clicking a group or chip only changes the right pane. The split is resizable or fixed [builder's choice].
- On narrow screens it falls back to A: the click switches to the document tab and shows a bar "← Back to message · <fix> · 1 of N ‹ ›".

**Linking and highlighting:**
- Group click → first occurrence; "Highlight all" → all occurrences in the current view (metadata: all matching keys; tables: all rows on the visible page, with "N more on other pages" and ‹ › stepping through pages); chip click → that occurrence.
- Metadata: opens the group panel, scrolls to `metadata_key`, highlights the row (yellow background `#FFF1B8`, left marker `#E0B400` [my call; added to the token set as `--lifs-highlight` and `--lifs-highlight-border`, checked for AA contrast against `--lifs-ink`]); group-only locations open and outline the whole group.
- Tables: opens the right page (`floor(row_index / size)`), moves the column window to include `column`, tints the row and outlines the cell; header locations highlight the header cell.
- Structural messages with only `line_number`: if no `section`/row is known, no link (the line number stays visible).
- Keyboard: groups and chips are buttons; focus moves to the highlighted element; `aria-live` announces "Showing occurrence 1 of 3".

**Analytics:** clicks on messages are not tracked beyond the existing page views. [my call]

### 5.7 Tests

- jmzTab-m (both lines):
  - schema round trip (old JSON without the fields still deserialises; new fields serialise);
  - for each handler: a fixture whose expected messages assert `element_path`, `metadata_key`/`row_index`/`column`, `rule_id`;
  - `EmptyRuleHandler` → `metadata_group`;
  - parser: header and row errors carry section/row/column;
  - every message type has a `_fix_` property (test iterating all types);
  - 1.0.x: the renamed artifacts build and the validator compiles against them with only the version bump.
- Validator server: CSV report columns; persisted records keep the fields.
- SPA: grouping (keys, counts, filters), chip rendering, flat-list toggle, layout B/A switch by width, navigation to metadata key / table row page / column window, highlight-all across pages, accessibility (axe on the result page with a highlighted occurrence).
- e2e: upload a fixture with a known wrong CV term and a missing column → grouped messages → click → highlighted metadata row and table cell.

---

## 6. Order of work

1. jmzTab-m `master` (2.1.0-SNAPSHOT): schema additions, locations, fix texts, tests.
2. jmzTab-m `1.0.x`: compare with the `master`-built 1.0.7-SNAPSHOT, rename groupId/packages, version 1.0.8-SNAPSHOT, backport 1, publish snapshot.
3. jmzTab-m-webapp (on top of `feat/validator-spa` or after PR #77 merges — builder's choice): bump to 1.0.8-SNAPSHOT, CSV columns, regenerate client, SPA grouping/linking/layout B.
4. Fix texts reviewed by the project lead before 3 ships to `-dev`.

---

## 7. Risks

| Risk | Mitigation |
|---|---|
| The 1.0.x line differs from the `master`-built 1.0.7-SNAPSHOT the validator uses | Explicit comparison and port before the rename (§5.1); new version 1.0.8-SNAPSHOT instead of overwriting 1.0.7-SNAPSHOT |
| Package rename breaks reflection/config references (service loaders, Spring XML, JAXB `ObjectFactory`, OpenAPI generator config) | Full build + test suite of the 1.0.x line after the rename; grep for `de.isas` must be empty except history and licence headers |
| Splitting multi-element messages changes message counts and `maxErrors` truncation | Keep `maxErrors` semantics; note count changes in the release notes |
| Wrong locations (model path → mzTab key mapping) | Mapping lives in jmzTab-m next to the writer's naming; per-handler fixture tests assert exact keys |
| Large files: thousands of occurrences in one group | Chips collapse; "highlight all" works per visible page |
| External clients choke on unknown fields | Fields are optional; Jackson-based clients ignore unknown properties by default — verify pymzTab-m / rmzTab-m deserialisation in their test suites |

---

## 8. Left to the builder

- Whether the 1.0.x OpenAPI generator configuration handles the new `ValidationLocation` `$ref` and enum cleanly (spike at the start of the 1.0.x work; adjust generator options if needed).

- Exact JXPath-to-mzTab mapping implementation inside jmzTab-m.
- How handlers attach locations to `MZTabError` (constructor overloads vs. builder).
- The exact fix wording for each type (reviewed by the project lead).
- Split pane implementation (CDK, CSS grid; resizable or not).
- Whether the webapp work lands on `feat/validator-spa` before PR #77 merges or on a follow-up branch.

## 9. Builder check (2026-10-23)

Questions raised by the builder review and how they were settled (all from earlier decisions or the code; none needed the project lead):

1. Which line the validator uses — mzTab-M 2.0 via 1.0.x; `master` serves 2.1 users (§5.5).
2. Baseline of the 1.0.x comparison — code diff + behavioural diff on fixtures and real files; the line's own fixes are accepted improvements (§5.1).
3. Splitting multi-element messages — split by default, `maxErrors` applies after splitting, documented in release notes (§5.3).
4. Structural location bar — every lined message gets a section; row/column/key where the call site processes them; links never use lines (§5.3).
5. `row_index` vs the viewer's index — identical by definition (model list order), with an example (§5.3).
6. Old persisted records and the "N occurrences" prefix — headline falls back to `message`; count is a separate badge (§5.6).
7. Duplicate line field — removed from the location (§5.2); generator compatibility is a builder spike (§8).
8. CI/publishing for `1.0.x` and the snapshot dependency — workflow extended; SPA developed against fixture JSON until the snapshot exists (§5.1).
