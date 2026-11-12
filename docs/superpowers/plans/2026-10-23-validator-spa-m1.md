# mzTab-M Validator SPA (Milestone 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace jmzTab-m-webapp's Thymeleaf UI with an Angular 21 SPA in the LIFS design, backed by persisted validation sessions, a read-only section viewer, a validation-profile catalog and new `/rest/v2` endpoints, and retire mzTab 1.0. Released as 2.0.0.

**Architecture:**
- The repo becomes a Maven reactor: parent `pom.xml`, `frontend/` (Angular workspace built by `frontend-maven-plugin`, packaged as a jar of static files) and `server/` (the Spring Boot app, which depends on the frontend jar).
- A validation is a session directory under `storage.location`. It holds the upload, an optional custom mapping, a persisted `record.json` and the parsed sections. Everything goes through `StorageService`.
- The SPA talks to the server through a TypeScript client generated from a committed OpenAPI file. The server serves the SPA's `index.html` for every non-API route, with the base href set to the request's context path.

**Tech Stack:** Java (`maven.compiler.release` 24, built on JDK 25), Spring Boot 3.5.16, springdoc 2.8.17, jmzTab-m 1.0.7-SNAPSHOT, JUnit 5 + MockMvc. Angular 21.2 + Angular Material 21.2, Vitest (Angular 21 default), ng-openapi-gen 1.1.0, Playwright 1.64. Node v24.22.1 via frontend-maven-plugin 1.15.1. Fonts: `@fontsource/inter`, `@fontsource/space-grotesk`, `@fontsource/ibm-plex-sans`, `@fontsource/ibm-plex-mono` 5.3.0. Icons: `material-symbols` 0.48.1.

**Spec:** `docs/superpowers/specs/2026-10-23-validator-spa-design.md` (approved 2026-10-23). Approved mockup: `docs/superpowers/specs/2026-10-23-validator-spa/mockup.html`.

## Global Constraints

- **Branches.** All work happens on the integration branch `feat/validator-spa`, cut from `master` at `0b805ea` or later. Each task goes in as one reviewed PR into `feat/validator-spa`. `master` is untouched until the release.
- **Version.** `2.0.0-SNAPSHOT` from Task 1 on. Never publish `latest` from the integration branch.
- **jmzTab-m is the only format logic (D5).**
  - Parse with `MzTabFileParser`.
  - Produce cell values and column names with `MzTabWriterDefaults` mappers and schemas, as `MzTabMValidator.parse` does today.
  - The only hand-written mzTab reading allowed is the `MTD mzTab-version` sniff (Task 2).
- **Section store (deviation, recorded).** The spec proposes storing sections as jmzTab-m's JSON model. This plan stores **jmzTab-m's writer output** instead: header plus cell strings per row, as JSON Lines. The UI must show mzTab column names (`SML_ID`, `abundance_assay[3]`, `opt_…`). Mapping model fields to those names in the webapp would be new format logic (D5). The spec allows a different store as long as its requirements hold (§5.2), and they do.
- **Unchanged contracts.** `POST /rest/v2/validate` and `/rest/v2/validatePlain` keep their parameters and their `semanticValidation=false` default. `ValidateApiIntegrationTest` must pass unchanged.
- **New endpoints** live under `/rest/v2` in the package `org.lifstools.mztab2.server.api.validation`. The API says "validation profile" (`validationProfile`, `/validation-profiles`), never bare "profile".
- **Storage only through `StorageService`.** No `java.nio.file` access to session data outside `FileSystemStorageService`, because volumes may later become S3.
- **One replica per instance.** Code may assume that the process which reads a `RUNNING` record is the one that ran it.
- **Retention.** `minCleanupAge` (default 7 days). `expiresAt = createdAt + minCleanupAge days`.
- **Limits.**
  - Upload: 64 MB (`spring.servlet.multipart.max-file-size`, unchanged).
  - Custom mapping: 5 MB.
  - `maxErrors` between 1 and 500, default 100.
  - Table page size: 1–200, default 50.
  - Default visible columns: the first 50.
  - Total storage cap: `storage.max-total-bytes`, default `10737418240` (10 GiB).
- **Fixed copy.**
  - mzTab 1.0 rejection: `This file declares mzTab-version 1.0. mzTab 1.0 is no longer supported; the validator checks mzTab-M 2.0 files.`
  - Restart interruption: `The validation was interrupted by a server restart. Please validate the file again.`
  - Storage full: `The validator's storage is full. Please try again later.`
  - Unknown id: `Unknown or expired validation.`
- **Design system** (spec §5.6). The `--lifs-*` tokens and values verbatim; Inter for the app bar and footer only; corners 0, except badges and level chips (2px) and tiles (3px); rose never used for errors; no font, icon or script CDN at runtime.
- **Branding.** FZ Jülich, never ISAS, in all UI text and new docs. Leave the Java copyright headers alone.
- **English only.** No i18n framework.
- **Test commands.**
  - Server: `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`
  - Frontend unit: `npm --prefix frontend run test -- --run`
  - Full: `./mvnw -B verify`

## Review Focus

1. **A restart must not delete uploads.** `Application.init` calls `storageService.deleteAll()` on every start, which wipes every session. Expected: a restart keeps all sessions younger than `minCleanupAge`. *Pinned in Task 4: `restartKeepsSessions`.*
2. **Two browser tabs polling, or a reload while validation runs.** Expected: one validation run per id, never two. *Pinned in Task 4: `submitRunsValidationExactlyOnce`.*
3. **Hostile names and ids in paths.** A multipart filename such as `../../x.mzTab`, an id that is not a UUID, a section or group outside the enum. Expected: the file is stored under a generated name, and the request gets 404 or 400 without touching other paths. *Pinned in Task 6: `hostileFileNameIsStoredSafely`, `nonUuidIdIs404`.*
4. **A file that parses but has no SMF/SME, and a file that does not parse at all.** Expected: absent tables report `null` sizes and their tabs are disabled; an unparseable file shows its messages with outcome `NOT_PARSED` and no section tabs. *Pinned in Task 5: `absentTablesHaveNoSize`, `unparseableFileHasNoSections`.*
5. **Very wide tables in the browser** (846 × 1,990). Expected: the client never requests or renders more than the selected column window. *Pinned in Task 5: `defaultColumnWindowIsFifty`; Task 13: `requestsOnlyTheColumnWindow`.*

---

### Task 1: Integration branch, multi-module layout, 2.0.0-SNAPSHOT, CI

**Files:**
- Create: `pom.xml` (parent `org.lifs-tools:mztab-validator-webapp-parent`, packaging `pom`, modules `frontend`, `server`)
- Move (`git mv`): `pom.xml` → `server/pom.xml` (artifactId stays `mztab-validator-webapp`), `src/` → `server/src/`, `Dockerfile` → `server/Dockerfile`
- Create: `frontend/pom.xml` (artifactId `mztab-validator-frontend`, packaging `jar`; in this task it packages nothing)
- Modify: `.github/workflows/maven.yml`, `README.adoc`

**Interfaces:**
- Produces: module paths `server/` and `frontend/`, the property `frontend.skip` (default `false`), the version `2.0.0-SNAPSHOT`, and the test commands in Global Constraints.

- [ ] **Step 1: Create the integration branch.** `git switch -c feat/validator-spa origin/master && git push -u origin feat/validator-spa`. Work in a worktree (`superpowers:using-git-worktrees`); VS Code's Java extension rewrites `target/` while Maven runs.
- [ ] **Step 2: Baseline.** Run `./mvnw -B test`. Expected: 17 tests, 0 failures.
- [ ] **Step 3: Split the pom.**
  - The parent holds `<properties>`, `<repositories>`, `<distributionManagement>`, `<dependencyManagement>`, `<pluginManagement>` and the profiles `dev`, `microservice`, `release`.
  - `server/pom.xml` keeps `<dependencies>`, `<build><resources>` and `<build><plugins>`, plus the `docker` profile (Jib).
  - Set the version to `2.0.0-SNAPSHOT` everywhere.
- [ ] **Step 4: Update CI.** In `.github/workflows/maven.yml`:
  - add `feat/validator-spa` to `pull_request.branches` and `push.branches`;
  - keep `./mvnw -B verify` for PRs;
  - the master publish steps stay conditioned on `github.ref == 'refs/heads/master'`;
  - add a `workflow_dispatch` path that, on `feat/validator-spa`, logs in and runs `./mvnw -B package -Pdocker "-Djib.to.tags=${RELEASE_VERSION}"`. That pushes only `2.0.0-SNAPSHOT`, never `latest`.
- [ ] **Step 5: README.** Update the build and run section for the module layout, and replace `isas/mztab-validator-webapp` with `lifs/mztab-validator-webapp`.
- [ ] **Step 6: Verify.** Run `./mvnw -B verify`. Expected: 17 tests pass and `server/target/mztab-validator-webapp-2.0.0-SNAPSHOT.jar` exists. Then `java -jar server/target/mztab-validator-webapp-2.0.0-SNAPSHOT.jar --server.port=0` logs `Started Application` (stop it).
- [ ] **Step 7: Commit and open the PR into `feat/validator-spa`.** `git commit -m "build: parent + server + frontend modules, 2.0.0-SNAPSHOT, CI for the integration branch"`

---

### Task 2: Retire mzTab 1.0

**Files:**
- Delete: `server/src/main/java/org/lifstools/mztab/validator/webapp/service/validation/EbiValidator.java`
- Create: `.../service/validation/MzTabVersionDetector.java`
- Modify: `ValidationService` (remove `MzTabVersion` and the version parameters), `MzTabValidationService`, `ValidationController` and `validation-form.html` (drop the version select; Thymeleaf lives until Task 15), `server/pom.xml` (remove `uk.ac.ebi.pride:jmztab-modular-model`, `jmztab-modular-util` and the `jmztab.version` property), `application.properties` (`jmztab.version.number`), `AppInfo`, and the 1.0 strings in `i18n/messages.properties`
- Test: `.../service/validation/MzTabVersionDetectorTest.java`, `MzTabMValidatorTest.java` (extend)

**Interfaces:**
- Produces:
  - `final class MzTabVersionDetector { static Optional<String> detect(Path file) throws IOException; static boolean isLegacy(String version); }`
  - `detect` returns the value of the first `MTD\tmzTab-version\t…` line, or empty.
  - `isLegacy` is true for values starting with `1.`.

- [ ] **Step 1: Write failing tests.**

```java
@Test void detectsMzTabMVersion()            // minimal-m-2.0.mztab -> Optional.of("2.0.0-M")
@Test void detectsLegacyVersion()            // temp file "MTD\tmzTab-version\t1.0.0\n" -> "1.0.0"; isLegacy("1.0.0") == true; isLegacy("2.0.0-M") == false
@Test void missingVersionLineIsEmpty()       // temp file "COM\tnothing\n" -> Optional.empty()
@Test void readsOnlyUntilMetadataEnds()      // the version line after the first non-MTD/COM line is ignored
```

- [ ] **Step 2: Run them.** `…-Dtest=MzTabVersionDetectorTest`. Expected: FAIL (class missing).
- [ ] **Step 3: Implement the detector**, then remove every 1.0 code path and dependency listed under **Files**. `ValidateApi` and `ValidatePlainApi` stop passing `MzTabVersion.MZTAB_2_0`.
- [ ] **Step 4: Verify.** Run `./mvnw -B -pl server -am test -Dfrontend.skip=true`. Expected: all tests pass, and `grep -rn "EbiValidator\|MZTAB_1_0\|jmztab-modular" server/` finds nothing.
- [ ] **Step 5: Commit.** `git commit -m "feat!: retire mzTab 1.0 validation and its dependencies"`

---

### Task 3: Validation profile catalog

**Files:**
- Create: `server/src/main/resources/validation-profiles/index.json`
- Create: `server/src/main/resources/validation-profiles/mztab-m-2.0-default/1.0.0.xml` (a byte-for-byte copy of `server/src/main/resources/mappings/mzTab-M-mapping.xml`)
- Create: `server/src/main/java/org/lifstools/mztab/validator/webapp/validationprofile/ValidationProfile.java`, `ValidationProfileCatalog.java`
- Create: `server/src/main/java/org/lifstools/mztab2/server/api/validation/ValidationProfilesApiController.java`
- Modify: `SafeCvMappingLoader` (add the XSD pass), `application.properties`
- Test: `.../validationprofile/ValidationProfileCatalogTest.java`, `.../api/validation/ValidationProfilesApiControllerTest.java`

**Interfaces:**
- **`index.json` (exact):** `[{"id":"mztab-m-2.0-default","version":"1.0.0","title":"mzTab-M 2.0 default rules","description":"The reference semantic rules shipped with jmzTab-m.","mzTabVersions":["2.0.0-M"]}]`
- Produces:
  - `record ValidationProfile(String id, String version, String title, String description, List<String> mzTabVersions)`
  - `ValidationProfileCatalog`:
    - `List<ValidationProfile> latest()`: one entry per id, highest version by semver order;
    - `Optional<ValidationProfile> find(String id, @Nullable String version)`: `null` version means the latest;
    - `URL mappingUrl(ValidationProfile p)`: `classpath:/validation-profiles/{id}/{version}.xml`.
  - `SafeCvMappingLoader.validateAgainstSchema(byte[] xml)` throws `InvalidMappingFileException`. It uses `classpath:/schema/CvMapping.xsd` (from `jmztabm-validation`), with `ACCESS_EXTERNAL_DTD` and `ACCESS_EXTERNAL_SCHEMA` set to `""`, after the existing hardened SAX pass.
  - Property `validation.custom-mappings.enabled=true`.
  - `GET /rest/v2/validation-profiles` returns `200 [ValidationProfile…]` (the latest versions).

- [ ] **Step 1: Write failing tests.**

```java
@Test void catalogListsDefaultProfileOnly()             // latest() == [mztab-m-2.0-default 1.0.0]
@Test void everyCatalogMappingValidatesAgainstXsd()     // for every (id, version) in index.json: validateAgainstSchema(bytes) does not throw  (CI guard for future entries)
@Test void unknownProfileIsEmpty()                      // find("nope", null).isEmpty(); find("mztab-m-2.0-default", "9.9.9").isEmpty()
@Test void latestPicksHighestVersion()                  // catalog built from a test index with 1.0.0 and 1.10.0 -> latest version "1.10.0"
@Test void schemaRejectsEmptyCvMapping()                // validateAgainstSchema("<CvMapping/>") throws InvalidMappingFileException
@Test void profilesEndpointListsCatalog()               // GET /rest/v2/validation-profiles -> $[0].id == "mztab-m-2.0-default", $[0].version == "1.0.0"
```

- [ ] **Step 2: Run them.** `…-Dtest='ValidationProfileCatalogTest,ValidationProfilesApiControllerTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Load `index.json` once with the `ObjectMapper` bean, and resolve resources with `ResourceLoader`.
- [ ] **Step 4: Run them again.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(validation-profiles): versioned catalog with the jmzTab-m default, XSD check"`

---

### Task 4: Persisted validation sessions

**Files:**
- Modify: `StorageService`, `FileSystemStorageService`, `Application` (remove `storageService.deleteAll()` from `init`), `MzTabMValidator` (add `validateAndParse`), `StorageProperties` (add `maxTotalBytes`)
- Create, in `server/src/main/java/org/lifstools/mztab/validator/webapp/session/`: `ValidationRecord.java`, `ValidationOptions.java`, `ProfileRef.java`, `ValidationStatus.java`, `ValidationOutcome.java`, `SectionsSummary.java`, `TableSize.java`, `ValidationSessionService.java`, `ValidationRunner.java`, `SessionRecovery.java`, `StorageFullException.java`
- Test: `.../session/ValidationSessionServiceTest.java`, `.../service/storage/FileSystemStorageServiceTest.java`

**Interfaces:**
- **`StorageService` additions** (file backend only):
  - `enum SLOT { MZTABFILE, MAPPINGFILE, RECORD, SECTIONS }`
  - `void write(UUID sessionId, SLOT slot, String name, InputStream content)`: atomic (temp file plus move); `name` must match `[A-Za-z0-9._-]{1,64}`, otherwise `IllegalArgumentException`.
  - `Optional<InputStream> read(UUID sessionId, SLOT slot, String name)`
  - `Stream<UUID> sessions()`
  - `long totalBytes()`
- Produces:
  - `enum ValidationStatus { QUEUED, RUNNING, FINISHED, FAILED }`
  - `enum ValidationOutcome { PASSED, PASSED_WITH_NOTES, FAILED, NOT_PARSED }`
    - `NOT_PARSED`: the file could not be parsed into a model.
    - `FAILED`: at least one ERROR.
    - `PASSED_WITH_NOTES`: only WARN or INFO messages.
    - `PASSED`: no messages.
  - `record ProfileRef(Kind kind, String id, String version)`, with `enum Kind { NONE, CATALOG, CUSTOM }`
  - `record TableSize(int rows, int columns)`
  - `record SectionsSummary(int metadataLines, TableSize sml, TableSize smf, TableSize sme)`: a `null` TableSize means the table is absent.
  - `record ValidationRecord(UUID id, String fileName, ValidationStatus status, ValidationOutcome outcome, ValidationLevel level, int maxErrors, ProfileRef validationProfile, String mzTabVersion, Instant createdAt, Instant startedAt, Instant finishedAt, Instant expiresAt, Map<ValidationLevel,Integer> counts, List<ValidationMessage> messages, SectionsSummary sections, String failureReason)`
    - Persisted as `RECORD/record.json`.
    - `counts` always holds all three levels.
  - `record ValidationOptions(ValidationLevel level, int maxErrors, String validationProfileId, String validationProfileVersion)`: profile id `null` means structural validation only.
  - `ValidationSessionService`:
    - `ValidationRecord submit(String fileName, InputStream content, ValidationOptions options, @Nullable InputStream customMapping)`: stores the upload as `upload.mztab`; returns a `QUEUED` record; throws `StorageFullException` when `totalBytes() >= maxTotalBytes`, and `IllegalArgumentException` for an unknown profile or a mapping rejected by `SafeCvMappingLoader` (parse and XSD);
    - `Optional<ValidationRecord> find(UUID id)`;
    - `boolean delete(UUID id)`.
  - `ValidationRunner.run(UUID id)`: `@Async("toolThreadPoolTaskExecutor")`.
    1. Moves the record from `QUEUED` to `RUNNING`, exactly once per id (per-id lock plus a status check).
    2. Applies the version sniff from Task 2. A legacy file ends `FAILED` with outcome `NOT_PARSED` and the single ERROR message from Global Constraints.
    3. Calls `MzTabMValidator.validateAndParse`.
    4. Writes the sections (Task 5 hook: `SectionWriter.write`; a no-op stub in this task).
    5. Persists the result as `FINISHED`.
    6. Any exception leads to `FAILED` with `failureReason`.
  - `MzTabMValidator.validateAndParse(Path file, ValidationLevel level, int maxErrors, @Nullable Path mapping)` returns `record Outcome(List<ValidationMessage> messages, @Nullable MzTab mzTab)`. A `null` mapping skips semantic validation. The existing `validate(...)` delegates to it.
  - `SessionRecovery`: an `ApplicationRunner` that turns every `QUEUED` or `RUNNING` record into `FAILED`, with the restart message from Global Constraints.

- [ ] **Step 1: Write failing tests** (`@SpringBootTest` with a `@TempDir` storage location and the OLS mock as in `MappingUploadIntegrationTest`).

```java
@Test void submitPersistsQueuedRecord()           // submit(...) -> status QUEUED, expiresAt == createdAt + 7 days; RECORD/record.json exists
@Test void validFileFinishes()                    // lipidomics-example.mzTab, no profile -> eventually FINISHED, outcome in {PASSED, PASSED_WITH_NOTES, FAILED}, mzTabVersion "2.0.0-M", counts has keys INFO, WARN, ERROR
@Test void legacyFileIsRejectedWithMessage()      // "MTD\tmzTab-version\t1.0.0\n..." -> FAILED, NOT_PARSED, messages == [ERROR with the exact 1.0 text]
@Test void catalogProfileRunsSemanticValidation() // validationProfileId "mztab-m-2.0-default" -> record.validationProfile == (CATALOG, "mztab-m-2.0-default", "1.0.0"); verify lookupService was used
@Test void customMappingWithDoctypeIsRejected()   // MappingFixtures.MAPPING_WITH_DOCTYPE -> IllegalArgumentException; no session directory left behind
@Test void unknownProfileIsRejected()             // validationProfileId "nope" -> IllegalArgumentException
@Test void submitRunsValidationExactlyOnce()      // runner.run(id) called concurrently from 2 threads -> validator invoked once (spy on MzTabMValidator)
@Test void restartKeepsSessions()                 // submit, finish, then run Application's CommandLineRunner -> find(id) still present
@Test void interruptedRunIsFailedOnStartup()      // write a RUNNING record, run SessionRecovery -> FAILED with the restart message
@Test void storageFullRefusesSubmit()             // storage.max-total-bytes=1 -> StorageFullException
@Test void deleteRemovesEverything()              // delete(id) == true; find(id).isEmpty(); delete(id) == false
```

```java
// FileSystemStorageServiceTest
@Test void writeIsAtomicAndReadable()
@Test void rejectsUnsafeNames()                   // write(id, RECORD, "../x", ...) -> IllegalArgumentException
@Test void cleanupRemovesRecordAndSections()      // a session older than minCleanupAge is removed by the scheduled cleanup, including RECORD and SECTIONS
```

- [ ] **Step 2: Run them.** `…-Dtest='ValidationSessionServiceTest,FileSystemStorageServiceTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Serialise records with the `ObjectMapper` bean (dates as ISO strings, per `JacksonConfiguration`). Make the existing `ToolResultService` path keep working for the Thymeleaf controller until Task 15.
- [ ] **Step 4: Run them, then the whole server suite.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(sessions): persisted validation records, single-run guarantee, restart recovery, storage cap"`

---

### Task 5: Section store and paging

**Files:**
- Create, in `server/src/main/java/org/lifstools/mztab/validator/webapp/sections/`: `Section.java`, `SectionWriter.java`, `SectionReader.java`, `TableQuery.java`, `ColumnFilter.java`, `TablePage.java`, `TableRow.java`, `MetadataGroup.java`, `MetadataPage.java`, `MetadataEntry.java`
- Modify: `ValidationRunner` (call the real `SectionWriter`), `MzTabMValidator` (move the writer-defaults code of `parse(...)` into `SectionWriter`; `parse` stays as a thin adapter over it, because the Thymeleaf result page uses it until Task 15 deletes both)
- Test: `.../sections/SectionStoreTest.java`

**Interfaces:**
- Produces:
  - `enum Section { SML, SMF, SME }`
  - `SectionWriter.write(UUID id, MzTab mzTab)` returns `SectionsSummary`. It writes `SECTIONS/metadata.jsonl` (one `MetadataEntry` per line) and `SECTIONS/{sml|smf|sme}.jsonl`: the first line is the header (`["SML_ID", …]`), each further line is a row of cell strings. Cells and headers come from `MzTabWriterDefaults` mappers and schemas. An empty or absent table writes no file and yields a `null` size.
  - `record MetadataEntry(int lineNumber, String key, String value)`
  - `record MetadataGroup(String name, int count)`: the group is the key up to the first `[` (`assay[1]-name` → `assay`); keys without `[` go to group `general`. Groups keep file order.
  - `record MetadataPage(String group, int page, int size, long total, List<MetadataEntry> entries)`
  - `record ColumnFilter(String column, String contains)`: case-insensitive substring.
  - `record TableQuery(int page, int size, @Nullable String sort, boolean descending, List<ColumnFilter> filters, List<String> columns)`
  - `record TableRow(int index, List<String> cells)`: `index` is the 0-based row index in the file.
  - `record TablePage(Section section, int page, int size, long totalRows, List<String> allColumns, List<String> columns, List<TableRow> rows)`: `columns` are the returned ones; `cells` follow `columns`.
  - `SectionReader`:
    - `List<MetadataGroup> metadataGroups(UUID id)`
    - `Optional<MetadataPage> metadata(UUID id, String group, int page, int size)`
    - `Optional<TablePage> table(UUID id, Section s, TableQuery q)`
  - Rules for `table`:
    - Empty `columns` means the first 50.
    - Unknown column names give `IllegalArgumentException`.
    - Sorting compares numerically when both cells parse as `double`, otherwise as text; `"null"` and empty cells sort last in both directions.
    - Filters apply before paging; `totalRows` counts the filtered rows.
    - Keep at most 4 parsed tables in a size-bounded LRU cache.

- [ ] **Step 1: Write failing tests** with `lipidomics-example.mzTab`, `lipidomics-example-missing-feature-and-evidence.mzTab` and `MouseLiver_negative.mzTab` from `jmztabm-test-utils`.

```java
@Test void writesHeaderAndRowsWithMzTabColumnNames()  // SML allColumns starts with "SML_ID"; contains an "abundance_assay[1]" column
@Test void absentTablesHaveNoSize()                   // missing-feature-and-evidence: summary.smf == null && summary.sme == null; table(id, SMF, q).isEmpty()
@Test void unparseableFileHasNoSections()             // runner on "this is not mzTab": record.sections == null, outcome NOT_PARSED
@Test void defaultColumnWindowIsFifty()               // MouseLiver SML: q.columns empty -> columns.size() == min(50, allColumns.size()); each row.cells.size() == columns.size()
@Test void sortsNumericallyWithNullsLast()            // sort by an abundance column ascending: parsed doubles non-decreasing; "null" cells only at the end
@Test void filterThenPage()                           // filter SML_ID contains "1" -> totalRows == count of matching rows; page 1 of size 2 holds rows 3-4 of the filtered list
@Test void unknownColumnIsRejected()                  // columns ["nope"] -> IllegalArgumentException
@Test void metadataGroupsFollowFileOrder()            // first group "general"; contains "assay" with count == number of assay[n] lines
@Test void largeFilePagesQuickly()                    // MouseLiver: 20 consecutive table() calls complete in < 2 s total after the first
```

- [ ] **Step 2: Run them.** `…-Dtest=SectionStoreTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Write JSON Lines with the `ObjectMapper`, and stream rows while reading.
- [ ] **Step 4: Run them, then the server suite.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(sections): jmzTab-m writer output as JSON Lines, paging, sort, filter, column window"`

---

### Task 6: Validations REST API and JSON errors

**Files:**
- Create, in `server/src/main/java/org/lifstools/mztab2/server/api/validation/`: `ValidationsApiController.java`, `RestErrorAdvice.java`
- Test: `server/src/test/java/org/lifstools/mztab2/server/api/validation/ValidationsApiControllerTest.java`

**Interfaces:**
- Consumes: `ValidationSessionService`, `SectionReader`, `ValidationProfileCatalog` (Tasks 3–5).
- **OpenAPI tags** (they become the generated service names in Task 9): `@Tag(name = "validations")` here; `@Tag(name = "validation-profiles")` on Task 3's controller; `@Tag(name = "examples")` and `@Tag(name = "app-info")` in Task 7.
- Produces:

| Method and path | Request | Response |
|---|---|---|
| `POST /rest/v2/validations` | multipart `file` (required), `level` (default `INFO`), `maxErrors` (1–500, default 100), `validationProfile`, `validationProfileVersion`, `mappingFile` | `202` `ValidationRecord`, `Location: …/validations/{id}`. `mappingFile` and `validationProfile` together → 400. `mappingFile` while custom mappings are disabled → 403. |
| `GET /rest/v2/validations/{id}` | | `200` `ValidationRecord`; `404` |
| `DELETE /rest/v2/validations/{id}` | | `204`; `404` |
| `GET /rest/v2/validations/{id}/report?format=csv\|json` | | attachment `<fileName>-validation.csv\|json`; CSV columns `level,line,category,code,message` |
| `GET /rest/v2/validations/{id}/metadata` | | `200 [MetadataGroup]`; `404` |
| `GET /rest/v2/validations/{id}/metadata/{group}?page=0&size=50` | | `200 MetadataPage`; `404` |
| `GET /rest/v2/validations/{id}/tables/{section}?page=0&size=50&sort=&descending=false&filter=COL:text&columns=A,B` | `filter` repeatable; `section` in `sml\|smf\|sme` | `200 TablePage`; `404` (absent table or unknown id); `400` (bad column or size) |

- `RestErrorAdvice` (`@RestControllerAdvice(basePackages = "org.lifstools.mztab2.server.api")`) answers with `ErrorResponse`:
  - 400 for `IllegalArgumentException` and validation errors;
  - 404 for unknown ids, with the fixed copy;
  - 413 for `MaxUploadSizeExceededException`;
  - 503 for `StorageFullException`, with the fixed copy.

- [ ] **Step 1: Write failing tests** (MockMvc).

```java
@Test void createReturns202AndLocation()
@Test void pollingReachesFinished()                    // GET until status FINISHED (≤ 30 s)
@Test void profileAndMappingTogetherIs400()
@Test void customMappingDisabledIs403()                // @TestPropertySource validation.custom-mappings.enabled=false
@Test void maxErrorsOutOfRangeIs400()                  // 0 and 501
@Test void hostileFileNameIsStoredSafely()             // multipart filename "../../evil.mzTab" -> 202; no file outside the session dir; record.fileName == "evil.mzTab"
@Test void nonUuidIdIs404()                            // GET /validations/not-a-uuid -> 404 with message "Unknown or expired validation."
@Test void deleteThenGetIs404()
@Test void reportCsvHasHeaderAndRows()                 // first line "level,line,category,code,message"
@Test void tablesEndpointPagesWithColumns()            // columns=SML_ID -> $.columns == ["SML_ID"], $.rows[0].cells.length() == 1
@Test void absentTableIs404()
@Test void uploadTooLargeIsJson413()                   // spring.servlet.multipart.max-file-size=1KB in a test profile -> 413, JSON body with "status"
@Test void storageFullIsJson503()
@Test void openApiListsNewEndpoints()                  // /v3/api-docs/mztab-m-api has paths /rest/v2/validations and /rest/v2/validations/{id}/tables/{section}
```

- [ ] **Step 2: Run them.** `…-Dtest=ValidationsApiControllerTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Store the upload's base name only (`Paths.get(name).getFileName()`, with path separators stripped). Generated names stay inside storage (Task 4).
- [ ] **Step 4: Run them, plus `ValidateApiIntegrationTest`** (unchanged contracts). Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(api): validations, sections and report endpoints with JSON errors"`

---

### Task 7: Examples and app-info endpoints

**Files:**
- Create, in `server/src/main/java/org/lifstools/mztab2/server/api/validation/`: `ExamplesApiController.java`, `AppInfoApiController.java`, `ExampleFetcher.java`
- Modify: `application.properties` (add `matomo.tracker-base=https://lifs-tools.org/matomo/`)
- Test: `ExamplesApiControllerTest.java`, `AppInfoApiControllerTest.java`

**Interfaces:**
- Produces:
  - `record ExampleDto(String id, String name, String section, String description, String version, String focus, String createdBy, URI uri)`: `id` = `name`; `section` is `example` or `mapping`, from `examples.properties`.
  - `GET /rest/v2/examples` returns `200 [ExampleDto]`.
  - `POST /rest/v2/examples/{id}/validations` (JSON body `{level, maxErrors, validationProfile, validationProfileVersion}`) returns `202 ValidationRecord`. Unknown id → 404; a `mapping` example → 400.
  - `ExampleFetcher.fetch(URI uri)` returns `InputStream`, using `java.net.http.HttpClient`: 10 s connect timeout, 60 s request timeout, at most 64 MB. It is called only with the configured `ExampleDto.uri`.
  - `record AnalyticsDto(String siteId, String trackerBase)`
  - `record AppInfoDto(String version, String buildDate, String jmztabmVersion, int retentionDays, long maxUploadBytes, boolean customMappingsEnabled, @Nullable AnalyticsDto analytics)`: `analytics` is `null` when `ga.id` is empty.
  - `GET /rest/v2/app-info` returns `200 AppInfoDto`.

- [ ] **Step 1: Write failing tests.**

```java
@Test void listsExamplesWithSections()           // contains id "lipidomics-example.mzTab" section "example" and id "mzTab_2_0-M_mapping.xml" section "mapping"
@Test void validatesExampleById()               // @MockitoBean ExampleFetcher returns lipidomics-example.mzTab bytes -> 202; verify fetch called with the configured URI
@Test void mappingExampleCannotBeValidated()    // 400
@Test void unknownExampleIs404()
@Test void appInfoWithoutAnalytics()            // ga.id empty -> $.analytics == null; $.retentionDays == 7; $.maxUploadBytes == 67108864
@Test void appInfoWithAnalytics()               // ga.id=42 -> $.analytics.siteId == "42", $.analytics.trackerBase == "https://lifs-tools.org/matomo/"
```

- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(api): examples (validate by id) and app info"`

---

### Task 8: OpenAPI file for the client

**Files:**
- Create: `server/src/test/java/org/lifstools/mztab2/server/api/OpenApiSpecExportTest.java`
- Create: `frontend/openapi/mztab-m-api.json` (generated by the test, committed)

**Interfaces:**
- Produces: `frontend/openapi/mztab-m-api.json`, the input for the TypeScript client (Task 9). The test fails when the committed file differs from the live spec. With `-Dopenapi.update=true` it rewrites the file instead.

- [ ] **Step 1: Write the test.** It GETs `/v3/api-docs/mztab-m-api`, pretty-prints the JSON with sorted keys, and compares it with the committed file. If they differ: rewrite the file when `openapi.update` is set, otherwise fail with `OpenAPI spec is stale: run ./mvnw -pl server -am test -Dfrontend.skip=true -Dtest=OpenApiSpecExportTest -Dopenapi.update=true`.
- [ ] **Step 2: Generate the file.** Run the test with `-Dopenapi.update=true`, then without it. Expected: PASS.
- [ ] **Step 3: Commit.** `git commit -m "build: committed OpenAPI spec with staleness check"`

---

### Task 9: Angular workspace, frontend module, generated client

**Files:**
- Create: `frontend/` Angular 21 workspace (`npx @angular/cli@21 new frontend --create-application=false --package-manager=npm`), then `ng g application webapp --routing --style=scss --ssr=false`
- Create: `frontend/ng-openapi-gen.json` (`input: openapi/mztab-m-api.json`, `output: projects/webapp/src/app/api`), with `projects/webapp/src/app/api/` added to `frontend/.gitignore`
- Modify: `frontend/package.json` scripts: `generate:api` (ng-openapi-gen), `prebuild` and `pretest` (run `generate:api`), `build` (`ng build webapp --configuration production`), `test` (`ng test webapp`)
- Modify: `frontend/pom.xml`: frontend-maven-plugin 1.15.1 (`install-node-and-npm` v24.22.1, `npm ci`, `npm run build`, `npm run test -- --run`, all with `<skip>${frontend.skip}</skip>`); maven-resources copies `dist/webapp/browser/**` into `target/classes/static/`
- Modify: `server/pom.xml` (depend on `org.lifs-tools:mztab-validator-frontend:${project.version}`)
- Test: `frontend/projects/webapp/src/app/app.spec.ts`

**Interfaces:**
- Produces:
  - the generated services under `projects/webapp/src/app/api/`: `ValidationsService`, `ValidationProfilesService`, `ExamplesService` and `AppInfoService` (from the `@Tag` names in Tasks 3, 6 and 7). Later tasks import from `app/api`;
  - `provideApiConfiguration({ rootUrl: '' })` in `app.config.ts`, so calls are relative to `<base href>`;
  - a `proxy.conf.json` for `ng serve`: `/rest` → `http://localhost:8083/mztabvalidator`.

- [ ] **Step 1: Scaffold and add the dependencies:** `@angular/material@21`, `@angular/cdk@21`, the four `@fontsource` packages, `material-symbols`, and `ng-openapi-gen` (dev).
- [ ] **Step 2: Write the smoke test** `it('renders the router outlet')`: `App` contains a `router-outlet`, and `generate:api` produced `app/api/services/validations.service.ts`.
- [ ] **Step 3: Run it.** `npm --prefix frontend run test -- --run`. Expected: FAIL while `ng-openapi-gen.json` or the scripts are missing; PASS once they are in place.
- [ ] **Step 4: Verify the Maven wiring.** Run `./mvnw -B verify`. Expected: the server jar contains `BOOT-INF/lib/mztab-validator-frontend-2.0.0-SNAPSHOT.jar`, and that jar contains `static/index.html`.
- [ ] **Step 5: Commit.** `git commit -m "build(frontend): Angular 21 workspace, generated API client, Maven integration"`

---

### Task 10: LIFS theme and app shell

**Files:**
- Create: `frontend/projects/webapp/src/styles/_lifs-tokens.scss` (every `--lifs-*` token from spec §5.6, verbatim), `_material-theme.scss` (M3 theme, primary from `#16628F`, shape 0, brand font Space Grotesk, plain font IBM Plex Sans), `styles.scss`
- Create: `frontend/projects/webapp/src/assets/brand/` (copy the logos from `docs/superpowers/specs/2026-10-23-validator-spa/`)
- Create: `app/shell/app-bar.component.ts|html|scss`, `app/shell/site-footer.component.ts|html|scss`, `app/core/app-info.store.ts`, `app/core/matomo.service.ts`
- Modify: `app/app.ts` (layout: app bar, `<main><router-outlet>`, footer), `app.routes.ts` (empty route stubs for `''`, `result/:id`, `about`, `examples`, `**`)
- Test: `app-bar.component.spec.ts`, `site-footer.component.spec.ts`, `matomo.service.spec.ts`

**Interfaces:**
- Produces:
  - `AppInfoStore` (`providedIn: 'root'`): `info: Signal<AppInfoDto | undefined>`, loaded once from `GET rest/v2/app-info`.
  - `MatomoService`:
    - `init(analytics: AnalyticsDto | null)`: does nothing when `null`; otherwise injects `<trackerBase>matomo.js` with `requireConsent`, `setSiteId`, `trackPageView` on every `NavigationEnd`;
    - `setConsent(given: boolean)`;
    - `consentGiven(): boolean`.
  - App bar:
    - `#3D84A8` bar, logo `assets/brand/lifs-bildmarke.png` (max 50px), the name "mzTab-M Validator" with "LIFS Tools";
    - nav items `Validate` (`/`), `Examples` (`/examples`), `REST API` (`swagger-ui/index.html`, new tab), `About` (`/about`), `LIFS Tools ↗` (`https://lifs-tools.org/`), and a GitHub link (`https://github.com/lifs-tools/jmzTab-m-webapp`);
    - a menu button below 900px.
  - Footer:
    - partner logos with alt texts `Uni Wien`, `MPI-CBG`, `FZ Borstel`, `Forschungszentrum Jülich`;
    - "With funding from the:" with `BMFTR` and `BMBWF`;
    - links `Imprint & Privacy Policy` (`https://lifs-tools.org/imprint-privacy-policy.html`), `Impressum & Datenschutzerklärung` (`https://lifs-tools.org/impressum-datenschutz.html`), `Terms of Service` (`https://lifs-tools.org/terms-of-service.html`), and `Analytics` (a consent dialog, shown only when analytics is configured);
    - the `#414856` band with the signet, `© 2016 - {current year} LIFS Tools Consortium` and `Top`.

- [ ] **Step 1: Write failing tests.**

```ts
it('shows the five nav items in order')                // texts: Validate, Examples, REST API, About, LIFS Tools ↗
it('footer names FZ Jülich and never ISAS')            // img alt "Forschungszentrum Jülich" present; textContent does not contain "ISAS"
it('copyright shows the current year')                 // contains `© 2016 - ${new Date().getFullYear()} LIFS Tools Consortium`
it('hides the Analytics link without analytics')       // AppInfoStore analytics null -> no "Analytics" link
it('does not load matomo without analytics')           // init(null) -> no <script src*="matomo.js">
it('loads matomo with consent required')               // init({siteId:"42", trackerBase:"https://x/"}) -> script src "https://x/matomo.js"; _paq contains ["requireConsent"] and ["setSiteId","42"]
```

- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** Map the `--lifs-*` tokens onto `--mat-sys-*` in `_material-theme.scss`. Load the fonts with `@import '@fontsource/…/400.css'` (and the needed weights) in `styles.scss`.
- [ ] **Step 4: Run them.** Expected: PASS. Then compare `ng serve` by eye against section 2 of `mockup.html` (app bar and footer).
- [ ] **Step 5: Commit.** `git commit -m "feat(spa): LIFS theme, app bar, footer, Matomo with consent"`

---

### Task 11: Landing page and validator form

**Files:**
- Create: `app/pages/landing/landing.page.ts|html|scss`, `app/pages/landing/validator-form.component.ts|html|scss`, `app/pages/landing/examples-card.component.ts|html`
- Test: `validator-form.component.spec.ts`, `landing.page.spec.ts`

**Interfaces:**
- Consumes: the generated API (Task 9), `AppInfoStore`.
- Produces:
  - `ValidatorFormComponent` with the form fields `file: File | null`, `level: 'INFO'|'WARN'|'ERROR'` (default `INFO`), `maxErrors: number` (1–500, default 100), `validationProfile: string | null` (default `mztab-m-2.0-default`; `null` shows as "None — structural validation only"), `mappingFile: File | null` (hidden unless `customMappingsEnabled`; choosing one clears and disables the profile select).
  - On submit it calls `POST rest/v2/validations`, then navigates to `/result/{id}`.
  - Errors show under the button: 413 `The file is larger than {maxUploadBytes/1 MB} MB.`; 400 and 503 the server's `message`.
  - The hero copy is fixed: eyebrow `LIFS Tools · Report & share`; title `Validate your mzTab-M files`; lead `Structural and semantic validation with jmzTab-m, the mzTab-M reference implementation.`; note `Results are kept for {retentionDays} days, then deleted.`

- [ ] **Step 1: Write failing tests.**

```ts
it('defaults to INFO, 100 and the default validation profile')
it('rejects maxErrors outside 1..500')                 // submit disabled
it('choosing a mapping file disables the profile select and sends mappingFile only')
it('hides the mapping upload when custom mappings are disabled')
it('navigates to /result/:id after 202')
it('shows the size message on 413')                     // "The file is larger than 64 MB."
it('shows retention days from app info in the hero')
it('lists examples with download links; mapping examples have no Validate action')
it('Validate on an example posts to examples/{id}/validations and navigates')
```

- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement** with outlined Material form fields (square through the theme). The drop zone uses native `dragover`/`drop` handlers plus a hidden `<input type=file>`.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(spa): landing page, validator form, examples card"`

---

### Task 12: Result page — status, banner, messages, report, delete

**Files:**
- Create: `app/pages/result/result.page.ts|html|scss`, `result-header.component.*`, `result-banner.component.*`, `messages-tab.component.*`
- Test: `result.page.spec.ts`, `messages-tab.component.spec.ts`

**Interfaces:**
- Consumes: `GET`/`DELETE rest/v2/validations/{id}`, `…/report`.
- Produces:
  - **Polling:** while the status is `QUEUED` or `RUNNING`, poll every 2 s and show an indeterminate progress bar with the file name. Stop on `FINISHED`, on `FAILED` and on destroy.
  - **Header:**
    - eyebrow `Result · kept until {expiresAt, 'd MMM y'}`, then the file name;
    - badges: `mzTab-M {version}`, the profile (`{id} · v{version}`, `custom mapping`, or `structural only`), `level {level} · max {maxErrors}`;
    - buttons `Download report` (a menu with CSV and JSON) and `Delete now` (a confirm dialog, then `DELETE`, then navigate to `/`).
  - **Banner:**

    | Outcome | Title | Status colour |
    |---|---|---|
    | `PASSED` | `Validation passed` | ok |
    | `PASSED_WITH_NOTES` | `Validation passed with notes` | warn |
    | `FAILED` | `Validation failed` | error |
    | `NOT_PARSED` | `The file could not be read` | error |

    The banner also shows level chips with the counts. `failureReason`, when set, appears under the title.
  - **Tabs:** `Messages ({n})`, `Metadata`, `Summary`, `Features`, `Evidence`. A table tab is disabled with `—` when its `TableSize` is `null`. With outcome `NOT_PARSED`, only Messages shows.
  - **Messages tab:** client-side text, level and category filters; columns Level, Line (`—` for −1), Category, Message; 25 per page.
  - **Not-found state** (404): `This result does not exist or has expired. Results are kept for {retentionDays} days.`, with a link to `/`.
  - The query parameter `confirmDelete=true` opens the delete dialog on load.

- [ ] **Step 1: Write failing tests** (HttpTestingController and fake timers).

```ts
it('polls until FINISHED and then stops')
it('maps each outcome to its banner title')               // the four rows above
it('disables absent table tabs')                          // sections.smf null -> "Features" tab disabled
it('shows only Messages for NOT_PARSED')
it('filters messages by level and text')
it('delete asks for confirmation, then navigates home')
it('confirmDelete=true opens the dialog')
it('404 shows the expiry explanation')
```

- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expected: PASS. Compare by eye against the results frame of `mockup.html`.
- [ ] **Step 5: Commit.** `git commit -m "feat(spa): result page with polling, banner, messages, report and delete"`

---

### Task 13: Read-only section viewer

**Files:**
- Create: `app/pages/result/metadata-tab.component.*`, `app/pages/result/table-tab.component.*`, `app/pages/result/column-picker.component.*`
- Test: `metadata-tab.component.spec.ts`, `table-tab.component.spec.ts`

**Interfaces:**
- Consumes: `…/metadata`, `…/metadata/{group}`, `…/tables/{section}` (Task 6).
- Produces:
  - **Metadata tab:** an expansion panel per `MetadataGroup` (`{name} ({count})`), loaded lazily when opened, 50 entries per page, key in mono, value in sans.
  - **Table tab:**
    - a Material table fed by `TablePage`, with server-side `page` and `size` (25/50/100) and sort (header click);
    - one text filter per visible column (debounced 300 ms; sent as repeated `filter=COL:text`);
    - a column picker: a searchable checklist over `allColumns`, at most 50 selected, the first column always included;
    - a column window navigator, "Columns 1–50 of 1,990 ‹ ›", which shifts the window by 50.
    - The table never requests more than the selected columns.

- [ ] **Step 1: Write failing tests.**

```ts
it('loads a metadata group only when opened')
it('requestsOnlyTheColumnWindow')                     // initial request has no columns param; "›" requests columns = allColumns[50..99]
it('sort click requests sort and descending')
it('column filter is debounced and sent as filter=COL:text')
it('picker caps the selection at 50 and keeps the first column')
```

- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(spa): read-only metadata and table viewer with server paging"`

---

### Task 14: About, Examples and error pages

**Files:**
- Create: `app/pages/about/about.page.*`, `app/pages/examples/examples.page.*`, `app/pages/not-found/not-found.page.*`, `app/core/http-error.interceptor.ts`
- Test: `about.page.spec.ts`, `not-found.page.spec.ts`

**Interfaces:**
- Produces:
  - **About:** carries over the current `about.html` content, updated:
    - mzTab-M and jmzTab-m with links;
    - validation levels;
    - validation profiles and custom mapping files, plus the note on EBI OLS lookups;
    - retention ({retentionDays} days) and privacy (Matomo with consent);
    - how to cite;
    - the notice `mzTab 1.0 validation was retired in version 2.0.0.`;
    - version info from `AppInfoDto`.
    - No ISAS.
  - **Examples:** the full list from `rest/v2/examples`, grouped `Example files` and `Example mapping files`; reuses the card's actions from Task 11.
  - **Not found:** in the LIFS error-page style: eyebrow `Error 404 · Page not found`, title `We couldn't find that page`, tips, and buttons `Validate a file` (`/`) and `Browse LIFS tools` (`https://lifs-tools.org/`).
  - The interceptor turns network errors and 5xx (other than 503) into a snackbar `Something went wrong. Please try again.`

- [ ] **Step 1: Write failing tests.**

```ts
it('about shows the retirement notice and the app version')
it('about never mentions ISAS')
it('unknown route renders the not-found page')
```

- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(spa): about, examples and not-found pages"`

---

### Task 15: Cutover — serve the SPA, delete Thymeleaf, end-to-end tests

**Files:**
- Create: `server/src/main/java/org/lifstools/mztab/validator/webapp/spa/SpaController.java`
- Delete:
  - `ValidationController`, `ValidationForm`, `ValidationResult`, `Page`, `ToolResultService`, `DefaultToolResultService`, `ToolResult`, `ValidationService.runValidation`/`getStatus`/`parse`, and `MzTabMValidator.parse`;
  - `server/src/main/resources/templates/`, `static/css/`, `static/js/`, `static/img/` (except `favicon.png`), `i18n/`;
  - the `LocaleResolver` and `LocaleChangeInterceptor` beans and the webjars handler in `WebConfig`.
- Modify: `server/pom.xml` (remove `spring-boot-starter-thymeleaf`, `thymeleaf-layout-dialect`, `org.webjars:jquery`, `org.webjars:bootstrap`, `webjars-locator-*`, and `ognl` unless `mvn dependency:analyze` shows another user), `MappingUploadIntegrationTest` (rewrite against `POST /rest/v2/validations`), `DefaultProfileAccessIntegrationTest` (keep its intent, anonymous access without a profile: `GET /` and `GET /about` → 200 HTML; `POST /rest/v2/validations` → 202; `POST /rest/v2/validatePlain` → 200 or 422)
- Create: `frontend/e2e/validator.spec.ts`, `frontend/playwright.config.ts`, `frontend/e2e/fixtures/lipidomics-example.mzTab`; modify `.github/workflows/maven.yml` (an e2e job: package, start the jar with `--server.servlet.contextPath=/mztabvalidator`, run `npx playwright test`)
- Test: `server/src/test/java/org/lifstools/mztab/validator/webapp/spa/SpaControllerTest.java`

**Interfaces:**
- Produces:
  - `SpaController`:
    - serves `static/index.html` for every GET outside `/rest/**`, `/v3/**`, `/swagger-ui/**`, `/actuator/**` and paths with a file extension;
    - replaces `<base href="/">` with `<base href="{request.getContextPath()}/">`, read once and cached per context path;
    - sends `Cache-Control: no-cache`.
  - Redirects: `GET /validate/{id}` → 302 `/result/{id}`; `GET /validate/{id}/delete` → 302 `/result/{id}?confirmDelete=true`.

- [ ] **Step 1: Write failing tests.**

```java
@Test void rootServesIndexWithContextBase()         // contextPath /mztabvalidator -> body contains <base href="/mztabvalidator/">
@Test void deepRouteServesIndex()                   // GET /result/<uuid> -> 200 text/html with app-root
@Test void oldValidateUrlRedirects()                // GET /validate/<uuid> -> 302 Location ends with /result/<uuid>
@Test void oldDeleteUrlNeverDeletes()               // GET /validate/<uuid>/delete -> 302 ...?confirmDelete=true; the record still exists
@Test void restUnknownPathIsJson404()               // GET /rest/v2/nope -> 404 application/json
@Test void assetsAreNotForwarded()                  // GET /missing.js -> 404, not index.html
```

```ts
// frontend/e2e/validator.spec.ts (Playwright, baseURL http://localhost:8083/mztabvalidator/)
test('upload, see result tabs, delete')            // structural only; banner visible; Summary tab shows "SML_ID"; delete returns to landing
test('custom mapping with DOCTYPE is rejected')    // error text visible under the button
test('old /validate/{id} link lands on the result')
test('unknown route shows the not-found page')
test('footer shows Forschungszentrum Jülich')
test('landing and result pages have no serious accessibility violations')  // @axe-core/playwright: no "serious" or "critical" violations, wcag2a + wcag2aa tags
```

- [ ] **Step 2: Run the server tests.** Expected: FAIL.
- [ ] **Step 3: Implement the controller and the deletions.** Then run `grep -rniE "thymeleaf|webjars|jquery|bootstrap|th:" server/src` and expect no matches outside comments.
- [ ] **Step 4: Run the full build and the end-to-end tests.** `./mvnw -B verify`, then start the jar and run `npm --prefix frontend exec playwright test`. Expected: all PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat!: serve the SPA, delete the Thymeleaf UI, add end-to-end tests"`

---

### Task 16: Documentation, large-file check, release preparation

**Files:**
- Modify: `README.adoc`: the build, `ng serve` with the proxy, the self-hosting requirements (outbound EBI OLS access, a volume for `storage.location`, `minCleanupAge`, `storage.max-total-bytes`, `validation.custom-mappings.enabled`, `ga.id`, `matomo.tracker-base`), and the profile catalog (id, version, use case)
- Create: `docs/release-notes/2.0.0.md` (the new UI, mzTab 1.0 retired, REST contracts unchanged, new endpoints)
- Create: `docs/superpowers/plans/2026-10-23-validator-spa-m1-parity.md` (the parity checklist from spec §2 goal 1, one checkbox each)
- PRs prepared, not merged:
  - `docker-recipes-external`: `docker-compose-mztabvalidator-dev.yml` image → `2.0.0-SNAPSHOT`; `docker-compose-mztabvalidator.yml` image → `2.0.0` and `replicas: 1` (a separate PR, merged at release);
  - `lipidcompass-ansible`: a named volume for `mztab-validator` and `storage.location` set to it.

- [ ] **Step 1: Large-file check.**
  - Submit `../lipidcompass-submissions/submissions/LCS-00050/data/LCS-00050-01.mzTab` to a local jar with no profile.
  - Record the time to `FINISHED` and the size of `SECTIONS/sml.jsonl`.
  - In Chrome, page through the Summary tab and shift the column window five times.
  - Expected: no frame drop that freezes the tab, and each page responds in under 1 s.
  - Write the numbers into the parity checklist.
- [ ] **Step 2: Write the docs and release notes.** The project lead reviews them.
- [ ] **Step 3: Publish the snapshot image.** Trigger the `workflow_dispatch` publish on `feat/validator-spa`, merge the `-dev` compose PR, and work through the parity checklist on `/mztabvalidator-dev`.
- [ ] **Step 4: Commit.** `git commit -m "docs: README, release notes and parity checklist for 2.0.0"`
- [ ] **Release** (after the checklist is complete; the project lead decides):
  1. merge `feat/validator-spa` into `master`;
  2. release 2.0.0 with the existing `release` profile;
  3. merge the production compose PR (`2.0.0`, `replicas: 1`) and the lipidcompass-ansible volume PR.
