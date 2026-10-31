# mzTab-M Editor Phase 2a (Repair and Guidance) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Users can understand every validation problem through specific guidance, and can fix any file that reads as text in place: through repair edits and quick fixes, re-checked by the file's own engine, then downloaded.

**Architecture:**
- **Guidance.** The engines add jmzTab-m's cause text to every message. The webapp matches messages against a curated guidance catalog and quotes the offending lines. It returns guidance with results, as a Markdown resolution report and as a public list.
- **Working copies.** A validation session can get one working copy: the file's text as lines and cells in a SQLite file. Revisioned, undoable edit batches change it. The copy's version engine re-validates the assembled text after edits, and the webapp diffs each run against the previous one.
- **SPA.** The SPA moves its document viewer into a new library project, `projects/mztabm-editor`, which adds editing. A new route `edit/:id` hosts it next to the messages and their guidance.

**Tech Stack:**
- Server: Java 25, Spring Boot 3.5, `spring-jdbc` with `NamedParameterJdbcTemplate`, `org.xerial:sqlite-jdbc` 3.50.3.0, Jackson.
- Engines: jmzTab-m 1.0.8-SNAPSHOT and 2.1.0-SNAPSHOT, via the existing engines.
- SPA: Angular 21.2 with Material/CDK 21.2, Vitest through `@angular/build:unit-test`, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-31-mztabm-editor-design.md` (approved 2026-10-31). It builds on:
- the standalone design (`lipidcompass/docs/design/mztabm-editor/specs/standalone-editor-design.md`);
- `2026-10-23-validator-spa-design.md`;
- `2026-10-23-linked-validation-messages-design.md`;
- `2026-10-30-mztabm-version-engines-design.md`.

The spec's §6 row "2a" is this plan's definition of done.

## Global Constraints

**Branch:** work branches off `feat/validator-spa`; PRs go into `feat/validator-spa`.

**Reference implementation and format logic:**
- Verdicts come only from an engine validating the text. The webapp never parses, writes or validates mzTab-M (D5). Repair edits and quick fixes are text edits (spec §5.3, §5.6).
- The webapp module never depends on jmzTab-m: the existing enforcer ban and ArchUnit test stay green.

**SQL and request parameters:**
- "Please ensure any arguments to the REST API that would affect SQL commands are templated / named parameters only, no anonymous parameter injection or string concatenation gymnastics." Every SQLite statement is a `static final String` constant with `:named` parameters only, run through `NamedParameterJdbcTemplate`. No `?` placeholders, no SQL built by concatenation.
- "Parameters must also be validated and constrained where possible to mitigate those as attack vectors." Out-of-range input is rejected with 400, never clamped.
- Limits [my call]:

  | Input | Limit |
  |---|---|
  | edit batch | ≤ 5,000 operations |
  | one cell | ≤ 100,000 characters |
  | one raw line | ≤ 1,000,000 characters |
  | page size | 1–200 |
  | line ranges | ≤ 1,000 lines |

  Positions must exist, and column names must be in the header.

**Contracts and storage:**
- `/rest/v2/validate`, `/validatePlain`, `/convert` and `/convertPlain` keep their contracts unchanged.
- Working copies have the same retention as sessions: `minCleanupAge` days after the last change.
- Storage is capped by `storage.max-total-bytes`, default 10 GiB.
- Copies are found only by id; there is no listing endpoint.

**Builds:**
- Release builds may depend only on released artifacts; `sqlite-jdbc` 3.50.3.0 is released.
- Never push Docker images. `-Pdocker` runs `jib:build` and pushes; use `jib:dockerBuild` only.
- Build in this worktree.
- zsh: write `${VAR}` before a colon.

**Commits:** every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**OpenAPI:**
- After any REST change, refresh the committed spec with `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest=OpenApiSpecExportTest -Dopenapi.update=true -Dsurefire.failIfNoSpecifiedTests=false`.
- The TS client is regenerated from it by `npm test` and `npm run build` (`pretest`/`prebuild`). It is not committed.

**Copy rules:**
- Plain words, and no bare codes in user-facing text.
- Guidance never says "check the specification".
- A quick fix never picks a CV term, unit or value for the user.

**Docs** are part of each task that changes user-facing behaviour. The final task requests the project lead's review.

## Review Focus

1. **Mixed line endings, a missing trailing newline, a byte-order mark.** A file with these opens and downloads byte-identical, and an edit to line 5 leaves CRLF on line 9 intact. Owner: Task 4 (`roundTripsMixedEndingsAndBom`, `editKeepsOtherLinesBytes`).
2. **A file whose parse stops early.** A file where jmzTab-m stops at line 40 of 900 still opens. Lines 41–900 are editable, and the UI says how far the file was read. Owner: Task 6 (`abortedParseReportsReadUpTo`), Task 10 (e2e `brokenFileOpensAndShowsReadUpTo`).
3. **Two tabs on one copy.** An edit sent against an old revision gets 409, and the tab reloads instead of overwriting. Owner: Task 5 (`staleRevisionIsRefused`), Task 10 (spec `reloadsOnConflict`).
4. **A slow validation finishing after newer edits.** Its result must not be shown as current. Owner: Task 6 (`supersededRunIsMarkedStale`).
5. **A Latin-1 file.** It is not shown as mojibake. Opening it is refused with the line, and re-encoding gives the right characters. Owner: Task 4 (`refusesInvalidUtf8WithLine`), Task 7 (`reencodeLatin1`).

---

### Task 1: Engines report the cause, and publish their error codes

**Files:**
- Modify: `engine-api/src/main/java/org/lifstools/mztab/validator/engine/api/ValidationMessage.java`
- Create: `engine-core-src/src/main/java/org/lifstools/mztab/validator/engine/ErrorCauses.java`
- Modify: `engine-core-src/src/main/java/org/lifstools/mztab/validator/engine/EngineValidator.java` (`converted`)
- Create: `engine-core-src/src/test/java/org/lifstools/mztab/validator/engine/ErrorCausesTest.java`
- Create: `engine-core-src/src/test/java/org/lifstools/mztab/validator/engine/ErrorCodeListTest.java`
- Create (generated, committed): `server/src/main/resources/guidance/error-codes-2.0.json` and `error-codes-2.1.json`
- Modify: `engine-api/src/main/resources/engine-openapi.json` (regenerated)
- Modify: `engine-parent/pom.xml` (surefire property `errorcodes.update=${errorcodes.update}`, default `false`)

**Interfaces:**
- Produces:
  - `ValidationMessage.cause`: a `@Nullable String`, JSON `cause`, appended last to `@JsonPropertyOrder`.
  - `ErrorCauses.causeOf(String code) -> Optional<String>`.
  - The JSON code list: an array of `{"code": 1000, "level": "Error", "keyword": "LinePrefix"}`, sorted by code.

- [ ] **Step 1: Write the failing tests**

```java
// ErrorCausesTest
@Test void causeOfAKnownCode() {
    assertTrue(ErrorCauses.causeOf("[Error-1000] line 5: \"x\" MUST start with ...").isPresent());
}
@Test void causeOfMessagesWithoutTypeCode() {
    assertEquals(Optional.empty(), ErrorCauses.causeOf(""));
    assertEquals(Optional.empty(), ErrorCauses.causeOf("json_not_read"));
    assertEquals(Optional.empty(), ErrorCauses.causeOf("[Error-9999] line 1: unknown"));
}
// EngineValidatorTest (existing class, new test)
@Test void messagesCarryTheCauseOfTheirCode() // validate Fixtures' file with a bad parameter (Error-1012); that message's cause equals MZTabErrorTypeMap().getType(1012).getCause()
// ErrorCodeListTest
@Test void committedListMatchesThisEngine()
// reads ${webapp.dir}/src/main/resources/guidance/error-codes-${engine.line}.json and compares it with
// new MZTabErrorTypeMap().getTypeMap(); with -Derrorcodes.update=true it rewrites the file instead
// (same pattern as EngineOpenApiContractTest)
```

- [ ] **Step 2: Run them and see them fail**

Run: `./mvnw -B -pl engine-mztabm-2.0 -am test -Dfrontend.skip=true -Dtest='ErrorCausesTest,ErrorCodeListTest,EngineValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation failure (no `ErrorCauses`, no `cause`).

- [ ] **Step 3: Implement**

- **`ErrorCauses.causeOf`:** parse the code with `^\s*\[(Error|Warn|Info)-(\d+)\]`, then call `new MZTabErrorTypeMap().getType(n)`. Return its `getCause()` stripped of leading `=` and whitespace. Return empty when it is null or blank. Build the map once, in a static field.
- **`EngineValidator.converted`:** set the cause after `convertValue`.
- **Code list:** generate both files:
  - `./mvnw -B -pl engine-mztabm-2.0 -am test -Dfrontend.skip=true -Dtest=ErrorCodeListTest -Derrorcodes.update=true -Dsurefire.failIfNoSpecifiedTests=false`
  - the same command for `engine-mztabm-2.1`.
- **Contract:** regenerate the engine contract with `-Dtest=EngineOpenApiContractTest -Dopenapi.update=true`.

- [ ] **Step 4: Run both engines' tests**

Run: `./mvnw -B -pl engine-mztabm-2.0,engine-mztabm-2.1 -am verify -Dfrontend.skip=true`

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit** — "Engines report the cause of each message and publish their error codes"

---

### Task 2: Guidance catalog and resolver

**Files:**
- Create in `server/src/main/java/org/lifstools/mztab/validator/webapp/guidance/`:
  - `GuidanceEntry.java`, `ToolVariant.java`, `GuidanceCatalog.java`;
  - `Guidance.java`, `QuotedLine.java`, `GuidanceResolver.java`;
  - `ErrorCodeList.java`.
- Create: `server/src/main/resources/guidance/catalog.json`
- Create in `server/src/test/java/org/lifstools/mztab/validator/webapp/guidance/`: `GuidanceCatalogTest.java`, `GuidanceResolverTest.java`

**Interfaces:**
- Consumes: `ValidationMessage.cause` and the code lists (Task 1).
- Produces:
  - `record GuidanceEntry(String id, List<String> codes, @Nullable String messagePattern, @Nullable List<String> lines, String title, String explanation, String fixHere, String fixInTool, @Nullable String quickFix, boolean stopsParsing, List<ToolVariant> tools)`.
    - `codes` are type codes like `"Error-1012"`.
    - `messagePattern` is a Java regex that must find a match in the message.
    - `lines` restricts the entry to versions (`"2.0"`, `"2.1"`); null means all.
  - `record ToolVariant(String software, @Nullable String toolVersion, String fixInTool)`. `software` is a case-insensitive regex, matched against the name slot of `MTD software[n]`.
  - `GuidanceCatalog.entries() -> List<GuidanceEntry>`.
  - `GuidanceCatalog.match(ValidationMessage m, @Nullable String line) -> Optional<GuidanceEntry>`. The first entry wins: entries with a `messagePattern` are tried before those without.
  - `record QuotedLine(long lineNumber, String text, boolean truncated)`. `text` is at most 2,000 characters, with tabs kept.
  - `record Guidance(@Nullable String entryId, String typeCode, String title, String explanation, String fixHere, String fixInTool, @Nullable String toolFixInTool, @Nullable String quickFix, boolean stopsParsing, @Nullable String cause, @Nullable String suggestion, List<Integer> messages, List<QuotedLine> lines)`. `messages` are indexes into the message list it was resolved for.
  - `GuidanceResolver.resolve(List<ValidationMessage> messages, @Nullable String line, List<String> softwareNames, Function<Set<Long>, Map<Long, String>> lineTexts) -> List<Guidance>`. There is one `Guidance` per (entry or fallback, type code, rule id), in the order of first appearance. Messages without a type code and without an entry fall back to their own text.
  - `ErrorCodeList.codes(String line) -> Set<String>`, e.g. `"Error-1012"`.

- [ ] **Step 1: Write the failing tests**

```java
// GuidanceCatalogTest
@Test void everyCatalogCodeExistsInAnEngine()        // each entry's codes ⊆ ErrorCodeList.codes("2.0") ∪ codes("2.1")
@Test void everyEntryHasBothFixes()                   // fixHere and fixInTool non-blank; title ≤ 60 chars
@Test void coversEveryCodeSeenInRealFiles()          // entries exist for: Error-1000, 1004, 1008, 1009, 1012, 1013, 1014, 1017,
                                                      // 2000, 2002, 2004, 2008, Error-3015, Warn-3005, Warn-3014, Info-3010, Info-2037, Info-2038
@Test void emptyParameterAndUnnamedUserParamDiffer() // match() of '"MTD instrument[1]-detector" value "[, , , ]" is not a valid parameter.'
                                                      // vs '... value "[, , , 25]" ...' gives two different entry ids
@Test void stopsParsingSet()                          // entries for 1000, 1004, 1008, 1009, 2002, 2004 have stopsParsing = true
// GuidanceResolverTest
@Test void groupsRepeatsAndQuotesTheirLines()        // three Error-1012 messages on lines 61, 62, 70 → one Guidance, messages [0,1,2], lines 61, 62, 70 quoted
@Test void picksTheToolVariant()                      // software name "LipidCompass curation script" → toolFixInTool of that variant; "MassHunter" → null
@Test void fallsBackToJmzTabTexts()                   // an unknown code → entryId null, title = message, cause/suggestion copied
@Test void truncatesLongLines()                       // a 5,000-char line → 2,000 chars, truncated = true
@Test void quotesAtMost50LinesPerGuidance()           // 270 messages → messages has 270 indexes, lines has 50
```

- [ ] **Step 2: Run them and see them fail**

Run: `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest='GuidanceCatalogTest,GuidanceResolverTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation failure.

- [ ] **Step 3: Implement**

- **Loading.** `GuidanceCatalog` is a `@Component`. It loads `classpath:guidance/catalog.json` once, and fails startup on an invalid regex or a duplicate id.
- **Catalog content.** Write `catalog.json` with entries for every code in `coversEveryCodeSeenInRealFiles`, using the spec's §1 table and the jmzTab-m texts.
- **Wording rules:**
  - Each entry says, in plain words, what is wrong and why it matters.
  - `fixHere` names the editor action ("Use the quick fix …" or "Edit the field …").
  - `fixInTool` tells an exporter developer what to write instead.
- **Tool variants:** the entries for 1012, 1014, 1017 and Warn-3014 get a variant for `LipidCompass curation` (the template converter) and a variant for `LipidDataAnalyzer` where §1 shows that tool.
- **Format:**

```json
{
  "id": "empty-parameter",
  "codes": ["Error-1012", "Error-1013"],
  "messagePattern": "\"\\[\\s*,\\s*,\\s*,\\s*\\]\"",
  "title": "Empty parameter",
  "explanation": "The field holds a parameter with no ontology, accession, name or value. mzTab-M needs at least a name.",
  "fixHere": "Use the quick fix to remove the line. If the field is required, validation will then ask for it and you can fill it in.",
  "fixInTool": "Leave optional fields out of the file when they have no value, instead of writing [, , , ].",
  "quickFix": "remove-empty-parameter",
  "stopsParsing": false,
  "tools": [{ "software": "LipidCompass curation", "toolVersion": "0.0.7", "fixInTool": "Blank template cells become [, , , ]; fill them or leave the row out of the sheet." }]
}
```

`quickFix` ids are checked in Task 7, which adds `everyQuickFixIdExists`.

- [ ] **Step 4: Run them and see them pass** (command from Step 2). Expected: PASS.

- [ ] **Step 5: Commit** — "Guidance catalog for the problems found in real files"

---

### Task 3: Guidance with results, the resolution report and the problems list

**Files:**
- Create: `server/src/main/java/org/lifstools/mztab/validator/webapp/guidance/UploadLines.java`
- Create: `server/src/main/java/org/lifstools/mztab/validator/webapp/guidance/ResolutionReport.java`
- Create: `server/src/main/java/org/lifstools/mztab2/server/api/validation/GuidanceApiController.java`
- Modify: `server/src/main/java/org/lifstools/mztab/validator/webapp/session/ValidationRecord.java`
  - Add the field `@Nullable List<Guidance> guidance` and `withGuidance(List<Guidance>)`.
  - `ValidationRecordStore` always writes the record with `guidance` = null.
- Modify: `server/src/main/java/org/lifstools/mztab2/server/api/validation/ValidationsApiController.java`
  - `getValidation` fills in `guidance` for FINISHED and FAILED records with messages.
  - `getValidationReport` accepts `format=md`.
- Modify: `RestErrorAdvice` (`assignableTypes` += `GuidanceApiController`)
- Modify: `frontend/openapi/mztab-m-api.json` (regenerated)
- Create in `server/src/test/java/...`: `guidance/UploadLinesTest.java`, `guidance/ResolutionReportTest.java`, `mztab2/server/api/GuidanceApiTest.java`
- Modify: `server/src/test/java/org/lifstools/mztab2/server/api/...ValidationsApi*Test` (the existing class that tests `getValidation`)

**Interfaces:**
- Consumes: `GuidanceResolver`, `GuidanceCatalog` (Task 2).
- Produces:
  - `UploadLines.lines(UUID session, Set<Long> lineNumbers) -> Map<Long, String>`. It reads `MZTABFILE/upload.mztab` once, as UTF-8 with replacement.
  - `UploadLines.softwareNames(UUID session) -> List<String>`. These are the name slots of `MTD software[n]` lines.
  - `ResolutionReport.markdown(ValidationRecord record, List<Guidance> guidance) -> String`.
  - `GET /rest/v2/guidance -> List<GuidanceEntry>`, public and cacheable for 1 hour.
  - `ValidationRecord.guidance` in `GET /rest/v2/validations/{id}`.

- [ ] **Step 1: Write the failing tests**

```java
// UploadLinesTest
@Test void readsRequestedLinesOnly()          // file of 10 lines, request {2, 9, 42} → {2: "...", 9: "..."}; 42 absent
@Test void softwareNamesFromParam()           // "MTD\tsoftware[1]\t[MS, MS:1001456, LipidCompass curation script, v0.0.7]" → ["LipidCompass curation script"]
// ResolutionReportTest
@Test void reportListsProblemsWithToolFixesAndLines()
// markdown contains "# Validation problems in <fileName>", one "## <title> (n)" per guidance, the tool fix
// (or fixInTool), "Lines: 61, 62, 70" and the quoted lines in a fenced block
@Test void reportOfACleanFileSaysSo()         // no messages → "No problems were found."
// ValidationsApi…Test (existing)
@Test void finishedResultCarriesGuidance()    // file with Error-1012 → guidance[0].entryId = "empty-parameter", lines[0].lineNumber set
@Test void reportAsMarkdown()                 // GET …/report?format=md → 200, text/markdown;charset=UTF-8, attachment "<file>-problems.md"
@Test void unknownReportFormatIs400()         // format=pdf → 400 (unchanged rule: csv|json|md)
// GuidanceApiTest
@Test void listsTheCatalog()                  // GET /rest/v2/guidance → 200, contains id "empty-parameter", Cache-Control max-age=3600
```

- [ ] **Step 2: Run them and see them fail**

Run: `./mvnw -B -pl server -am verify -Dfrontend.skip=true -Dtest='UploadLinesTest,ResolutionReportTest,GuidanceApiTest,ValidationsApi*Test' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: failures.

- [ ] **Step 3: Implement**

- **`getValidation`:** resolve guidance with the record's `mzTabVersion` line, `UploadLines.softwareNames` and `UploadLines.lines`.
- **Report download:**
  - the Markdown report is named `<fileName>-problems.md`;
  - CSV and JSON stay as they are.
- **Spec:** refresh the committed OpenAPI spec (Global Constraints).

- [ ] **Step 4: Run them and see them pass** (command from Step 2), then run `OpenApiSpecExportTest`. Expected: PASS.

- [ ] **Step 5: Commit** — "Results carry guidance; Markdown resolution report; public guidance list"

---

### Task 4: Working-copy store (SQLite, exact text)

**Files:**
- Modify: `server/pom.xml`. Add `org.springframework:spring-jdbc` (Boot-managed) and `org.xerial:sqlite-jdbc:3.50.3.0`; set the version as property `sqlite-jdbc.version` in the parent `pom.xml`.
- Modify: `server/src/main/java/org/lifstools/mztab/validator/webapp/service/StorageService.java`
  - `SLOT` += `WORKINGCOPY`.
  - New method `Path localFile(UUID sessionId, SLOT slot, String name)`. It creates the slot directory and returns the path. The name is checked as in `write`.
  - New method `void touch(UUID sessionId)`. It sets the session directory's modification time to now.
- Modify: `FileSystemStorageService.java` (both methods)
- Create in `server/src/main/java/org/lifstools/mztab/validator/webapp/workingcopy/`:
  - `Line.java`, `SourceEncoding.java`, `NotUtf8Exception.java`;
  - `CopyText.java`, `WorkingCopyDb.java`.
- Create in `server/src/test/java/org/lifstools/mztab/validator/webapp/workingcopy/`: `CopyTextTest.java`, `WorkingCopyDbTest.java`, `StaticSqlTest.java`
- Modify: `server/src/test/java/.../FileSystemStorageServiceTest.java` (or the existing storage test)

**Interfaces:**
- Produces:
  - **`record Line(long number, String section, List<String> cells, String eol)`.**
    - `number` is 1-based.
    - `section` is one of `MTD|SMH|SML|SFH|SMF|SEH|SME|COM|BLANK|?`. `BLANK` means an empty line; `?` means the first cell is not a known prefix.
    - `eol` is `"\n"`, `"\r\n"`, `"\r"` or `""`.
    - `text()` returns `String.join("\t", cells)`.
  - **`enum SourceEncoding { UTF_8, WINDOWS_1252, ISO_8859_1 }`.**
  - **`NotUtf8Exception(long lineNumber)`.** Its message is "The file is not encoded as UTF-8; the first line that is not is line <n>."
  - **`CopyText.read(InputStream in, SourceEncoding enc) -> CopyText.Parsed(boolean bom, List<Line> lines)`.**
    - It splits on CRLF, LF and CR, and keeps each ending.
    - With UTF-8 it decodes strictly and throws `NotUtf8Exception` with the first bad line.
  - **`CopyText.write(boolean bom, Iterator<Line> lines, OutputStream out)`.** It writes UTF-8.
  - **`WorkingCopyDb implements AutoCloseable`:**
    - `static WorkingCopyDb open(Path file)`: WAL mode, `busy_timeout` 5000, creates the schema if needed.
    - Line access:
      - `void importLines(CopyText.Parsed parsed)`;
      - `long lineCount()`;
      - `List<Line> lines(long from, int count)`;
      - `Optional<Line> line(long number)`;
      - `Iterator<Line> allLines()`;
      - `void replaceLine(long number, Line line)`;
      - `void insertLines(long before, List<Line> lines)`;
      - `void deleteLines(long from, int count)`.
    - Metadata access:
      - `String meta(String key)` / `void setMeta(String key, String value)`;
      - `boolean bom()`.
    - Transactions: `<T> T inTransaction(Supplier<T> work)`.
    - Inserting or deleting renumbers the following lines. The schema and the ordering key are the implementer's choice: e.g. a `seq` REAL key with gaps, plus a cached number, or renumbering in one statement.
- **Exactness rule (spec §5.2):** `write(read(bytes))` equals `bytes` for every valid UTF-8 input.

- [ ] **Step 1: Write the failing tests**

```java
// CopyTextTest
@Test void roundTripsMixedEndingsAndBom()   // bytes = BOM + "MTD\ta\tb\r\nMTD\tc\td\nSMH\tx\r" + "SML\t1" (no final newline) → write(read) == bytes
@Test void keepsEmptyTrailingCells()        // "SML\t1\t\t\n" → cells ["SML","1","",""]
@Test void sectionsAndUnknownPrefix()       // "mtd\tx\ty" → section "?", "" → "BLANK", "COM\tnote" → "COM"
@Test void refusesInvalidUtf8WithLine()     // line 3 holds byte 0xE9 → NotUtf8Exception.lineNumber() == 3
@Test void reencodesLatin1()                // same bytes with WINDOWS_1252 → line 3 contains "é"
// WorkingCopyDbTest
@Test void importAndReadBack()              // 1,000 lines → lineCount 1000, lines(998, 5) returns 998..1000
@Test void insertAndDeleteRenumber()        // insertLines(3, two lines) → old line 3 is now 5; deleteLines(1,1) → it is 4
@Test void editKeepsOtherLinesBytes()       // CRLF file, replaceLine(5) → write() differs from the original only in line 5
@Test void reopenKeepsContent()             // close + open(file) → same lines and meta
// StaticSqlTest
@Test void sqlIsStaticWithNamedParameters()
// scans server/src/main/java/**/workingcopy/*.java: every string passed to NamedParameterJdbcTemplate/JdbcTemplate
// methods is a static final String constant; no constant contains '?' outside quotes; no '+' concatenation in those constants
// FileSystemStorageServiceTest
@Test void touchUpdatesSessionDirectoryTime()
@Test void localFileRejectsUnsafeNames()    // "../x" → IllegalArgumentException
```

- [ ] **Step 2: Run them and see them fail**

Run: `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest='CopyTextTest,WorkingCopyDbTest,StaticSqlTest,FileSystemStorageServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation failure.

- [ ] **Step 3: Implement** as specified.

Use a `SQLiteDataSource` per file and wrap it in a `NamedParameterJdbcTemplate`. Every statement is a `private static final String`.

- [ ] **Step 4: Run them and see them pass** (command from Step 2).

The image base `eclipse-temurin:25-jre` is Ubuntu (glibc), so sqlite-jdbc's bundled Linux natives apply, and the Linux CI run of `WorkingCopyDbTest` covers native loading. No image build is needed in this task.

Expected: PASS.

- [ ] **Step 5: Commit** — "Working copies store a file's exact text in SQLite"

---

### Task 5: Repair operations, revisions and undo

**Files:**
- Create in `server/src/main/java/org/lifstools/mztab/validator/webapp/workingcopy/`: `EditOperation.java`, `EditBatch.java`, `WorkingCopyEditor.java`, `StaleRevisionException.java`
- Create: `server/src/test/java/org/lifstools/mztab/validator/webapp/workingcopy/WorkingCopyEditorTest.java`

**Interfaces:**
- Consumes: `WorkingCopyDb`, `Line` (Task 4).
- Produces:
  - **`sealed interface EditOperation`**, Jackson-typed by property `"op"`:
    - `SetCell(long line, int cell, String text)`, where `cell` ≥ 1, because cell 0 is the prefix;
    - `SetMetadataValue(long line, String value)` and `RenameMetadataKey(long line, String key)`;
    - `InsertLine(long before, String text)` and `DeleteLine(long line)`;
    - `MoveRow(long line, long before)`;
    - `InsertColumn(String header, int position, String name, String fill)`, `DeleteColumn(String header, String name)` and `RenameColumn(String header, String name, String newName)`. Here `header` is `SMH|SFH|SEH`, and the operation applies to the header and to every row of its section;
    - `JoinWithPrevious(long line)`;
    - `SplitCells(long line)`, which splits the cells at the first run of spaces after the prefix;
    - `SetRawLine(long line, String text)`.
  - **`record EditBatch(long revision, List<EditOperation> operations)`.**
  - **`WorkingCopyEditor`:**
    - `apply(WorkingCopyDb db, EditBatch batch) -> long newRevision`. One transaction; it stores the inverse operations for undo, and a new batch clears redo.
    - `undo(WorkingCopyDb db, long revision) -> long` and `redo(WorkingCopyDb db, long revision) -> long`. Each is a new revision.
    - `canUndo(WorkingCopyDb db)` and `canRedo(WorkingCopyDb db)`.
  - **`StaleRevisionException(long expected, long actual)`.**
- **Limits** (Global Constraints): text may hold a tab only in `SetRawLine` and `InsertLine`, and a newline in none. Violations throw `IllegalArgumentException`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void setCellChangesOneCell()
@Test void metadataValueAndKey()
@Test void insertColumnAlignsEveryRow()        // SMH + 3 SML rows; InsertColumn("SMH", 2, "opt_global_x", "null") → every row has one more cell at index 2
@Test void deleteAndRenameColumn()
@Test void joinWithPreviousRepairsSplitRow()   // "SML\t1\tPC 34:1 with" / " a newline\t5" → one line "SML\t1\tPC 34:1 with a newline\t5"
@Test void splitCellsAtSpaces()                // "MTD\tstudy_variable[1]-assay_refs assay[1], assay[2]" → ["MTD","study_variable[1]-assay_refs","assay[1], assay[2]"]
@Test void undoRedoRestoreExactly()            // apply 3 batches, undo 3 → text equals the original bytes; redo 3 → equals after-state
@Test void newBatchClearsRedo()
@Test void staleRevisionIsRefused()            // apply with revision 0 after revision is 1 → StaleRevisionException(1, 0), nothing changed
@Test void refusesOutOfRangeAndForbiddenText() // line 0, line > count, cell 0, a tab in SetCell, a newline anywhere, 5,001 operations, unknown column name → IllegalArgumentException, nothing changed
@Test void batchIsAtomic()                     // second operation invalid → first not applied, revision unchanged
```

- [ ] **Step 2: Run them and see them fail**

Run: `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest=WorkingCopyEditorTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement.** Store the undo log in `WorkingCopyDb` tables through named-parameter constants, for example inverse operations as JSON per revision. `StaticSqlTest` keeps covering them.

- [ ] **Step 4: Run them and see them pass**, together with `StaticSqlTest`.

- [ ] **Step 5: Commit** — "Repair operations with revisions and undo"

---

### Task 6: Working-copy service, validation loop and REST API

**Files:**
- Create in `server/src/main/java/org/lifstools/mztab/validator/webapp/workingcopy/`:
  - `WorkingCopyService.java`, `WorkingCopyInfo.java`, `WorkingCopyValidation.java`;
  - `TrackedMessage.java`, `MessageDiff.java`, `WorkingCopyReader.java`.
- Modify: `server/src/main/java/org/lifstools/mztab/validator/webapp/engine/EngineResultReader.java`. Add `outcomeOnly(InputStream ndjson) -> ValidationEvent.Outcome`, which reads and discards the section events and writes nothing to storage.
- Modify: `server/src/main/java/org/lifstools/mztab/validator/webapp/sections/TableRow.java`. Add `@Nullable Long lineNumber`; it is null for results, and the existing constructor is kept.
- Create: `server/src/main/java/org/lifstools/mztab2/server/api/validation/WorkingCopiesApiController.java`
- Modify:
  - `RestErrorAdvice`: `StaleRevisionException` → 409. `NotUtf8Exception` → 422 with `error` = `"not_utf8"`. `assignableTypes` += `WorkingCopiesApiController`.
  - `ValidationSessionService.delete`: also deletes the `WORKINGCOPY` slot. It does already, through `deleteAll`; assert this in a test.
  - `frontend/openapi/mztab-m-api.json` (regenerated).
- Create in `server/src/test/java/...`: `workingcopy/MessageDiffTest.java`, `workingcopy/WorkingCopyServiceTest.java` (uses `EngineTestSupport`, the real 2.0 engine) and `mztab2/server/api/WorkingCopiesApiTest.java`.

**Interfaces:**
- Consumes:
  - Tasks 4–5;
  - `EngineRegistry.route`, `EngineClient.validate`, `DeclaredVersions.of`;
  - `SynchronousEngineService.jsonToTsv`, `tsvToJson`;
  - `GuidanceResolver` (Task 2).
- Produces:
  - **`WorkingCopyService`:**
    - `create(UUID validationId, SourceEncoding enc) -> WorkingCopyInfo`. It is idempotent: an existing copy is returned unchanged. A JSON upload is converted by the engine first. The copy id equals the validation id [my call: one copy per session; spec §5.2 puts the copy in the session's slot].
    - `info(UUID id) -> WorkingCopyInfo`.
    - `edit(UUID id, EditBatch b) -> WorkingCopyInfo`, `undo(UUID id, long rev)`, `redo(UUID id, long rev)`.
    - `requestValidation(UUID id, boolean semantic) -> WorkingCopyInfo`. It runs asynchronously on `toolThreadPoolTaskExecutor`, one run per copy at a time. While a run is going, a new request marks it to run again on the latest revision.
    - `writeTsv(UUID id, OutputStream out)` and `writeJson(UUID id, OutputStream out)`. The JSON export needs the last validation to be at the current revision, with no error; otherwise it throws `IllegalStateException` → 409.
    - `delete(UUID id)`.
  - **`record WorkingCopyInfo`:**

    | Field | Type |
    |---|---|
    | `id` | `UUID` |
    | `fileName` | `String` |
    | `revision` | `long` |
    | `mzTabVersion` | `@Nullable String` |
    | `lineCount` | `long` |
    | `sections` | `SectionsSummary` |
    | `canUndo` | `boolean` |
    | `canRedo` | `boolean` |
    | `expiresAt` | `Instant` |
    | `validation` | `@Nullable WorkingCopyValidation` |

  - **`record WorkingCopyValidation`:**

    | Field | Type | Notes |
    |---|---|---|
    | `status` | `Status` | `enum Status { RUNNING, DONE, FAILED }` |
    | `revision` | `long` | |
    | `stale` | `boolean` | |
    | `parsed` | `boolean` | |
    | `readUpTo` | `@Nullable Long` | |
    | `semantic` | `boolean` | |
    | `messages` | `List<TrackedMessage>` | |
    | `fixed` | `List<ValidationMessage>` | |
    | `guidance` | `List<Guidance>` | |
    | `failureReason` | `@Nullable String` | |

  - **`record TrackedMessage(ValidationMessage message, State state)`**, with `enum State { NEW, OPEN }`.
  - **`MessageDiff.compare(List<ValidationMessage> previous, List<ValidationMessage> current) -> Result(List<TrackedMessage> current, List<ValidationMessage> fixed)`.** Messages match by type code plus the first key that is present:
    1. `(metadata_key)`;
    2. `(section, row_index, column)`;
    3. `(line_number)`;
    4. otherwise the message text.
  - **`readUpTo`:** when `parsed` is false, the smallest line number of a message whose catalog entry has `stopsParsing`. Otherwise null.
  - **`WorkingCopyReader`:**
    - `metadataGroups(UUID id)`, `metadata(UUID id, String group, int page, int size, @Nullable String key)` and `table(UUID id, Section s, TableQuery q)`, with the same DTOs as `SectionReader`. Table rows carry `lineNumber`.
    - `lines(UUID id, long from, int count) -> List<Line>`.
  - **REST under `/rest/v2/working-copies`:**
    - `POST ?validation={id}&encoding=UTF_8|WINDOWS_1252|ISO_8859_1` → 201 for a new copy, 200 for an existing one; the body is `WorkingCopyInfo`.
    - `GET /{id}`; `DELETE /{id}` → 204.
    - `POST /{id}/edits` (body `EditBatch`); `POST /{id}/undo?revision=`; `POST /{id}/redo?revision=`.
    - `POST /{id}/validations?semantic=false` → 202.
    - `GET /{id}/metadata`, `/{id}/metadata/{group}` and `/{id}/tables/{section}`, with the same parameters and checks as the validation endpoints.
    - `GET /{id}/lines?from=&count=`, with `count` 1–1000.
    - `GET /{id}/file?format=tsv|json`; `GET /{id}/original`.
- **Validation details:**
  - **Semantic validation** uses the session's profile (`ValidationRecord.validationProfile`, custom mapping included). It is false by default.
  - **Level and error limit** are the session's.
  - **A busy engine** is retried after `Retry-After`, within `engines.jobTimeout`. A run that cannot reach its engine, or that gives up, ends as `FAILED` with `EngineMessages.unavailable(line)`.
- **Retention:** `edit`, `undo`, `redo` and `create` call `storage.touch(id)`. `expiresAt` is the last change plus `minCleanupAge` days.
- **Storage cap:** `create` checks the remaining capacity against the upload size times 3 [my call: the SQLite overhead estimate] and throws `StorageFullException`.

- [ ] **Step 1: Write the failing tests**

```java
// MessageDiffTest
@Test void fixedNewAndOpen()                  // prev [A@key x, B@line 7], curr [B@line 7, C@row 3] → current [B OPEN, C NEW], fixed [A]
@Test void sameCodeOtherPlaceIsNew()
// WorkingCopyServiceTest (real 2.0 engine)
@Test void createFromValidationIsIdempotent()
@Test void createRefusesLatin1WithLine()      // Latin-1 upload → NotUtf8Exception(3); with WINDOWS_1252 → created
@Test void jsonUploadBecomesTsv()             // JSON upload → copy lines start with "MTD\tmzTab-version"
@Test void editThenValidateShowsFixed()       // upload with "[, , , ]" detector; DeleteLine(that line); validate → its Error-1012 in fixed
@Test void abortedParseReportsReadUpTo()      // line 40 holds "XYZ\tfoo" (Error-1000, stops parsing) → parsed=false, readUpTo=40; lines(41, 10) readable
@Test void supersededRunIsMarkedStale()       // validate at revision 1, edit to revision 2 before it finishes → info.validation.revision=1, stale=true; a second run follows
@Test void versionFollowsTheText()            // NO_VERSION file; InsertLine(1, "MTD\tmzTab-version\t2.0.0-M") → mzTabVersion "2.0.0-M" after validation
@Test void jsonDownloadNeedsAValidCurrentState() // errors → IllegalStateException; after fixing → JSON written by the engine
@Test void deletingTheSessionDeletesTheCopy()
// WorkingCopiesApiTest
@Test void createReadEditUndoDownloadRoundTrip()   // POST → 201; edits → revision 1; undo → 2; GET file → bytes == original upload
@Test void staleEditIs409()
@Test void notUtf8Is422WithCode()                  // body.error == "not_utf8"
@Test void parameterBounds()                       // count=0, count=1001, page size 201, bad section, bad encoding, non-UUID id → 400
@Test void unknownCopyIs404()
```

- [ ] **Step 2: Run them and see them fail**

Run: `./mvnw -B -pl server -am verify -Dfrontend.skip=true -Dtest='MessageDiffTest,WorkingCopyServiceTest,WorkingCopiesApiTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement** as specified, then refresh the committed OpenAPI spec.

- [ ] **Step 4: Run them and see them pass**, then the full server suite: `./mvnw -B -pl server -am verify -Dfrontend.skip=true`.

- [ ] **Step 5: Commit** — "Working copies: REST API, validation loop and downloads"

---

### Task 7: Quick fixes

**Files:**
- Create in `server/src/main/java/org/lifstools/mztab/validator/webapp/workingcopy/quickfix/`:
  - `QuickFix.java`, `QuickFixes.java`, `QuickFixPreview.java`, `LineChange.java`;
  - one class per fix, named after its id.
- Modify: `WorkingCopiesApiController` (preview endpoint)
- Modify: `GuidanceCatalogTest` (add `everyQuickFixIdExists`)
- Create in `server/src/test/java/.../workingcopy/quickfix/`: `QuickFixesTest.java`, `QuickFixEngineTest.java` (real engines)

**Interfaces:**
- Consumes: `WorkingCopyDb`, `EditOperation` (Tasks 4–5), the `GuidanceEntry.quickFix` ids (Task 2), the copy's last messages (Task 6).
- Produces:
  - **`interface QuickFix`:**
    - `String id()` and `String title()`;
    - `Optional<List<EditOperation>> forMessage(ValidationMessage m, WorkingCopyDb db)`;
    - `boolean appliesToAll()`.
  - **`QuickFixes.ids() -> Set<String>`** and **`QuickFixes.find(String id) -> Optional<QuickFix>`.**
  - **`record LineChange(long lineNumber, @Nullable String before, @Nullable String after)`.** `before` is null for an insert, `after` is null for a delete.
  - **`record QuickFixPreview(String fixId, String title, long revision, int occurrences, List<LineChange> changes, EditBatch batch)`.** At most 200 changes are listed; `occurrences` counts all of them.
  - **`GET /rest/v2/working-copies/{id}/quick-fixes/{fixId}?message={index}&all=false`** → `QuickFixPreview`. `message` is an index into the last validation's messages. With `all=true`, the preview covers every message of the same guidance group.
  - **Applying:** the client posts `preview.batch()` to `/edits`, so stale previews get 409.
- **Fix ids and behaviour** (spec §5.6):

  | Id | Message | Edit |
  |---|---|---|
  | `remove-empty-parameter` | 1012/1013, the value is `[, , , ]` or a list of only those | `DeleteLine` |
  | `normalise-doi` | 1014 | `SetMetadataValue`. Each `doi:` item drops repeated `doi:` and `https?://(dx\.)?doi\.org/`; other items stay unchanged |
  | `join-broken-line` | 1000, and the previous line exists | `JoinWithPrevious` |
  | `split-mtd-line` | 1008, the line has 2 cells and the second contains a space | `SplitCells` |
  | `add-mandatory-column` | 1004 | `InsertColumn(header, position, name, "null")`. The position is before the first `abundance_` or `opt_` column, else at the end; the name comes from the location column or message parameter |
  | `add-missing-metadata` | 2008 `"X" MUST be defined in the metadata section` | `InsertLine(after the last line of X's group, else after the last MTD line, "MTD\tX\t")` |
  | `remove-unused-cv` | Warn-3014 with prefix P | `DeleteLine` for every `cv[n]-*` line of the CV whose label is P; renumber the higher `cv[m]` keys with `RenameMetadataKey` |
  | `reencode-latin1` | not a message | the client re-creates the copy with `encoding=WINDOWS_1252`; listed in `ids()` for the catalog check only |

  Every other catalog `fixHere` is a field jump that the SPA handles. **No fix picks a CV term, unit or value.**

- [ ] **Step 1: Write the failing tests**

```java
// QuickFixesTest (db fixtures, no engine)
@Test void removeEmptyParameter()
@Test void normaliseDoi()                 // "doi:https://doi.org/10.1/x|doi:doi:10.2/y|pubmed:123" → "doi:10.1/x|doi:10.2/y|pubmed:123"
@Test void joinBrokenLine()
@Test void splitMtdLine()
@Test void addMandatoryColumnBeforeAbundances()
@Test void addMissingMetadataAfterItsGroup()
@Test void removeUnusedCvRenumbers()     // cv[1] MS, cv[2] UO, cv[3] NCIT; Warn-3014 "UO" → cv[2]-* deleted, cv[3]-* renamed cv[2]-*
@Test void noFixWhenThePatternDoesNotMatch()  // 1012 with "[MS, MS:1, , ]" → Optional.empty()
@Test void allCoversEveryOccurrence()    // 270 empty-parameter lines, all=true → occurrences 270, changes lists 200, batch has 270 operations
// QuickFixEngineTest (2.0 engine; repeat for 2.1 where the fixture applies)
@Test void eachFixRemovesItsMessage()    // per fix: fixture with the problem → apply preview batch → validate → the targeted message is in `fixed`
@Test void reencodeLatin1()              // Latin-1 upload → create with WINDOWS_1252 → line text contains "é"; validation runs
// GuidanceCatalogTest
@Test void everyQuickFixIdExists()       // every entry.quickFix ∈ QuickFixes.ids()
```

- [ ] **Step 2: Run them and see them fail**

Run: `./mvnw -B -pl server -am verify -Dfrontend.skip=true -Dtest='QuickFixesTest,QuickFixEngineTest,GuidanceCatalogTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement** as specified, then refresh the committed OpenAPI spec.

The fixtures are synthetic files under `server/src/test/resources/workingcopy/`, one per pattern. Real submission files are not copied in.

- [ ] **Step 4: Run them and see them pass.**

- [ ] **Step 5: Commit** — "Quick fixes for the common mechanical problems"

---

### Task 8: SPA guidance on the result page and the problems page

**Files:**
- Create in `frontend/projects/webapp/src/app/pages/result/`: `guidance-panel.component.{ts,html,scss,spec.ts}`
- Modify in `frontend/projects/webapp/src/app/pages/result/`:
  - `messages-tab.component.{ts,html}`: a group shows its guidance panel when expanded;
  - `message-groups.ts`: `MessageGroup.guidance?: Guidance`, joined by message index;
  - `result-header.component.{ts,html}`: a **Download resolution report** action, `report?format=md`.
- Create: `frontend/projects/webapp/src/app/pages/problems/problems.page.{ts,html,scss,spec.ts}`. It is route `problems`, titled "Common problems and how to fix them in your tool".
- Modify: `frontend/projects/webapp/src/app/app.routes.ts`, `core/page-titles.ts`, `shell/site-footer.component.html` (a link to Problems)

**Interfaces:**
- Consumes: `ValidationRecord.guidance`, `GET /rest/v2/guidance` (Task 3), through the generated client.
- Produces:
  - `<app-guidance-panel [guidance]="Guidance" [messages]="ValidationMessage[]" (jump)="DocTarget">`;
  - `groupMessages(ms, guidance?)` attaches each group's guidance.
- **Panel content, in this order:**
  1. **What's wrong:** `title`, then `explanation`, then `cause` (if any).
  2. **Where:** "line N, `key`" per quoted line, with the line in a monospace block, tabs shown as `→`, and the cell at the message's column highlighted. "and *k* more lines" beyond the quoted ones.
  3. **Fix it here:** `fixHere`. In 2a on the result page, when `quickFix` is set, add "Open the file in the editor to use the quick fix" (Task 10 wires the button).
  4. **Fix it in your tool:** `toolFixInTool ?? fixInTool`, with the tool name when it is a variant.
  5. When `stopsParsing`: "jmzTab-m stopped reading at this line, so problems further down are not reported yet."
  6. For CV-rule messages (`rule_id` set): "Rule `<rule_id>`: required" (error), "recommended" (warn) or "optional" (info). This shows the rule's requirement level (spec §5.5); since jmzTab-m#186, the message level is derived from it.

- [ ] **Step 1: Write the failing specs**

```ts
// guidance-panel.component.spec.ts — host-template driven (TestBed host component; inputs set through the template, not by assignment)
it('shows what, where, fix here and fix in tool in order')
it('prefers the tool variant and names the tool')
it('marks tabs and highlights the cell of the message column')
it('says how many more lines when more are affected than quoted')
it('warns that later problems are hidden when stopsParsing')
it('names the rule requirement level for CV-rule messages')   // rule_id + level warn → "Rule … : recommended"
// messages-tab.component.spec.ts (existing)
it('attaches guidance to its group by message index')
// problems.page.spec.ts
it('lists every catalog entry with its title, explanation and fix in tool')
// result-header.component.spec.ts (existing)
it('links the resolution report download')
```

- [ ] **Step 2: Run them and see them fail**

Run: `cd frontend && npx ng test webapp --no-watch` (the `pretest` hook regenerates the client).

- [ ] **Step 3: Implement** with Angular Material (expansion and buttons, as used now) and the `--lifs-*` tokens. Add no new dependencies.

- [ ] **Step 4: Run them and see them pass.** Also run `npx ng build webapp` and check there are no budget errors.

- [ ] **Step 5: Commit** — "Result page shows guidance per problem; resolution report; problems page"

---

### Task 9: Editor library project; the result page uses it read-only

**Files:**
- Modify: `frontend/angular.json`. Add the project `mztabm-editor` (`projectType: library`, root `projects/mztabm-editor`, with a `test` target of `@angular/build:unit-test`). Add no build target: packaging is phase 2c.
- Modify: `frontend/tsconfig.json`: `paths` `"@lifs-tools/mztabm-editor": ["projects/mztabm-editor/src/public-api.ts"]`.
- Create in `frontend/projects/mztabm-editor/src/`:
  - `public-api.ts`;
  - `lib/document-source.ts`;
  - `lib/metadata-view.component.*`, `lib/table-view.component.*`, `lib/column-picker.component.*`, moved from `webapp/src/app/pages/result/` with their specs;
  - `lib/styles/_mztabm-tokens.scss`.
- Modify: `frontend/projects/webapp/src/app/pages/result/result.page.{ts,html}`. It uses the library components with `ResultDocumentSource`.
- Create: `frontend/projects/webapp/src/app/pages/result/result-document-source.ts`
- Modify: `frontend/pom.xml`. The `npm-test` execution also runs `ng test mztabm-editor --no-watch`, for example through an npm script `test:all`. Keep `npm test` running both.

**Interfaces:**
- Produces:
  - **`abstract class DocumentSource`.** It is provided by the host and holds no generated-client types; the library defines its own minimal interfaces mirroring the DTOs:
    - `metadataGroups(): Observable<DocMetadataGroup[]>`;
    - `metadataPage(group: string, page: number, size: number, key?: string): Observable<DocMetadataPage>`;
    - `tablePage(section: DocSection, q: DocTableQuery): Observable<DocTablePage>`;
    - `readonly editable: boolean`;
    - `applyEdits?(ops: DocEdit[]): Observable<void>`.
  - **`DocEdit`:** a TypeScript union mirroring the server's `EditOperation` JSON (Task 5), with `op` names identical.
  - **Components:** `<mztabm-metadata-view>` and `<mztabm-table-view>`, with the same inputs and outputs as today's tabs (focus target, jump-to, highlight), plus `[readonly]`, default true.
  - **Tokens:** `--mztabm-color-primary`, `--mztabm-color-surface`, `--mztabm-color-text`, `--mztabm-color-text-muted`, `--mztabm-color-error`, `--mztabm-color-warn`, `--mztabm-color-info`, `--mztabm-color-ok`, `--mztabm-highlight`, `--mztabm-highlight-border`, `--mztabm-font-family`, `--mztabm-font-mono` and `--mztabm-radius`. Each defaults to its `--lifs-*` counterpart (spec §5.10, SPA design §5.6).

- [ ] **Step 1: Move the specs first.** Move the existing table, metadata and column-picker specs into the library, adapted to a fake `DocumentSource`. Add:

```ts
it('renders identically for a read-only source')          // same DOM landmarks as before the move (headers, paging, filters)
it('uses --mztabm tokens, not --lifs tokens, in its styles') // no "--lifs-" in projects/mztabm-editor/src/**/*.scss except _mztabm-tokens.scss
it('imports nothing from projects/webapp')                 // source scan
```

- [ ] **Step 2: Run them and see them fail**

Run: `cd frontend && npx ng test mztabm-editor --no-watch`

- [ ] **Step 3: Implement the move.** Behaviour stays unchanged: the existing result-page specs and the e2e `validator.spec.ts` stay green.

- [ ] **Step 4: Run** `npm test`, `npx ng build webapp` and the e2e specs against a local stack (`frontend/e2e`, `E2E_BASE_URL`). Expected: all green.

- [ ] **Step 5: Commit** — "Document views move into the mztabm-editor library"

---

### Task 10: Editor route, fixing in place, and the landing entry

**Files:**
- Modify in `frontend/projects/mztabm-editor/src/lib/`: `metadata-view.component.*` and `table-view.component.*`. When `[readonly]="false"`, cells and values are editable: Enter saves, Escape cancels, keyboard navigation follows the existing table patterns.
- Create in `frontend/projects/mztabm-editor/src/lib/`: `raw-line-editor.component.*`. It shows a line with visible tabs (`→`) and edits it as `SetRawLine`.
- Create in `frontend/projects/webapp/src/app/pages/edit/`:
  - `edit.page.{ts,html,scss,spec.ts}`;
  - `working-copy-source.ts`;
  - `working-copy.store.ts`;
  - `quick-fix-dialog.component.*`.
- Modify:
  - `app.routes.ts`: `edit/:id`;
  - `page-titles.ts`;
  - `pages/result/result-header.component.*`: **Fix this file** → `POST working-copies?validation=id` → `edit/:id`;
  - `pages/result/guidance-panel.component.*`: a quick-fix button when it is hosted by the editor;
  - `pages/landing/landing.page.html` and `validator-form.component.*`: **Open a file to fix**, which submits like Validate and then goes straight to `edit/:id` once the result is final.

**Interfaces:**
- Consumes:
  - the working-copy REST API (Task 6), quick-fix previews (Task 7), `DocumentSource` (Task 9);
  - `GuidancePanelComponent` (Task 8).
- Produces: the `WorkingCopyStore`, a signal store:
  - `info()`, `messages()`, `fixed()`, `guidance()`, `saving()`, `validating()`;
  - `edit(ops)`, `undo()`, `redo()`, `validate(semantic)`, `previewFix(fixId, messageIndex, all)`, `applyFix(preview)`.
- **Behaviour:**
  - **After edits:** after each accepted batch, the store requests structural validation once the user has been idle for 1,500 ms [my call]. While a run is RUNNING, it polls `GET /{id}` every 1,000 ms.
  - **Conflicts:** a 409 reloads the info and the visible pages, and shows the snackbar "This file was changed in another tab. It was reloaded."
  - **Run status:** the message list marks NEW messages and has a collapsible "Fixed since the last check (n)" list. When `stale`: "Checking your latest changes…". When `readUpTo` is set: "jmzTab-m stopped reading at line N; problems further down are not reported yet."
  - **Unreadable lines:** lines with section `?` or `BLANK` in the middle of a section, and MTD lines with 2 or more than 3 cells, open the raw-line editor when their message is selected.
  - **Undo, redo, save state:** undo and redo buttons, plus Ctrl+Z and Ctrl+Shift+Z outside inputs. The save indicator reads "Saved" or "Saving…". The retention notice reads "Kept for 7 days after your last change" (from `expiresAt`/app info).
  - **Downloads:** "Download .mzTab" is always available. "Download JSON" is enabled only for a current validation without errors. "Download original" and "Download resolution report" are also offered.
  - **Encoding:** a 422 `not_utf8` on create shows the line and the button "Re-encode from Windows-1252", which creates the copy with `encoding=WINDOWS_1252`.
  - **Check CV rules:** "Check CV rules" runs semantic validation on demand. The button is shown when the session has a profile.
  - **Version changes:** when `info.mzTabVersion` changes between runs (spec §5.4, R8), show the snackbar "This file is now checked as mzTab-M <line>." It also covers a version that is no longer supported, using the existing unsupported and unavailable texts.

- [ ] **Step 1: Write the failing specs and e2e tests**

```ts
// edit.page.spec.ts
it('validates after edits once idle and marks new and fixed messages')
it('reloadsOnConflict')                                   // 409 on edit → info refetched, snackbar text as above
it('opens the raw line editor for a line without prefix')
it('shows how far the file was read when parsing stopped')
it('offers JSON download only for a current valid state')
it('offers re-encoding on not_utf8')
it('announces a version change after a run')
// quick-fix-dialog.component.spec.ts
it('shows before and after, the count, and applies the batch')
// table-view (library)
it('edits a cell and emits SetCell with its line number')
it('Escape cancels an edit')
```

```ts
// frontend/e2e/editor.spec.ts (new; fixtures in frontend/e2e/fixtures/)
test('fixThisFileWithQuickFixes')        // upload a file with "[, , , ]" + a doi URL → Fix this file → apply both quick fixes → no errors → Download .mzTab has neither problem
test('brokenFileOpensAndShowsReadUpTo') // a line without prefix at line 12 → editor opens, notice names line 12, join fix repairs it, parse completes
test('openAFileToFixFromLanding')
test('unchangedDownloadIsIdentical')     // Fix this file → Download .mzTab → bytes equal the fixture
```

- [ ] **Step 2: Run them and see them fail**

Run: `cd frontend && npx ng test webapp --no-watch && npx ng test mztabm-editor --no-watch`. The e2e tests run against a local stack.

- [ ] **Step 3: Implement** as specified.

- [ ] **Step 4: Run** `npm test`, `npx ng build webapp` and `npx playwright test` against a local stack (webapp plus both engines). Expected: all green.

- [ ] **Step 5: Commit** — "Editor: fix a file in place, with quick fixes, undo and downloads"

---

### Task 11: Performance, documentation and the doc review

**Files:**
- Create: `server/src/test/java/org/lifstools/mztab/validator/webapp/workingcopy/LargeFilePerformanceTest.java`. It is tagged `@Tag("performance")` and excluded from the default surefire run; it runs with `-Dgroups=performance`.
- Create: `server/src/test/java/.../workingcopy/LargeFiles.java`, which generates a file of 846 SML rows × 1,990 columns, 1,521 assays and 5,540 MTD lines.
- Modify: `server/pom.xml` (surefire `excludedGroups` `performance` by default)
- Modify:
  - `frontend/projects/webapp/src/app/pages/about/about.page.html`: an editor section;
  - the inline help on the edit page;
  - `docs/self-hosting/configuration.md`: the working-copy slot, SQLite natives, and caps;
  - `docs/self-hosting/operating.md`: retention of working copies, and disk use roughly 3× the upload;
  - `docs/release-notes/2.0.0.md`, or a new `docs/release-notes/2.1.0.md` if 2.0.0 has shipped by then;
  - `README.adoc`: features.
- Create: `frontend/projects/webapp/src/app/pages/guide/guide.page.*`, route `guide`, titled "Fixing a file". It covers:
  - reading a result and its guidance;
  - fixing in place: edits, raw lines, quick fixes, undo;
  - what "stopped reading" means;
  - downloads and the resolution report;
  - retention;
  - re-encoding.

**Interfaces:**
- Consumes: everything above.
- **Performance budgets** [my call, measured on the generated file], on the developer machine:

  | Operation | Budget |
  |---|---|
  | create a copy from the upload | ≤ 10 s |
  | `lines`/`table` page reads | ≤ 300 ms |
  | one `SetCell` batch | ≤ 100 ms |
  | `InsertColumn` across all rows | ≤ 2 s |
  | assembling the text for validation | ≤ 3 s |
  | one structural validation on the 2.0 engine | reported, not asserted |

  The structural-validation measurement is recorded in the test output and added to the design's §9 risk row as a dated measurement.

- [ ] **Step 1: Write the performance test** with the budgets as assertions, plus the guide page spec:

```ts
it('has a section for each topic and links the problems page')
```

- [ ] **Step 2: Run** `./mvnw -B -pl server -am verify -Dfrontend.skip=true -Dgroups=performance -Dtest=LargeFilePerformanceTest -Dsurefire.failIfNoSpecifiedTests=false`. Record the numbers. If a budget fails, stop and report it with the numbers, as a design risk; do not tune silently.

- [ ] **Step 3: Write the docs** listed above. Then add a short paragraph with today's measurement to the design's §9 row "Re-validating a 32 MB file…".

- [ ] **Step 4: Full verification:** `./mvnw -B verify` (both engines, server, frontend) and `npx playwright test` against a local stack. Expected: BUILD SUCCESS, all e2e tests green.

- [ ] **Step 5: Commit** — "Editor docs, guide page and large-file measurements"

- [ ] **Step 6: Request the doc review.** Ask the project lead to review:
  - the guide page and the about section;
  - the problems page;
  - `catalog.json` (wording of every entry);
  - the self-hosting and release-note changes.

  List the files.
