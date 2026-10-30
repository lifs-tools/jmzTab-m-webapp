# mzTab-M Version Engines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse, validate, convert and render every mzTab-M file with the jmzTab-m line of the version it declares, in one stateless engine service per version, and remove jmzTab-m from the webapp.

**Architecture:**
- New modules: `engine-api` (contract DTOs and the version detector, no jmzTab-m), engine sources in `engine-core-src/` (one copy, compiled by both engines), `engine-mztabm-2.0` (jmzTab-m 1.0.x) and `engine-mztabm-2.1` (jmzTab-m 2.1.x).
- The webapp (`server`) detects the declared version, routes to the configured engine over HTTP (multipart in, NDJSON out), and stores results and sections as today.
- The SPA filters profiles and examples by version.

**Tech Stack:**
- Java 24 bytecode on JDK 25, Spring Boot 3.5.16, springdoc 2.10.0, Jackson 2.21.7;
- jmzTab-m 1.0.8-SNAPSHOT and 2.1.0-SNAPSHOT;
- `build-helper-maven-plugin` 3.6.1, `okhttp3:mockwebserver` 4.12.0 (webapp tests), ArchUnit 1.4.1;
- Angular 21 with Angular Material, Playwright; Jib 3.5.2.

**Spec:** `docs/superpowers/specs/2026-10-30-mztabm-version-engines-design.md` (approved 2026-10-30). Builds on `docs/superpowers/specs/2026-10-23-validator-spa-design.md`. Read both.

**Repository and branch:**
- jmzTab-m-webapp; work branches off `feat/validator-spa`, PRs into `feat/validator-spa` (SPA design §5.7).
- Build in a git worktree, never in the VS Code checkout: concurrent JDT compiles corrupt `target/classes`.

## Global Constraints

- One mzTab-M version, one jmzTab-m line: a `2.0.x-M` file never reaches jmzTab-m 2.1.x and the reverse (spec anti-goal 1).
- `server` must not depend on `org.lifs-tools:jmztabm-*` in any scope, nor on `uk.ac.ebi.pride.utilities:ols-client`.
- The ArchUnit ban covers `uk.ac.ebi.pride.jmztab2..`, `info.psidev.cvmapping..`, `uk.ac.ebi.pride.utilities.ols..` and `org.lifstools.mztab2..` except `org.lifstools.mztab2.server..`.
- Engine logic exists exactly once, in `engine-core-src/`; engine modules hold only their pom, version-specific classes and fixtures.
- Release builds depend only on released artifacts (`requireReleaseDeps`). Development builds may use SNAPSHOTs. Release builds exclude `engine-mztabm-2.1` (spec E11).
- Engines listen on 8084 (2.0) and 8085 (2.1), API under `/engine/v1`, no authentication, internal network only.
- The webapp's REST and storage behaviour is unchanged except where a task says otherwise. `ValidationMessage` JSON is byte-compatible with jmzTab-m's:
  - fields: `code`, `category`, `message_type`, `message`, `line_number`, `suggestion`, `rule_id`, `location`;
  - `location` fields: `section`, `row_index`, `column`, `metadata_key`, `metadata_group`, `element_path`;
  - `category`: `format`, `logical`, `cross_check`;
  - `message_type`: `error`, `warn`, `info`;
  - `section`: `mtd`, `smh`, `sml`, `sfh`, `smf`, `seh`, `sme`, `com`, `file`.
- Every request parameter (webapp and engine) is validated with Jakarta Bean Validation and constrained. Over-limit values are rejected (400), never clamped.
  - Path ids and versions in the profile endpoints use allow-list patterns: id `^[a-z0-9][a-z0-9.-]{0,63}$`, version `^[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}$`.
- Version lines are `MAJOR.MINOR` strings derived from a declared version by `^\s*(\d+)\.(\d+)(\.|$)`: `2.0.0-M` → `2.0`, `2.1.0-M` → `2.1`. A version starting `1.` is legacy and rejected as today.
- Default line when a file declares no version: `engines.default-line=2.0`. The webapp refuses to start if no engine is configured for it.
- A file without `mzTab-version` is not validated "as if" it declared the default: Error-2008 stays (spec §5.4).
- Busy engine: HTTP 503 with `Retry-After`. Error bodies: `{"code": "...", "message": "..."}` with codes `unsupported_version`, `busy`, `invalid_request`, `file_too_large`, `not_json`, `internal`.
- Upload limit 64 MB (`spring.servlet.multipart.max-file-size`) in the webapp and in engines; mapping files 5 MiB (`ValidationSessionService.MAX_MAPPING_BYTES`).
- The webapp never holds a whole upload or a whole section in memory to pass it on: spool to temporary files.
- Every new user-facing text in English; the SPA follows the design system of SPA design §5.6.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never push or deploy without the project lead's go-ahead.

## Review Focus

1. **A JSON upload whose `metadata` comes after the tables, or a 60 MB JSON file.** Routing must still find `mzTab-version`, reading the stream once with bounded memory, and not time out. Tests: Task 1 `detectsVersionInJsonWhenMetadataComesLast`, `detectsVersionInLargeJsonWithBoundedMemory`.
2. **An engine that dies mid-response.** The NDJSON stream ends without its `end` event, so the validation must end FAILED with the unavailable message, no partial section files are left behind, and it is never stuck RUNNING. Test: Task 5 `truncatedEngineStreamFailsValidationAndRemovesSections`.
3. **Declared-version spellings found in real files.** `2.0.0-M ` with a trailing tab or space, `2.1.0-m`, `2.1`, `2.0.1-M`, a UTF-8 BOM before `MTD`, CRLF line ends. Each must route to the expected line, and `2.2.0-M` must get the unsupported message. Test: Task 1 `lineOfAcceptsRealWorldSpellings`.
4. **A hidden 2.1 example requested by id on an instance without a 2.1 engine** (`POST /rest/v2/examples/{id}/validations`). It must be rejected without downloading anything. Test: Task 7 `exampleOfUnavailableLineIsRejectedWithoutFetching`.
5. **`/validate?semanticValidation=true` with a 2.1 file.** It must use `mztab-m-2.1-default`, not the 2.0 rules, and on an instance without a 2.1 engine it must answer 422 with the unsupported message. Test: Task 6 `semanticValidationUsesTheDefaultProfileOfTheFilesLine`.

---

## File map

```
pom.xml                                   # modules, jmztabm20/21 properties, snapshot-engines + release profiles
engine-api/pom.xml                        # artifactId mztab-validator-engine-api; jackson-annotations, jakarta.validation, swagger-annotations only
engine-api/src/main/java/org/lifstools/mztab/validator/engine/api/
  ValidationMessage.java, ValidationLocation.java   # DTOs, JSON identical to jmzTab-m
  ValidationLevel.java                    # moved from server webapp.domain (INFO, WARN, ERROR)
  EngineInfo.java, EngineError.java, EngineErrorCode.java
  ValidationEvent.java                    # sealed NDJSON event types
  SectionSizes.java                       # = SectionsSummary shape
  MzTabVersions.java                      # lineOf, isLegacy
  MzTabVersionDetector.java               # TSV + streaming JSON detection (moved from server)
engine-api/src/main/resources/engine-openapi.json   # committed engine contract
engine-core-src/src/main/java/org/lifstools/mztab/validator/engine/   # NOT a module; compiled by both engines
  EngineApplication.java, EngineController.java, EngineErrorAdvice.java
  EngineValidator.java (from MzTabMValidator), EngineConverter.java (from MzTabConverter)
  SectionRenderer.java (from SectionWriter.render), ValidationStreamWriter.java
  SafeCvMappingLoader.java (moved), JobLimiter.java, EngineConfig.java
  cvcompletion/OlsMappingCvSuggestionService.java, cvcompletion/StreamUtils.java (moved, unused until milestone 2)
engine-core-src/src/main/resources/application.properties
engine-core-src/src/test/java/org/lifstools/mztab/validator/engine/   # shared test suite
engine-mztabm-2.0/pom.xml, engine-mztabm-2.0/src/test/...           # 2.0-only expectations
engine-mztabm-2.1/pom.xml, engine-mztabm-2.1/src/test/...           # 2.1-only expectations
server/src/main/java/org/lifstools/mztab/validator/webapp/engine/
  EnginesProperties.java, EngineRegistry.java, EngineClient.java, EngineResultReader.java
  EngineUnavailableException.java, EngineBusyException.java, EngineRejection.java, EngineRequestException.java
  TruncatedEngineStreamException.java, EngineHealthContributor.java, RouteDecision.java
server/src/main/resources/schema/CvMapping.xsd                       # copy, for the upload check
server/src/main/resources/mztab-schemas/2.0.yml, 2.1.yml             # copies of jmztabm-api's mzTab_m_openapi.yml
server/src/main/resources/validation-profiles/                       # + mztab-m-2.0-default/1.1.0.xml, mztab-m-2.1-default/1.0.0.xml
frontend/projects/webapp/src/app/core/mztab-version.ts               # browser-side detection
compose/dev-stack.yml                                                # local webapp + both engines
```

---

### Task 1: `engine-api` module with DTOs and version detection

**Files:**
- Create: `engine-api/pom.xml`, the `engine-api/src/main/java/.../engine/api/*` files of the file map, and `engine-api/src/test/java/.../engine/api/MzTabVersionDetectorTest.java`, `MzTabVersionsTest.java`, `ValidationMessageJsonTest.java`.
- Create the fixtures in `engine-api/src/test/resources/version-detection/`:
  - `tsv-first.mztab`, `tsv-bom-crlf.mztab`, `tsv-missing.mztab`, `tsv-legacy.mztab`;
  - `json-metadata-first.json`, `json-metadata-last.json`, `json-missing.json`.
- Modify: `pom.xml` (add module `engine-api` before `server`); `server/pom.xml` (depend on `mztab-validator-engine-api`).
- Delete: `server/src/main/java/.../service/validation/MzTabVersionDetector.java`. Update its two callers (`ValidationRunner`) to the moved class.
- Move: `server/.../webapp/domain/ValidationLevel.java` → `engine-api/.../engine/api/ValidationLevel.java` (unchanged constants `INFO, WARN, ERROR`); rewrite the 17 imports in `server/src` with `perl -pi` (BSD sed has no `\b`) and check that `git diff --stat` lists 17 server files plus the move.

**Interfaces:**
- Produces:
  - `MzTabVersions.lineOf(String declared) -> Optional<String>`: `2.0`, `2.1`, …; empty if not parseable.
  - `MzTabVersions.isLegacy(String declared) -> boolean`.
  - `MzTabVersionDetector.detect(Path file) -> Optional<String>`: TSV or JSON, chosen by the first non-whitespace, non-BOM character `{`.
  - `MzTabVersionDetector.detectTsv(Path)`.
  - `MzTabVersionDetector.detectJson(Path)`: Jackson `JsonParser`; the `mzTab-version` field of the top-level `metadata` object; skips other subtrees with `skipChildren()`.
  - `MzTabVersionDetector.isJson(Path)` (moved from `ValidationRunner.isJson`).
  - `ValidationMessage` / `ValidationLocation`: Lombok `@Data` beans with `@JsonProperty` names and enums `Category`, `MessageType`, `Section` (`@JsonValue` lower-case values of Global Constraints). `ValidationMessage.error(String text)`, `.info(String text)`: category `format`, code `""`, line `-1`.
  - `EngineInfo(String line, List<String> mzTabVersions, String jmztabmVersion, String build, long maxUploadBytes, int maxConcurrentJobs)`.
  - `EngineError(EngineErrorCode code, String message)`; `EngineErrorCode` serialized lower-case.
  - `ValidationEvent` sealed interface, Jackson `@JsonTypeInfo(use = NAME, property = "type")`:
    - `Outcome(boolean parsed, String mzTabVersion, List<ValidationMessage> messages)` → `"outcome"`;
    - `MetadataLine(int line, String key, String value)` → `"metadata"`;
    - `TableHeader(String section, List<String> header)` → `"table"`;
    - `TableRowCells(String section, List<String> cells)` → `"row"`;
    - `End(SectionSizes sections)` → `"end"`.
  - `SectionSizes(int metadataLines, TableDims sml, TableDims smf, TableDims sme)`; `TableDims(int rows, int columns)`.

- [ ] **Step 1: Write the failing tests**

```java
// MzTabVersionsTest
@ParameterizedTest @CsvSource({"2.0.0-M,2.0", "'2.0.0-M ',2.0", "2.1.0-m,2.1", "2.1,2.1", "2.0.1-M,2.0", "2.2.0-M,2.2"})
void lineOfAcceptsRealWorldSpellings(String declared, String line) { assertEquals(Optional.of(line), MzTabVersions.lineOf(declared)); }
@Test void lineOfRejectsGarbage() { assertEquals(Optional.empty(), MzTabVersions.lineOf("M-2")); }
@Test void legacyIsOneDotAnything() { assertTrue(MzTabVersions.isLegacy("1.0.0")); assertFalse(MzTabVersions.isLegacy("2.0.0-M")); }

// MzTabVersionDetectorTest
@Test void detectsTsvVersion()                         // tsv-first → "2.0.0-M"
@Test void detectsTsvVersionAfterBomWithCrlf()          // tsv-bom-crlf → "2.1.0-M"
@Test void missingVersionIsEmpty()                      // tsv-missing, json-missing → Optional.empty()
@Test void detectsVersionInJsonWhenMetadataComesFirst() // → "2.1.0-M"
@Test void detectsVersionInJsonWhenMetadataComesLast()  // tables before "metadata" → "2.0.0-M"
@Test void detectsVersionInLargeJsonWithBoundedMemory() // a generated 60 MB JSON (@TempDir) with metadata last:
                                                        // → "2.1.0-M" within 10 s under -Xmx256m (surefire argLine for this test class)
@Test void nestedMzTabVersionKeyOutsideMetadataIsIgnored() // {"comment":[{"mzTab-version":"9.9"}],"metadata":{"mzTab-version":"2.0.0-M"}} → "2.0.0-M"

// ValidationMessageJsonTest
@Test void serializesLikeJmzTabM()  // a fully populated message → exactly the JSON of fixture validation-message.json
                                    // (copied from a jmzTab-m 1.0.8 ValidationMessage serialization)
@Test void roundTripsAllEnumValues()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -B -pl engine-api test`
Expected: compilation failure (classes missing).

- [ ] **Step 3: Implement the module.** `engine-api/pom.xml` depends only on `jackson-databind`, `jakarta.validation-api`, `io.swagger.core.v3:swagger-annotations-jakarta` and `lombok` (provided). `detectJson` stops at the first matching field; it never builds a tree for skipped subtrees.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -B -pl engine-api test && ./mvnw -B -pl server -am test -Dfrontend.skip=true`
Expected: PASS; the server tests are unchanged and green.

- [ ] **Step 5: Commit**

```bash
git add pom.xml engine-api server
git commit -m "Add engine-api: engine contract DTOs and mzTab-M version detection"
```

---

### Task 2: Engine sources and the 2.0 engine

**Files:**
- Create: `engine-core-src/src/main/java/.../engine/*` (file map) and `engine-core-src/src/main/resources/application.properties`.
- Create: `engine-mztabm-2.0/pom.xml`.
- Create the shared tests in `engine-core-src/src/test/java/.../engine/`: `EngineControllerTest.java`, `EngineValidatorTest.java`, `EngineConverterTest.java`, `SectionRendererTest.java`, `JobLimiterTest.java`, `EngineOpenApiContractTest.java`, `SafeCvMappingLoaderTest.java` (moved from server).
- Create: `engine-api/src/main/resources/engine-openapi.json` (generated by the contract test with `-Dopenapi.update=true`).
- Modify: `pom.xml`:
  - module `engine-mztabm-2.0`;
  - properties `jmztabm20.version=1.0.8-SNAPSHOT`, `jmztabm21.version=2.1.0-SNAPSHOT`;
  - `build-helper-maven-plugin` 3.6.1 in `pluginManagement`.
- Keep `server` untouched in this task (it still validates in-process).

**Interfaces:**
- Consumes: Task 1 DTOs, `MzTabVersionDetector`, `MzTabVersions`.
- Produces (HTTP, documented in `engine-openapi.json`):
  - `GET /engine/v1/info` → `EngineInfo`. `line` comes from property `engine.line` (set per module: `2.0` / `2.1`). `mzTabVersions` comes from `engine.mztab-versions` (`2.0.0-M` / `2.1.0-M`). `jmztabmVersion` comes from the filtered resource property `engine.jmztabm-version`.
  - `POST /engine/v1/validations`, `multipart/form-data`:
    - parts: `file` (required, ≤ 64 MB), `mapping` (optional, ≤ 5 MiB);
    - query: `level` ∈ `ERROR|WARN|INFO` (default `INFO`), `maxErrors` 0..500 (default 100);
    - success: `200 application/x-ndjson`. The events are, in this order:
      1. one `outcome`;
      2. if `parsed`: the `metadata` lines; then, per present table (SML, SMF, SME): one `table` event and its `row` events;
      3. one `end`.

      Row `cells` exclude the line prefix (`SML`, …), as `SectionWriter` stores them today;
    - errors: `400 invalid_request` (bad parameter), `400 unsupported_version` (declared line ≠ `engine.line`), `413 file_too_large`, `503 busy` + `Retry-After: 5`.
  - `POST /engine/v1/conversions/tsv-to-json`, body TSV → `200 application/json` (the mzTab-M model) or `422` with a `ValidationMessage[]` array.
  - `POST /engine/v1/conversions/json-to-tsv?validate=true|false`, body JSON → `200 text/tab-separated-values`, `422` with `ValidationMessage[]`, or `400 not_json`.
- Java (shared sources):
  - `EngineValidator.validate(Path file, ValidationLevel level, int maxErrors, @Nullable Path mapping) -> Outcome(List<ValidationMessage> messages, @Nullable MzTab mzTab)`. The body is today's `MzTabMValidator.validateAndParse`, including the capped semantic messages and the "skipped" note. Messages are converted with `ObjectMapper.convertValue(jmzTabMessage, engine.api.ValidationMessage.class)`.
  - `SectionRenderer.render(MzTab) -> Rendered` (today's `SectionWriter.render`). It no longer stamps `MZTabConstants.VERSION_MZTAB_M` into a file without a version.
  - `ValidationStreamWriter.write(OutputStream, Outcome, @Nullable Rendered)`: one JSON object per line, flushed per table.
  - JSON uploads are converted to TSV inside the engine, as `ValidationRunner` does today (`JSON_NOT_READ` / `JSON_NOT_CONVERTED` messages move with it).
  - `JobLimiter(int maxConcurrent)` (`engine.max-concurrent-jobs`, default = available processors) with `tryAcquire() -> Optional<Permit>`. There is no waiting: busy → 503.

- [ ] **Step 1: Write the failing tests** (shared suite; fixtures from the engine's own `jmztabm-test-utils`)

```java
// EngineControllerTest (@SpringBootTest(webEnvironment = MOCK) + MockMvc)
@Test void infoReportsLineAndVersions()            // GET /engine/v1/info → line == System.getProperty("engine.line")
@Test void validatesTsvAndStreamsOutcomeSectionsEnd() // lipidomics-example.mzTab → first event outcome(parsed=true),
                                                   // ≥1 metadata, table SML header first cell "SML_ID", last event end with sml.rows == row events
@Test void validatesJsonUpload()                   // lipidomics-example.mzTab.json → outcome parsed=true
@Test void unparseableFileStreamsOutcomeAndEndOnly() // a file of "garbage" → outcome(parsed=false, messages non-empty), end(sections=null)
@Test void semanticValidationRunsOnlyWithMapping() // with the 2.0 catalog mapping → a message with rule_id != null; without → none
@ParameterizedTest @ValueSource(strings = {"level=DEBUG", "maxErrors=-1", "maxErrors=501"})
void rejectsInvalidParameters(String query)        // → 400, body code "invalid_request"
@Test void rejectsFileOfOtherLine()                // a file declaring the other line → 400 unsupported_version
@Test void acceptsFileWithoutVersion()             // tsv-missing → 200, outcome contains Error-2008 (code contains "2008")
@Test void busyWhenAllSlotsTaken()                 // engine.max-concurrent-jobs=1, slot held → 503, Retry-After "5", code busy
@Test void oversizedUploadIs413()                  // spring.servlet.multipart.max-file-size=1KB in the test → 413 file_too_large
@Test void convertsTsvToJsonAndBack()              // tsv-to-json 200; json-to-tsv of that JSON 200 and starts with "MTD\tmzTab-version"
@Test void jsonToTsvRejectsNonJson()               // → 400 not_json
@Test void mappingWithDoctypeIsRejected()          // MappingFixtures.MAPPING_WITH_DOCTYPE → outcome message "Mapping file rejected"
// SectionRendererTest
@Test void doesNotStampVersionIntoFileWithoutOne() // metadata lines contain no "mzTab-version" key
// EngineOpenApiContractTest
@Test void liveSpecMatchesCommittedContract()      // /v3/api-docs (servers removed, keys sorted) == engine-api/src/main/resources/engine-openapi.json
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -B -pl engine-mztabm-2.0 -am test`
Expected: compilation failure.

- [ ] **Step 3: Implement.** `engine-mztabm-2.0/pom.xml`:
  - artifactId `mztab-validator-engine-2.0`;
  - `build-helper-maven-plugin` `add-source` `${project.basedir}/../engine-core-src/src/main/java`, `add-resource` `../engine-core-src/src/main/resources`, `add-test-source` `../engine-core-src/src/test/java`;
  - dependencies: `jmztabm-io` and `jmztabm-validation` at `${jmztabm20.version}`; `jmztabm-test-utils` (test); `mztab-validator-engine-api`; `spring-boot-starter-web`, `-validation`, `-actuator`; springdoc; jaxb-runtime;
  - surefire `systemPropertyVariables`: `engine.line=2.0`, `openapi.update=${openapi.update}`;
  - resource filtering sets `engine.jmztabm-version=${jmztabm20.version}`;
  - `spring-boot-maven-plugin` `mainClass` `org.lifstools.mztab.validator.engine.EngineApplication`;
  - `server.port=8084`.

  `EngineConfig` provides the `OLSClient` and `CvParameterLookupService` beans (from `WebConfig`). Port the logic of `MzTabMValidator`, `MzTabConverter` and `SectionWriter.render`; don't rewrite it.

- [ ] **Step 4: Generate the contract, then run the tests**

Run:
```
./mvnw -B -pl engine-mztabm-2.0 -am test -Dtest=EngineOpenApiContractTest -Dopenapi.update=true -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -pl engine-mztabm-2.0 -am test
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pom.xml engine-api engine-core-src engine-mztabm-2.0
git commit -m "Add the mzTab-M 2.0 engine on jmzTab-m 1.0.x"
```

---

### Task 3: The 2.1 engine and version-specific expectations

**Files:**
- Create: `engine-mztabm-2.1/pom.xml` (as Task 2, with `${jmztabm21.version}`, `engine.line=2.1`, `engine.mztab-versions=2.1.0-M`, port 8085, artifactId `mztab-validator-engine-2.1`).
- Create: `engine-mztabm-2.0/src/test/java/.../engine/FactorsTwoZeroTest.java` and `engine-mztabm-2.1/src/test/java/.../engine/FactorsTwoOneTest.java`.
- Create the fixtures `study-variable-factors-2.0.mztab` and `study-variable-factors-2.1.mztab` under each engine's `src/test/resources/`: `minimal-m-2.0.mztab` plus a `study_variable[1]-factors` line, declared `2.0.0-M` and `2.1.0-M` respectively.
- Modify: `pom.xml`: add profile `snapshot-engines`, activated by `<property><name>!skipSnapshotEngines</name></property>`, with `<modules><module>engine-mztabm-2.1</module></modules>`.

**Interfaces:**
- Consumes: everything in Task 2 (the shared suite runs unchanged against 2.1).
- Produces: image/artifact `mztab-validator-engine-2.1`. The 2.1 engine's `liveSpecMatchesCommittedContract` passes against the same `engine-openapi.json`.

- [ ] **Step 1: Write the failing tests**

```java
// FactorsTwoZeroTest (2.0 engine)
@Test void keepsFactorsThroughJsonRoundTrip()  // tsv-to-json then json-to-tsv of study-variable-factors-2.0 → output contains "study_variable[1]-factors"
// FactorsTwoOneTest (2.1 engine)
@Test void reportsWarn2067ForFactorsIn21File() // validations of study-variable-factors-2.1 → a message with code containing "2067", message_type warn
@Test void dropsFactorsThroughJsonRoundTrip()  // round trip of the 2.1 file → output does not contain "study_variable[1]-factors"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -B -pl engine-mztabm-2.1 -am test`
Expected: the module is not found (no pom yet).

- [ ] **Step 3: Create `engine-mztabm-2.1/pom.xml`.** No Java sources besides tests.

- [ ] **Step 4: Run both engines' suites**

Run: `./mvnw -B -pl engine-mztabm-2.0,engine-mztabm-2.1 -am test`
Expected: PASS. `./mvnw -B -DskipSnapshotEngines -q help:evaluate -Dexpression=project.modules -DforceStdout` does not list `engine-mztabm-2.1`.

- [ ] **Step 5: Commit**

```bash
git add pom.xml engine-mztabm-2.0 engine-mztabm-2.1
git commit -m "Add the mzTab-M 2.1 engine on jmzTab-m 2.1.x"
```

---

### Task 4: Webapp engine client, registry, health and app info

**Files:**
- Create the `server/.../webapp/engine/*` files of the file map, with tests `EngineRegistryTest.java`, `EngineClientTest.java`, `EngineHealthContributorTest.java` under `server/src/test/java/.../webapp/engine/`.
- Modify:
  - `server/pom.xml`: test dependency `com.squareup.okhttp3:mockwebserver:4.12.0`;
  - `server/src/main/resources/application.properties`:
    - `engines.default-line=2.0`, `engines.mztabm[0].line=2.0`, `engines.mztabm[0].url=http://mztab-engine-2-0:8084`;
    - `engines.connect-timeout=5s`, `engines.job-timeout=10m`, `engines.unavailable-hint-url=`;
    - `management.endpoint.health.status.order=DOWN,OUT_OF_SERVICE,UP,UNAVAILABLE,UNKNOWN`, `management.endpoint.health.status.http-mapping.UNAVAILABLE=200`.
- Modify: `AppInfoApiController.AppInfoDto`. Replace `jmztabmVersion` with:
  - `List<EngineDto> engines`, where `EngineDto(String line, List<String> mzTabVersions, @Nullable String jmztabmVersion, boolean available)`;
  - `String defaultLine`;
  - `@Nullable String unavailableHintUrl`.

  Remove `jmztabm.version.number` from `AppInfo` and the resource filtering. Update `AppInfoApiControllerTest`.

**Interfaces:**
- Consumes: Task 1 DTOs; the engine HTTP API of Task 2.
- Produces:
  - `@ConfigurationProperties("engines") record EnginesProperties(String defaultLine, List<Engine> mztabm, Duration connectTimeout, Duration jobTimeout, @Nullable String unavailableHintUrl)` with `record Engine(String line, URI url)`. It is validated with `@Validated`: `line` matches `^[0-9]+\.[0-9]+$`, the URL scheme is http or https, and lines are unique.
  - `EngineRegistry`:
    - `Optional<EngineClient> forLine(String line)`;
    - `Set<String> lines()`;
    - `String defaultLine()`;
    - `RouteDecision route(Optional<String> declared)`, with `sealed RouteDecision`: `Routed(EngineClient engine, String line, boolean defaultApplied)`, `Legacy(String declared)`, `Unsupported(String declared, Set<String> supported)`;
    - `Map<String, Optional<EngineInfo>> lastKnownInfo()`, refreshed every 60 s and on demand.

    Construction fails if `defaultLine ∉ lines()`.
  - `EngineClient`, built on `RestClient` + `JdkClientHttpRequestFactory` (connect timeout, read timeout = job timeout):
    - `EngineInfo info()`;
    - `<T> T validate(Path file, @Nullable Path mapping, ValidationLevel level, int maxErrors, Function<InputStream, T> ndjsonReader)`;
    - `Path tsvToJson(InputStream tsv, Path target)`;
    - `Path jsonToTsv(InputStream json, boolean validate, Path target)`.

    Error mapping: 503 → `EngineBusyException(Duration retryAfter)`; connect failure or timeout → `EngineUnavailableException(String line)`; 422 → `EngineRejection(List<ValidationMessage>)`; 400 / 413 → `EngineRequestException(EngineError)`.
  - `EngineHealthContributor`: a `CompositeHealthContributor` with one `HealthIndicator` per line named `engine-<line with "." replaced by "-">` (`engine-2-0`). Statuses: `UP` with `EngineInfo` details, or `UNAVAILABLE`.

- [ ] **Step 1: Write the failing tests**

```java
// EngineRegistryTest
@Test void routesDeclaredVersionToItsLine()             // "2.1.0-M" with lines {2.0,2.1} → Routed(line "2.1", defaultApplied false)
@Test void missingVersionUsesDefaultLine()               // Optional.empty() → Routed(line "2.0", defaultApplied true)
@Test void legacyVersionIsLegacy()                       // "1.0.0" → Legacy
@Test void lineWithoutEngineIsUnsupported()              // "2.1.0-M" with lines {2.0} → Unsupported(supported = {"2.0"})
@Test void unknownLineIsUnsupported()                    // "2.2.0-M" → Unsupported
@Test void startupFailsWhenDefaultLineHasNoEngine()      // defaultLine 2.1, lines {2.0} → IllegalStateException
// EngineClientTest (MockWebServer)
@Test void busyMapsToEngineBusyExceptionWithRetryAfter() // 503 + Retry-After: 7 → retryAfter 7 s
@Test void connectionRefusedMapsToUnavailable()          // closed port → EngineUnavailableException("2.0")
@Test void slowEngineTimesOut()                          // job-timeout 1s, server delays 3 s → EngineUnavailableException
@Test void uploadIsStreamedNotBuffered()                 // 20 MB file, MockWebServer records body size == file size; the test runs with -Xmx128m
// EngineHealthContributorTest
@Test void downEngineIsUnavailableButOverallHealthIsUp() // GET /actuator/health with the engine at a closed port → 200, status UP,
                                                         // components["engines"]["components"]["engine-2-0"].status == "UNAVAILABLE"
// AppInfoApiControllerTest
@Test void listsEnginesAndDefaultLine()                  // engines[0].line "2.0", defaultLine "2.0", unavailableHintUrl null
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest='Engine*Test,AppInfoApiController*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Implement** the classes listed in Interfaces. No caller uses them yet.

- [ ] **Step 4: Run all server tests**

Run: `./mvnw -B -pl server -am test -Dfrontend.skip=true`
Expected: PASS. Update `OpenApiSpecExportTest`'s committed spec (`-Dopenapi.update=true`, see its `STALE` message) for the `AppInfoDto` change.

- [ ] **Step 5: Commit**

```bash
git add server frontend/openapi/mztab-m-api.json
git commit -m "Webapp: engine registry, client, health and engines in app info"
```

---

### Task 5: Asynchronous validations through the engines

**Files:**
- Modify:
  - `server/.../session/ValidationRunner.java`;
  - `ValidationSessionService.java` (`submit`, `resolveProfile`);
  - `ValidationRecord.java` (new components `@Nullable String engine`, `boolean defaultVersionApplied`; factory methods carry them);
  - `ValidationRunnerTest.java`, `ValidationSessionServiceTest.java`, `ValidationsApiControllerTest.java`;
  - `.github/workflows/maven.yml` (e2e job: start both engines).
- Create: `server/.../engine/EngineResultReader.java`, `server/src/test/java/.../engine/EngineResultReaderTest.java`, `compose/dev-stack.yml`, `scripts/run-dev-stack.sh`.
- Delete: `server/.../sections/SectionWriter.java` (its storage part moves into `EngineResultReader`); `SectionWriteRaceTest` is adapted to `EngineResultReader`.

**Interfaces:**
- Consumes: `EngineRegistry.route`, `EngineClient.validate`, the `ValidationEvent` types.
- Produces:
  - `EngineResultReader.read(UUID id, InputStream ndjson) -> EngineResult(Outcome outcome, @Nullable SectionsSummary sections)`. It writes `metadata.jsonl` (`MetadataEntry {line, key, value}`) and one table file per section (header row first, then rows) through `StorageService.write(id, SLOT.SECTIONS, …)`, spooling each section through a temporary file.
    - It throws `TruncatedEngineStreamException` when the stream ends before `end`, after deleting `SLOT.SECTIONS`.
    - File names and formats are exactly those `SectionReader` reads today (`Section.fileName()`).
  - `ValidationRecord.engine` is a label such as `mzTab-M 2.1 · jmzTab-m 2.1.0-SNAPSHOT (build abc123)`, built from `EngineInfo`.
  - Submit-time checks in `ValidationSessionService.submit` (detection on the stored upload):
    - legacy → the record is created and finishes as today's `rejected(...)`;
    - unsupported → the record is created and finishes `rejected` with the message from the Copy block below;
    - a catalog profile whose `mzTabVersions` lines don't contain the routed line → `IllegalArgumentException`, giving 400 from `RestErrorAdvice`: `The validation profile <id> <version> applies to mzTab-M <lines>, but the file is validated as mzTab-M <line>.`

Copy:
- Unsupported: `This file declares mzTab-version <declared>. This validator supports mzTab-M <supported, joined with " and ">.` plus, if `engines.unavailable-hint-url` is set: ` mzTab-M <line> is available for testing on the development validator: <url>`.
- Engine unavailable or timeout: `The mzTab-M <line> validator is temporarily unavailable. Please try again later.`

Busy handling:
- `EngineBusyException` before any byte of the response → the record goes back to `QUEUED`, and the runner reschedules itself (Spring `TaskScheduler`) after `max(retryAfter, backoff)`. The backoff starts at 2 s, doubles, and is capped at 30 s.
- When `createdAt + engines.job-timeout` has passed → the record finishes FAILED with the unavailable message.

- [ ] **Step 1: Write the failing tests**

```java
// EngineResultReaderTest
@Test void writesSectionsInSectionReaderFormat()          // canned NDJSON → SectionReader.metadataGroups / table pages equal those of the pre-change fixture
@Test void truncatedEngineStreamFailsValidationAndRemovesSections() // NDJSON without "end" → TruncatedEngineStreamException; storage has no SLOT.SECTIONS files
@Test void unparsedOutcomeWritesNoSections()              // outcome parsed=false + end → sections null, no files
// ValidationRunnerTest (stub EngineRegistry / MockWebServer)
@Test void routesTwoOneFileToTwoOneEngine()              // 2.1 fixture → request reached the 2.1 server; record.mzTabVersion "2.1.0-M", engine label contains "2.1"
@Test void fileWithoutVersionRecordsDefaultApplied()     // → defaultVersionApplied true
@Test void unsupportedLineFinishesWithOneMessage()       // lines {2.0}, 2.1 file → FAILED, one ERROR message starting "This file declares mzTab-version 2.1.0-M."
@Test void engineDownFinishesFailedNotRunning()          // closed port → FAILED with "temporarily unavailable"
@Test void busyEngineRequeuesThenSucceeds()              // first 503 Retry-After 1, then 200 → QUEUED in between, finally FINISHED
@Test void busyBeyondJobTimeoutFails()                   // always 503, job-timeout 3s → FAILED with "temporarily unavailable"
@Test void engineVersionDifferingFromDetectedIsKeptAndLogged() // outcome.mzTabVersion "2.0.1-M" vs detected "2.0.0-M" → record has "2.0.1-M"
// ValidationSessionServiceTest
@Test void catalogProfileOfOtherLineIsRejectedAtSubmit() // mztab-m-2.0-default + 2.1 file → IllegalArgumentException with the copy above
@Test void customMappingIsAcceptedForAnyLine()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest='EngineResultReaderTest,ValidationRunnerTest,ValidationSessionServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL / compilation failure.

- [ ] **Step 3: Implement.**
  - `ValidationRunner.execute` no longer touches jmzTab-m: route → `EngineClient.validate(upload, mapping, …, reader::read)` → `record.finished(...)`.
  - `ValidationRunner.isJson`, the JSON → TSV branch and `SectionWriter` go.
  - Existing tests that validated in-process (`ValidationsApiControllerTest`, `MappingUploadIntegrationTest`, `DefaultProfileAccessIntegrationTest`, `ExamplesApiControllerTest`, `AwaitAsyncValidations` users) run against a real engine:
    - add `server/src/test/java/.../webapp/EngineTestSupport.java`, which starts `engine-mztabm-2.0`'s jar on a free port once per JVM (built by `-am`; the jar path is passed as surefire property `engine20.jar=${project.basedir}/../engine-mztabm-2.0/target/mztab-validator-engine-2.0-${project.version}.jar`) and registers `engines.mztabm[0].url`;
    - this needs `engine-mztabm-2.0` before `server` in the reactor: add it to `server/pom.xml` as a `<scope>test</scope>` dependency of `<type>pom</type>` with `<exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>`. A pom-typed dependency still brings its dependencies transitively, so without the wildcard exclusion jmzTab-m would land on the server's test classpath. `NoJmzTabMArchTest` (Task 6) and `dependency:tree` catch a missing exclusion.
- [ ] **Step 4: Update the e2e job and the local stack**
  - `.github/workflows/maven.yml` e2e job, "Start the server": start `engine-mztabm-2.0` (`--server.port=8084`) and `engine-mztabm-2.1` (`--server.port=8085`) jars before the webapp, each health-polled like the webapp. Start the webapp with `--engines.mztabm[0].line=2.0 --engines.mztabm[0].url=http://localhost:8084 --engines.mztabm[1].line=2.1 --engines.mztabm[1].url=http://localhost:8085`.
  - Logs: `$RUNNER_TEMP/engine-2.0.log` and `$RUNNER_TEMP/engine-2.1.log`, uploaded on failure like `server.log`.
  - `compose/dev-stack.yml` and `scripts/run-dev-stack.sh`: the same three processes for local development; the webapp keeps `run-dev.sh` semantics.

- [ ] **Step 5: Run all tests**

Run: `./mvnw -B verify`
Expected: PASS (including the existing e2e-independent suites).

- [ ] **Step 6: Commit**

```bash
git add server compose scripts .github/workflows/maven.yml
git commit -m "Webapp: run asynchronous validations through the version engines"
```

---

### Task 6: Synchronous endpoints through the engines; jmzTab-m leaves the webapp

**Files:**
- Modify:
  - `server/.../mztab2/server/api/ValidateApi.java`, `ValidatePlainApi.java`, their controllers, and `ConvertApiController.java`;
  - `ValidationsApiController.java`, `ValidationRecord.java`, `RestErrorAdvice.java`: switch to `engine.api.ValidationMessage` / `ValidationLocation`;
  - `server/.../webapp/WebConfig.java` (drop the OLS, lookup and `ParameterConverter` beans);
  - `server/.../service/validation/SafeCvMappingLoader.java`: keep only `validateAgainstSchema(byte[])` and the hardened reader, against `classpath:/schema/CvMapping.xsd`; drop JAXB;
  - `server/pom.xml`: remove `jmztabm-io`, `jmztabm-validation`, `jmztabm-test-utils`, jaxb; add the enforcer `bannedDependencies` for `org.lifs-tools:jmztabm-*` and `uk.ac.ebi.pride.utilities:*`; add `com.tngtech.archunit:archunit-junit5:1.4.1` (test).
- Create:
  - `server/src/main/resources/schema/CvMapping.xsd` (copied from `jmztabm-validation` 1.0.8-SNAPSHOT);
  - `server/src/main/resources/mztab-schemas/2.0.yml` and `2.1.yml` (`mzTab_m_openapi.yml` from `jmztabm-api` 1.0.8-SNAPSHOT and 2.1.0-SNAPSHOT);
  - `server/.../mztab2/server/config/MzTabSchemasCustomizer.java`;
  - `server/src/test/java/.../webapp/NoJmzTabMArchTest.java`;
  - `server/src/test/resources/metabolomics/*`: copies of the 9 fixtures the server tests use (`lipidomics-example.mzTab`, `minimal-m-2.0.mztab`, `gcxgc-ms-example.mztab`, `example_study_variable_group.mztab`, `MouseLiver_negative.mzTab`, `MTBLS263.mztab`, `StandardMix_positive_exportSpeciesLevel.mzTab`, `xcms-msio-0.0.11-MTBLS4381-onlySMF-trimmed.mztab`, `publication-samples/lipidomics-example-missing-feature-and-evidence.mzTab`);
  - `engine-core-src/src/test/java/.../engine/WebappCopiesTest.java`.
- Move: `server/.../service/cvcompletion/*` → `engine-core-src/src/main/java/.../engine/cvcompletion/` (unchanged; no endpoint yet).
- Delete: `MzTabMValidator`, `MzTabConverter`, `MzTabValidationService`, `ValidationService`, `WebValidator` and their server tests (their behaviour is covered by the engine suite).

**Interfaces:**
- Consumes: `EngineRegistry.route`, `EngineClient.validate/tsvToJson/jsonToTsv`, `ValidationProfileCatalog.defaultFor(String line)` (added here: the latest version of profile id `mztab-m-<line>-default`, empty if absent).
- Produces:
  - `/validate`, `/validatePlain`: same parameters and status codes as today.
    - `semanticValidation=true` → the mapping of `catalog.defaultFor(routedLine)`; no bundled `/mappings/mzTab-M-mapping.xml` any more.
    - Unsupported or legacy → `422` with the one message.
    - Engine unavailable → `503` `ErrorResponse`; busy → `503` with `Retry-After` (same body as today's `SynchronousLimiter.BUSY`).
    - `/validate` (JSON body): the webapp spools the body, detects the version with `detectJson`, and forwards the JSON to the engine. The engine converts it.
  - `/convert`, `/convertPlain`: routed the same way. The response body is streamed from the engine's temporary-file result. Status codes are unchanged.
  - `MzTabSchemasCustomizer implements OpenApiCustomizer`:
    - reads both yml files with `io.swagger.v3.core.util.Yaml31.mapper()`;
    - adds every schema of each except `ValidationMessage`, `ValidationLocation` and `Error`, renamed `<Name>_2_0` / `<Name>_2_1`, with every `$ref` `#/components/schemas/X` rewritten to `X_<suffix>`, recursively through properties, items, allOf/oneOf/anyOf and additionalProperties;
    - sets the request body of `/rest/v2/validate` and `/rest/v2/convert` and the 200 response of `/rest/v2/convertPlain` to `oneOf [MzTab_2_0, MzTab_2_1]`. The `@Schema(implementation = MzTab.class)` annotations become `@Schema(type = "object")`, and the customizer replaces them.
  - `WebappCopiesTest` (shared engine suite) compares, byte for byte:
    - `${webapp.dir}/src/main/resources/schema/CvMapping.xsd` with the classpath `/schema/CvMapping.xsd`;
    - `${webapp.dir}/src/main/resources/mztab-schemas/<engine.line>.yml` with the classpath `/mzTab_m_openapi.yml`.

    `webapp.dir=${project.basedir}/../server` is set as a surefire property in both engine poms. The failure message names the file to update.

- [ ] **Step 1: Write the failing tests**

```java
// NoJmzTabMArchTest
@ArchTest static final ArchRule noJmzTabM = noClasses().should().dependOnClassesThat()
    .resideInAnyPackage("uk.ac.ebi.pride.jmztab2..", "info.psidev.cvmapping..", "uk.ac.ebi.pride.utilities.ols..")
    .orShould().dependOnClassesThat(resideInAPackage("org.lifstools.mztab2..").and(not(resideInAPackage("org.lifstools.mztab2.server.."))));
// ValidateApiIntegrationTest (existing, now via EngineTestSupport) + new:
@Test void semanticValidationUsesTheDefaultProfileOfTheFilesLine()  // 2.1 fixture, only engine 2.0 configured → 422, one message "This file declares mzTab-version 2.1.0-M."
                                                                    // 2.0 fixture → engine request had a mapping part equal to mztab-m-2.0-default latest XML
@Test void validateWithEngineDownIs503()
// ConvertApiIntegrationTest (existing) keeps passing unchanged, plus:
@Test void convertRoutesByJsonVersion()                              // JSON declaring 2.1.0-M with only 2.0 configured → 422 unsupported message
// OpenApiSpecExportTest: committed spec regenerated; new assertion
@Test void mzTabBodiesOfferBothVersions()  // components.schemas has MzTab_2_0 and MzTab_2_1; /rest/v2/convert requestBody schema oneOf refs both;
                                           // StudyVariable_2_0.properties has "factors", StudyVariable_2_1.properties does not
// WebappCopiesTest (engines)
@Test void webappXsdCopyMatchesJar()
@Test void webappSchemaCopyMatchesJar()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest='NoJmzTabMArchTest,ValidateApiIntegrationTest,ConvertApiIntegrationTest,OpenApiSpecExportTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `NoJmzTabMArchTest` fails, listing the current jmzTab-m uses.

- [ ] **Step 3: Implement** the changes listed under Files and Interfaces. Remove `jmztabm.version.number` from every properties file.

- [ ] **Step 4: Regenerate the spec and the TS client, then run everything**

Run:
```
./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest=OpenApiSpecExportTest -Dopenapi.update=true -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B verify
./mvnw -B -pl server dependency:tree -Dincludes=org.lifs-tools:jmztabm-*
```
Expected: verify passes; the dependency tree shows no `jmztabm-*` artifact; `npm --prefix frontend run build` compiles against the regenerated client.

- [ ] **Step 5: Commit**

```bash
git add server engine-core-src engine-mztabm-2.0 engine-mztabm-2.1 frontend/openapi frontend/projects/webapp/src/app/api
git commit -m "Webapp: synchronous endpoints through the engines; remove jmzTab-m from the webapp"
```

---

### Task 7: Validation profiles and examples per version (server)

**Files:**
- Create:
  - `server/src/main/resources/validation-profiles/mztab-m-2.0-default/1.1.0.xml`: the `mappings/mzTab-M-mapping.xml` of `jmztabm-validation` 1.0.8-SNAPSHOT; `1.0.0.xml` no longer matches it and stays, since versions are immutable;
  - `server/src/main/resources/validation-profiles/mztab-m-2.1-default/1.0.0.xml`: the same file from 2.1.0-SNAPSHOT;
  - `engine-core-src/src/test/java/.../engine/CatalogCopyTest.java`.
- Modify:
  - `validation-profiles/index.json`: add `mztab-m-2.0-default` 1.1.0, and `mztab-m-2.1-default` 1.0.0 with title `mzTab-M 2.1 default rules`, description `The reference semantic rules shipped with jmzTab-m 2.1.`, `mzTabVersions ["2.1.0-M"]`;
  - `ValidationProfileCatalog.java` (`defaultFor` from Task 6, `forLines(Set<String>)`);
  - `ValidationProfilesApiController.java`;
  - `ExampleFile.java` (+ `mzTabVersion`), `ExamplesApiController.java` (`ExampleDto` + `mzTabVersion`, filtering, rejection);
  - `examples.properties`: `mzTabVersion=2.0.0-M` on all 20 entries, plus 5 new `section=example` entries with `mzTabVersion=2.1.0-M`, `focus=Metabolomics`, `createdBy=jmzTab-m`;
  - their tests.

  The URIs of the new entries are pinned to jmzTab-m commit `11a72c844bf3edc9c462a6fc4b6467326c75482f`, in the form `https://raw.githubusercontent.com/lifs-tools/jmzTab-m/11a72c844bf3edc9c462a6fc4b6467326c75482f/test-utils/src/main/resources/metabolomics/<name>`, for:
  - `example_study_variable_group.mztab` (description `Study variable groups (mzTab-M 2.1)`);
  - `official-profile-m.mztab` (`Profile M: summary only`);
  - `official-profile-mf.mztab` (`Profile MF: summary and features`);
  - `official-profile-mfe.mztab` (`Profile MFE: summary, features and evidence`);
  - `official-profile-ms.mztab` (`Profile MS: summary and evidence`).

**Interfaces:**
- Consumes: `EngineRegistry.lines()`, `MzTabVersions.lineOf`.
- Produces:
  - `GET /rest/v2/validation-profiles?mzTabVersion=` (optional; accepts a line `2.1` or a version `2.1.0-M`; anything else → 400). It lists only profiles with at least one version whose line has an engine.
  - `GET /rest/v2/validation-profiles/{id}/{version}/mapping` → `application/xml`, `Content-Disposition: attachment; filename="<id>-<version>.xml"`. Unknown entries → 404; ids and versions outside the patterns of Global Constraints → 400.
  - `ExampleDto(String id, String name, String section, String description, String version, String mzTabVersion, String focus, String createdBy, URI uri)`. `GET /rest/v2/examples` omits entries whose `mzTabVersion` line has no engine.
  - `POST /rest/v2/examples/{id}/validations` for such an entry → `404 Unknown example.`, before any download.
  - `CatalogCopyTest`: the latest version of `mztab-m-<engine.line>-default` under `${webapp.dir}/src/main/resources/validation-profiles/` is byte-identical to the classpath `/mappings/mzTab-M-mapping.xml`. The failure message says: `jmzTab-m's bundled mapping changed: add a new catalog version mztab-m-<line>-default/<next>.xml`.

- [ ] **Step 1: Write the failing tests**

```java
// ValidationProfilesApiControllerTest
@Test void listsOnlyProfilesOfConfiguredLines()            // engines {2.0} → ids contain mztab-m-2.0-default, not mztab-m-2.1-default
@Test void filtersByVersionOrLine()                        // ?mzTabVersion=2.1.0-M and ?mzTabVersion=2.1 with engines {2.0,2.1} → only 2.1 profile
@ParameterizedTest @ValueSource(strings = {"2.x", "../etc", ""}) void rejectsInvalidVersionFilter(String v)  // → 400
@Test void downloadsMappingXml()                           // /validation-profiles/mztab-m-2.1-default/1.0.0/mapping → 200 application/xml, attachment
@ParameterizedTest @CsvSource({"..%2Fx,1.0.0", "mztab-m-2.0-default,1.0", "Mztab,1.0.0"}) void rejectsInvalidIdOrVersion(String id, String version) // → 400
@Test void unknownProfileIs404()
// ExamplesApiControllerTest
@Test void examplesCarryMzTabVersion()                     // every entry has mzTabVersion; the 5 new ones "2.1.0-M"
@Test void hidesExamplesOfUnavailableLines()               // engines {2.0} → no "2.1.0-M" entry
@Test void exampleOfUnavailableLineIsRejectedWithoutFetching() // engines {2.0}, POST official-profile-m.mztab → 404; ExampleFetcher mock never called
// ValidationProfileCatalogTest
@Test void defaultForLineIsLatestVersion()                 // defaultFor("2.0") → 1.1.0
// CatalogCopyTest (engines)
@Test void defaultProfileCopyMatchesBundledMapping()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest='ValidationProfile*Test,ExamplesApiControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL.

- [ ] **Step 3: Implement** the changes listed in Interfaces.

- [ ] **Step 4: Regenerate the spec and client, then run everything**

Run: the `OpenApiSpecExportTest` update command of Task 6, then `./mvnw -B verify`
Expected: PASS, including `CatalogCopyTest` in both engines.

- [ ] **Step 5: Commit**

```bash
git add server engine-core-src frontend/openapi frontend/projects/webapp/src/app/api
git commit -m "Validation profiles and examples per mzTab-M version; mzTab-M 2.1 default rules"
```

---

### Task 8: SPA: version-aware form, examples, result and About pages

**Files:**
- Create: `frontend/projects/webapp/src/app/core/mztab-version.ts` + `mztab-version.spec.ts`.
- Create the e2e fixtures in `frontend/e2e/fixtures/`: `profile-mf-2.1.mzTab` (copy of jmzTab-m's `official-profile-mf.mztab`) and `no-version.mzTab`.
- Create the e2e specs `frontend/e2e/versions.spec.ts` and `frontend/e2e/engine-down.spec.ts` (tagged `@engine-down`).
- Modify:
  - `core/validation-defaults.ts`: `DEFAULT_VALIDATION_PROFILE` becomes `defaultProfileFor(line: string): string`, returning `mztab-m-<line>-default`;
  - `pages/landing/validator-form.component.{ts,html,scss,spec.ts}`;
  - `pages/examples/examples.page.{ts,html,scss}`, plus a new `examples.page.spec.ts` if missing;
  - `pages/landing/examples-card.component.{ts,html}`;
  - `pages/result/result-header.component.{ts,html}` and its spec;
  - `pages/about/about.page.{html,ts,spec.ts}`;
  - `playwright.config.ts`: the default project uses `grepInvert: /@engine-down/`;
  - `.github/workflows/maven.yml`, e2e job, after "Run end-to-end tests": stop the 2.1 engine (`kill` its PID), then run `npm --prefix frontend run e2e -- --grep @engine-down`.

**Interfaces:**
- Consumes: `AppInfoDto.engines`, `.defaultLine`, `.unavailableHintUrl` (Task 4); `ValidationProfile.mzTabVersions`; `listValidationProfiles({mzTabVersion})`; the mapping download URL (Task 7); `ExampleDto.mzTabVersion`; `ValidationRecord.engine`, `.defaultVersionApplied`.
- Produces:
  - `detectMzTabVersion(file: Blob): Promise<string | null>`:
    - TSV: reads at most the first 1 MiB as UTF-8, strips a BOM, and finds the first `MTD\tmzTab-version\t` line (stopping at the first non-`MTD`/`COM`, non-blank line);
    - JSON (first non-whitespace char `{`): reads the stream chunk-wise and stops at the first `/"mzTab-version"\s*:\s*"([^"]*)"/`;
    - otherwise `null`.
  - `lineOf(version: string | null): string | null` with the same rule as `MzTabVersions.lineOf`.

UI rules:
- **Form:**
  - when a file is set, a `fileVersion` signal is filled from `detectMzTabVersion`, and `activeLine = lineOf(fileVersion) ?? info.defaultLine`;
  - the profile select lists only profiles whose `mzTabVersions` map to `activeLine`, plus Custom and None;
  - when `activeLine` changes, the selection resets to `defaultProfileFor(activeLine)` if listed, else None;
  - a badge next to the file name reads `mzTab-M 2.1` or, for no version, `No version declared · validated as mzTab-M <defaultLine>`;
  - if `activeLine` has no available engine, a `lifs-callout lifs-callout--warn` (`role="status"`) appears: `mzTab-M <line> is not supported by this validator.`, plus `It is available for testing on the development validator.`, linked to `unavailableHintUrl`, when set. Submit stays enabled; the server's answer is authoritative.
- **Examples page:**
  - a version filter (Material button toggle group) `All` plus one entry per available engine line, newest first;
  - a version badge on every entry;
  - the card "Example mapping files" is renamed **"Mapping files"**. It lists catalog profiles first (title, description, versions badge, download link to `/rest/v2/validation-profiles/{id}/{version}/mapping`), then `section=mapping` entries (with their free-text `version`, e.g. `DRAFT`).
- **Landing examples card:** only examples of `defaultLine`, with a link "All examples" to `/examples`.
- **Result header:**
  - the existing version badge gains `matTooltip` = `record.engine`;
  - if `defaultVersionApplied`, a second badge `default version`.
- **About:**
  - a section "mzTab-M versions" listing each engine: line, jmzTab-m version, and whether it is available;
  - one paragraph: `mzTab-M 2.0 is validated by jmzTab-m 1.0.x and mzTab-M 2.1 by jmzTab-m 2.1.x. Once mzTab-M 2.1 is the default, 2.0 receives bug fixes only.`;
  - remove the `jmztabmVersion` row.

- [ ] **Step 1: Write the failing tests**

```ts
// mztab-version.spec.ts — use the same 7 fixtures as Task 1 (copied to frontend/projects/webapp/src/testing/version-detection/)
it('detects the TSV version after a BOM with CRLF', ...)        // → '2.1.0-M'
it('detects the JSON version when metadata comes last', ...)    // → '2.0.0-M'
it('returns null without a version', ...)
it('maps real-world spellings to lines', ...)                    // same table as lineOfAcceptsRealWorldSpellings
// validator-form.component.spec.ts
it('offers only the profiles of the detected line and preselects its default', ...) // 2.1 file → options [mztab-m-2.1-default, custom?, none], selected mztab-m-2.1-default
it('falls back to the default line for a file without version', ...)               // badge text contains 'validated as mzTab-M 2.0'
it('warns when the line has no engine', ...)                                        // engines [2.0], 2.1 file → callout text 'mzTab-M 2.1 is not supported by this validator.'
// examples.page.spec.ts
it('filters examples by version', ...)
it('lists catalog profiles before draft mappings under "Mapping files"', ...)
// result-header.component.spec.ts
it('shows the engine label as tooltip and the default-version badge', ...)
```

```ts
// e2e/versions.spec.ts (both engines running)
test('a 2.1 file is validated by the 2.1 engine', ...)        // upload profile-mf-2.1.mzTab → result badge 'mzTab-M 2.1.0-M', tooltip contains 'jmzTab-m 2.1'
test('a 2.0 example and a 2.1 example validate', ...)
test('a catalog profile of the other version is rejected', ...) // API request: POST /rest/v2/validations with mztab-m-2.0-default and the 2.1 file → 400 with the mismatch text
test('a file without version shows the default-version badge', ...)
// e2e/engine-down.spec.ts (@engine-down; 2.1 engine stopped)
test('a 2.1 file fails with the unavailable message while 2.0 still validates', ...)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm --prefix frontend test`
Expected: FAIL (new specs).

- [ ] **Step 3: Implement** the UI rules above.

- [ ] **Step 4: Run unit and e2e tests locally**

Run: `npm --prefix frontend test`, then start `scripts/run-dev-stack.sh` and run `npm --prefix frontend run e2e` and `npm --prefix frontend run e2e -- --grep @engine-down` (after stopping the 2.1 engine).
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend .github/workflows/maven.yml
git commit -m "SPA: mzTab-M version detection, per-version profiles and examples, engine details"
```

---

### Task 9: Images, CI publishing, release profile and documentation

**Files:**
- Modify:
  - `pom.xml`. The Jib `<container><mainClass>` becomes `${start-class}`, so each module sets `start-class`: engines `org.lifstools.mztab.validator.engine.EngineApplication`. The `release` profile gets `maven-enforcer-plugin` with `requireReleaseDeps` (`onlyWhenRelease=false`, `failWhenParentIsSnapshot=false`, excludes `org.lifs-tools:mztab-validator-*`).
  - `engine-mztabm-2.0/pom.xml` and `engine-mztabm-2.1/pom.xml`: a `docker` profile with Jib `skip=false` (as `server/pom.xml`); ports 8084 / 8085; `jvmFlags` `-XX:MaxRAMPercentage=75`.
  - `.github/workflows/maven.yml`: the integration-branch publish step passes `-Djib.to.tags=${RELEASE_VERSION}` and **no** `-Djib.to.image` (each module derives its own image name). Images:
    - `docker.lifs-tools.org/lifs/mztab-validator-webapp:<version>`;
    - `docker.lifs-tools.org/lifs/mztab-validator-engine-2.0:<version>`;
    - `docker.lifs-tools.org/lifs/mztab-validator-engine-2.1:<version>`.

    The master push step keeps `latest` for all three.
  - `docs/release/2.0.0-deployment.md`: rewritten for three services:
    - the rollout starts with **releasing jmzTab-m 1.0.8**, then the webapp release with `-Prelease -DskipSnapshotEngines`;
    - patch 1 (`-dev`) adds `mztab-engine-2-0` and `mztab-engine-2-1` services (internal network only, no Traefik labels, 2048M limit each) and the webapp env:
      ```
      ENGINES_MZTABM_0_LINE=2.0
      ENGINES_MZTABM_0_URL=http://mztab-engine-2-0:8084
      ENGINES_MZTABM_1_LINE=2.1
      ENGINES_MZTABM_1_URL=http://mztab-engine-2-1:8085
      ```
      and a webapp memory limit of 1024M;
    - patch 2 (production) adds only `mztab-engine-2-0`, with `ENGINES_UNAVAILABLE_HINT_URL=https://apps.lifs-tools.org/mztabvalidator-dev/`;
    - patch 3 (LipidCompass volume) stays and notes that engines come with the opt-in;
    - rollback notes.
  - `docs/self-hosting/configuration.md`, `operating.md`, `building.md`, `README.md`:
    - the three services, their images, ports, memory, health components;
    - the `engines.*` properties and their environment-variable forms;
    - `-DskipSnapshotEngines` for releases;
    - local development with `scripts/run-dev-stack.sh`.
  - `docs/release-notes/2.0.0.md`:
    - mzTab-M 2.1 on the development validator;
    - per-version engines and profiles;
    - new result fields `engine` and `defaultVersionApplied`;
    - app-info `engines` replacing `jmztabmVersion`;
    - OpenAPI schemas now `MzTab_2_0` / `MzTab_2_1` (generated clients see new type names);
    - the mapping download endpoint.
  - `README.adoc`: module overview and how to run the stack locally.

- [ ] **Step 1: Verify the release build excludes SNAPSHOT engines and fails on SNAPSHOT dependencies**

Run: `./mvnw -B -Prelease -DskipSnapshotEngines -DskipTests validate`
Expected: FAIL, with `requireReleaseDeps` naming `org.lifs-tools:jmztabm-io:1.0.8-SNAPSHOT` (proves the guard). `./mvnw -B -Prelease validate` (without the flag) also lists `engine-mztabm-2.1`; document both in `building.md`.

- [ ] **Step 2: Implement** the pom, Jib and workflow changes.

- [ ] **Step 3: Build the images locally without pushing**

Run: `./mvnw -B -Pdocker compile jib:dockerBuild -Dfrontend.skip=false`
Expected: three images in the local daemon: `lifs/mztab-validator-webapp`, `lifs/mztab-validator-engine-2.0` and `lifs/mztab-validator-engine-2.1`, tagged `2.0.0-SNAPSHOT`. Then:
- start them on a docker network (the `compose/dev-stack.yml` image variant);
- `curl` webapp `/mztabvalidator/actuator/health` shows `engines.engine-2-0` and `engine-2-1` `UP`;
- a 2.1 fixture POSTed to `/rest/v2/validatePlain` returns 422 messages from the 2.1 engine;
- re-tag `latest` locally back to what it pointed at before.

- [ ] **Step 4: Write the documentation changes**, then ask the project lead to review the documentation (CLAUDE.md rule).

- [ ] **Step 5: Run the full build**

Run: `./mvnw -B verify`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pom.xml engine-mztabm-2.0 engine-mztabm-2.1 .github/workflows/maven.yml docs README.adoc
git commit -m "Engine images, release guard for SNAPSHOT engines, deployment and self-hosting docs"
```

---

## Outside this repository (prepared, executed by the project lead)

- Release jmzTab-m **1.0.8** from `1.0.x` before the webapp 2.0.0 release (E11).
- docker-recipes-external and lipidcompass-ansible PRs per the rewritten `docs/release/2.0.0-deployment.md`.
- Future work, not in this plan: offline semantic validation ([jmzTab-m#241](https://github.com/lifs-tools/jmzTab-m/issues/241)); 2.0 → 2.1 conversion in the API and the SPA editor; the 2.1 engine in releases once jmzTab-m 2.1.0 is out.
