# mzTab-M 2.0 and 2.1 Side by Side — Version Engines Design

**Date:** 2026-10-30
**Status:** Draft, awaiting approval by the project lead. Open questions answered 2026-10-30 (§8).
**Builds on:** `2026-10-23-validator-spa-design.md` (the "SPA design" below) and, for the editor, `lipidcompass/docs/design/mztabm-editor/specs/standalone-editor-design.md` (the "standalone design"). Everything there that this document does not override still applies.

Decisions are tagged **[decided]** where the project lead decided them and **[my call]** where the author decided and the project lead can still correct it.

---

## 1. Intent

The validator and, later, the editor must handle **mzTab-M 2.0 and mzTab-M 2.1** files, each with **the rules of its own reference implementation** [decided]:

- mzTab-M 2.0: jmzTab-m **1.0.x** (today 1.0.8-SNAPSHOT, branch `1.0.x`);
- mzTab-M 2.1: jmzTab-m **2.1.x** (today 2.1.0-SNAPSHOT, branch `master`).

Both lines use the same Maven coordinates and Java packages (`org.lifstools.mztab2`), so they cannot both be loaded into one application without class-loader isolation or package relocation. The project lead chose to keep them in **separate services** [decided]: one small "engine" service per mzTab-M version, each built on its own jmzTab-m line, and a webapp that no longer contains jmzTab-m at all. This was preferred over class-loader isolation as cleaner, easier to understand and truer to separation of concerns.

### Why now

- 2.1 is about to become the new default. From then on, **2.0 is mostly frozen: bug fixes only** [decided].
- The 2.x webapp (`feat/validator-spa`, webapp version 2.0.0-SNAPSHOT) is not released yet. Its REST and SPA contracts can still change without breaking anyone. **The engine split ships in webapp 2.0.0 [decided].**
- **Priorities [decided]:** the stable mzTab-M **2.0** comes first; it is what the released webapp (production) validates. mzTab-M **2.1** is integrated early to follow the format's current development, but only on the **development** validator (`/mztabvalidator-dev`) until jmzTab-m 2.1.0 is released.
- Users need clearer choices between 2.0 and 2.1 example files and mapping files (validation profiles) than the current single, unlabelled list.

### Why native rules matter (facts, 2026-10-30)

The two lines already differ, and they will drift further as 2.1 evolves while 2.0 is frozen:

- jmzTab-m 2.1 reads `study_variable[n]-factors` but **does not keep them** (2.1 replaced factors by `study_variable_group`). 1.0.x keeps them. A 2.0 file parsed and written by the 2.1 line loses its factors.
- Model differences: `StudyVariable.factors` exists only in 1.0.x; both lines have `StudyVariableGroup`.
- Validation messages (`code`, `category`, `message_type`, `message`, `line_number`, `suggestion`, `rule_id`, `location`) have the **same JSON shape** in both lines.
- The bundled `mappings/mzTab-M-mapping.xml` is **identical** in both lines today. It will diverge once 2.1-only rules are added.
- Neither line rejects a file because of its declared `mzTab-version`. Only a missing version is an error (Error-2008).

---

## 2. Goals, non-goals, anti-goals

### Goals

1. **Version routing:** every file is parsed, validated, converted and written by the engine of the mzTab-M version it declares (`MTD mzTab-version`).
2. **Two engines, one contract:** a 2.0 engine (jmzTab-m 1.0.x) and a 2.1 engine (jmzTab-m 2.1.x) with the same internal API. Adding a future version (e.g. 2.2) means adding an engine module, not changing the webapp.
3. **The webapp has no jmzTab-m dependency.** It owns the SPA, sessions, storage, the validation profile catalog, examples, routing and the public REST API.
4. **Better 2.0 / 2.1 choices in the SPA:**
   - the validator form shows the file's detected mzTab-M version and offers only the validation profiles for that version;
   - examples and mapping files are grouped and filterable by mzTab-M version;
   - the result page shows which version and which engine (jmzTab-m line and version) produced the result.
5. **A 2.1 default validation profile** next to the 2.0 one, each a copy of its own jmzTab-m line's bundled mapping.
6. **Release builds depend only on released artifacts; development builds may use SNAPSHOTs [decided].** Production runs the webapp and the 2.0 engine on released jmzTab-m 1.0.x. `-dev` additionally runs the 2.1 engine on jmzTab-m 2.1.0-SNAPSHOT (and may run the 2.0 engine on the current 1.0.x SNAPSHOT).
7. **Authoring both versions in the editor** (milestone 2): the user picks 2.0 or 2.1 for a new file; an existing file keeps its declared version; the fields, tables and rules offered follow that version.
8. **A configurable default version** for files that declare none (initially 2.0, switched to 2.1 once 2.1 is established) [my call].
9. The public REST contracts (`/rest/v2/validate`, `/validatePlain`, `/convert`, `/convertPlain`, `/validations`, …) keep their shapes. Results gain fields; nothing is removed.

### Non-goals

- **Converting a 2.0 file to 2.1** (or back), in the API and in the editor. It is future work (§11) [decided]; this design only keeps it possible.
- Running both engines on one file to compare verdicts.
- New features in the 2.0 engine beyond what the shared engine contract needs. Once 2.1 is the default, the 2.0 engine gets bug fixes only.
- A plugin system for third-party engines.
- mzTab 1.0: stays retired (SPA design M9); a 1.0 file still gets the single "no longer supported" message.

### Anti-goals (failure even if it "works")

- A 2.0 file validated, viewed, converted or edited by the 2.1 library, or the reverse.
- jmzTab-m classes on the webapp's classpath, or the webapp re-implementing any mzTab-M parsing beyond reading the `mzTab-version` line.
- Engine logic copied into two diverging source trees (§5.2).
- Engines reachable from the internet.
- A missing or crashed engine that takes the whole webapp down, or that leaves validations "running" forever.
- The SPA showing a 2.1 profile for a 2.0 file without saying so, or silently validating with rules of the other version.

---

## 3. Current state (facts this design relies on)

Checked on `feat/validator-spa` at `48a90d8` (2026-10-30):

1. **15 of 80 server source files use jmzTab-m:**
   - validation: `MzTabMValidator`, `MzTabValidationService`, `ValidationService`, `WebValidator`, `ValidationRunner`;
   - conversion: `MzTabConverter`, `ConvertApiController`;
   - sections: `SectionWriter` (the writer only; `SectionReader` reads plain JSON and needs no jmzTab-m);
   - public API types: `ValidateApi`, `ValidatePlainApi`, `ValidationsApiController`, `ValidationRecord` (`ValidationMessage`, `ValidationLocation`);
   - CV suggestions: `OlsMappingCvSuggestionService` (not used by anything yet);
   - configuration: `WebConfig` (`CvParameterLookupService`, `ParameterConverter`, the OLS client);
   - mapping files: `SafeCvMappingLoader` (JAXB `info.psidev.cvmapping.CvMapping` and `/schema/CvMapping.xsd`, both from the `jmztabm-validation` jar).

   The public controllers live in the webapp's own package `org.lifstools.mztab2.server.api`, which springdoc scans (`SwaggerDocumentationConfig`); that package is not part of jmzTab-m.
2. **`ValidationRunner`** stores the upload, converts a JSON upload to TSV with `MzTabNonValidatingWriter`, detects the version (`MzTabVersionDetector`, a plain text read), rejects mzTab 1.0, validates and parses with `MzTabMValidator`, then writes the sections with `SectionWriter`.
3. **Sections:** `SectionWriter` renders the parsed model through jmzTab-m's TSV writer mappers (`MzTabWriterDefaults`, `CsvMapper`) and stores line-oriented JSON in the session directory:
   - `metadata.jsonl`: one `MetadataEntry {line, key, value}` per metadata line;
   - one file per table: one JSON array of strings per row, the header row first.

   `SectionReader` serves pages from these files and needs no jmzTab-m. The format is version-agnostic. For a file without `mzTab-version`, `SectionWriter.render` stamps `MZTabConstants.VERSION_MZTAB_M` (`"2.0.0-M"` in both jmzTab-m lines) into the rendered metadata.
4. **Version detection:** `MzTabVersionDetector` reads only the TSV `MTD	mzTab-version` line. For a JSON upload, `ValidationRunner` deserializes the whole file into jmzTab-m's `MzTab` and reads the version from the model.
5. **Validation profile catalog:** `validation-profiles/index.json` plus `{id}/{version}.xml`. Each entry already carries `mzTabVersions` (`["2.0.0-M"]` for the only entry, `mztab-m-2.0-default` 1.0.0). Nothing checks a profile's versions against the file's version yet.
6. **Examples:** `examples.properties` has 20 entries, each with an explicit `section` (`example` or `mapping`): 17 mzTab-M files and 3 mapping XML files ("example mapping files"). `ExamplesApiController` and `ExampleDto.section` expose the `section`. All mzTab-M files are 2.0, from HUPO-PSI/mzTab `examples/2_0-Metabolomics_Release`. The mapping files are labelled `2.0` or `DRAFT`. HUPO-PSI publishes **no 2.1 examples** yet. jmzTab-m `master` has 2.1 test files in `test-utils/src/main/resources/metabolomics/` (e.g. `example_study_variable_group.mztab`, `official-profile-{m,mf,mfe,ms}.mztab`, declared `2.1.0-M`).
7. **SPA:**
   - the form has one "Validation profile" select (catalog entries, "Custom mapping file", "None");
   - the examples page has an "Example files" list and an "Example mapping files" list, each with a version note.
8. **Deployment** (SPA design §3 fact 11 and `docs/release/2.0.0-deployment.md`): one replica per instance, a storage volume per instance, Jib images `lifs/mztab-validator-webapp`, `-XX:MaxRAMPercentage=75` with a 2048M container limit. The `microservice` profile registers with Eureka (LipidCompass stack).

---

## 4. Decisions

| # | Decision | Status |
|---|---|---|
| E1 | Each mzTab-M version is handled by the reference implementation of that version. | decided |
| E2 | Separate engine services per version (option 3), not class-loader isolation or package relocation. | decided |
| E3 | 2.0 is frozen to bug fixes once 2.1 is the default; the editor authors both versions. | decided |
| E4 | Engines live in the jmzTab-m-webapp repository as Maven modules, released and versioned together with the webapp. | my call |
| E5 | Engines are stateless: the webapp streams the file to an engine and stores whatever comes back. Engines have no volume. | my call |
| E6 | One engine instance per version per stack; engines are not shared between stacks. Memory is not a constraint (three swarm nodes with 128 GB each). | decided (capacity), my call (no sharing) |
| E7 | Routing by the declared `mzTab-version`, read by the webapp (the existing TSV detector plus a new streaming JSON detector); a configurable default for files without one. | my call |
| E8 | The webapp defines its own DTOs for the shapes it exchanges with engines and clients; the JSON stays identical to jmzTab-m's. | my call |
| E9 | A catalog validation profile applies only to the mzTab-M versions it lists; a mismatch is rejected. Custom mapping files are accepted for any version. | my call |
| E10 | The engine split ships in webapp **2.0.0**. Production (release) runs the 2.0 engine only; the 2.1 engine runs on `-dev` until jmzTab-m 2.1.0 is released. | decided |
| E11 | A release build depends only on released artifacts (jmzTab-m included); SNAPSHOT dependencies are allowed in development builds only. | decided |
| E12 | Converting 2.0 files to 2.1 (API and SPA editor) is future work. | decided |
| E13 | An engine is optional per stack: the webapp offers a version only where its engine is configured. | my call |

---

## 5. Design

### 5.1 Responsibilities

| Concern | Webapp (`server`) | Engine (`engine-mztabm-2.0`, `engine-mztabm-2.1`) |
|---|---|---|
| SPA, public REST API, OpenAPI document | ✓ | |
| Sessions, persisted results, retention, delete, storage cap | ✓ | |
| Validation profile catalog, custom mapping upload check | ✓ (XXE-safe SAX parse and XSD check against its **own copy** of `CvMapping.xsd`; no JAXB) | ✓ (`SafeCvMappingLoader`'s JAXB load moves here; it parses the mapping again, with the same hardened parser, before use) |
| Examples (list, fetch by id) | ✓ | |
| Reading `mzTab-version` and choosing an engine | ✓ | |
| Parsing, structural and semantic validation, OLS lookups | | ✓ |
| JSON ⇄ TSV conversion, writing mzTab-M | | ✓ |
| Producing section data (the line-oriented JSON of §3 fact 3) | | ✓ |
| Serving sections (paging, sort, filter) | ✓ (`SectionReader`, unchanged) | |
| CV suggestions from mapping rules (milestone 2) | | ✓ |
| Editing operations that need the model: parse into a working copy, validate edits, export (milestone 2) | stores working copies | ✓ |

### 5.2 Repository layout and build

```
jmzTab-m-webapp/
  pom.xml                  # parent
  frontend/                # Angular workspace (unchanged)
  server/                  # the webapp: no jmzTab-m dependency
  engine-api/              # the engine contract: OpenAPI document + generated or hand-written DTOs, no jmzTab-m
  engine-core-src/         # the engine logic, as sources only (see below)
  engine-mztabm-2.0/       # Spring Boot app: engine-core-src compiled against jmzTab-m 1.0.x
  engine-mztabm-2.1/       # Spring Boot app: engine-core-src compiled against jmzTab-m 2.1.x
```

- **One source of engine logic, compiled and tested against each jmzTab-m line.** Both engine modules build the same engine sources against their own jmzTab-m version, so that an API difference between the lines is a compile error in that engine and not a runtime surprise. Version-specific code (if any) lives in the engine module that needs it, behind an interface of the shared sources. The mechanism (a shared source directory added by `build-helper-maven-plugin`, or another way that compiles the sources once per engine) is the builder's, as long as there is exactly one copy of the shared logic.
- The **same test suite** runs against each engine, with version-specific fixtures and expectations where the lines differ (e.g. factors kept by 2.0, dropped by 2.1).
- `server` must not depend, directly or transitively, on any `org.lifs-tools:jmztabm-*` artifact. A build rule (e.g. `maven-enforcer-plugin` `bannedDependencies`) and an ArchUnit test enforce it. The test bans `uk.ac.ebi.pride.jmztab2..`, `info.psidev.cvmapping..`, `uk.ac.ebi.pride.utilities.ols..` and `org.lifstools.mztab2..` **except** `org.lifstools.mztab2.server..`, the webapp's own API package, which stays where it is so that the springdoc group and the public OpenAPI document do not move [my call]. The webapp keeps a copy of `CvMapping.xsd` (identical in both jmzTab-m lines today) for its upload check; a test in each engine compares it with the engine's jar.
- **Images:** `lifs/mztab-validator-webapp` (unchanged name), `lifs/mztab-validator-engine-2.0`, `lifs/mztab-validator-engine-2.1` [my call], all built by Jib with the Java 25 base image and without devtools (#88/#89), all tagged with the webapp's project version.
- **jmzTab-m versions** are properties in the parent pom (`jmztabm20.version`, `jmztabm21.version`), so a 2.0 bug-fix release only bumps one property.
- **Release vs development builds (E11):**
  - the `release` profile runs `maven-enforcer-plugin` `requireReleaseDeps` over the modules it releases, and **does not include `engine-mztabm-2.1`** while its jmzTab-m is a SNAPSHOT. A release therefore produces `lifs/mztab-validator-webapp:<version>` and `lifs/mztab-validator-engine-2.0:<version>` only;
  - development builds (`feat/validator-spa`, later master SNAPSHOTs) build all modules and publish SNAPSHOT images of all three, never `latest` for the integration branch (as today);
  - once jmzTab-m 2.1.0 is released, `engine-mztabm-2.1` joins the release profile in a webapp release of its own.
- **Prerequisite for webapp 2.0.0:** a released jmzTab-m 1.0.x that contains everything the webapp needs (today's 1.0.8-SNAPSHOT features, e.g. the message `location` used by the linked messages). Releasing it (1.0.8) is a step of the 2.0.0 rollout, before the webapp release.

### 5.3 Engine API (internal)

Engines expose only an internal HTTP API under `/engine/v1`, described by an OpenAPI document in `engine-api`. The webapp calls it with a generated or hand-written client. **Required capabilities** (exact shapes are the builder's):

- **Info:** the mzTab-M versions the engine handles (e.g. `["2.0.0-M"]`), the jmzTab-m version and build, and limits (max upload size, max concurrent jobs).
- **Validate:** input: the mzTab-M file (TSV or JSON; the engine converts JSON itself), validation level, max messages, and optionally a mapping file (semantic validation runs if and only if one is given). Output, streamed:
  - the outcome (parsed or not), the declared mzTab-M version as the engine read it, and the messages;
  - when the file parsed: the section data in exactly the format `SectionReader` reads today (§3 fact 3: `metadata.jsonl` entries, and per table one JSON array of strings per row with the header first). The engine renders the file **as declared**: it no longer stamps a version into a file that has none (§5.4).
- **Convert:** JSON → TSV and TSV → JSON, with the messages of the conversion, as `/rest/v2/convert` and `/convertPlain` do today.
- **Milestone 2 (editing):** parse into working-copy rows, validate a working copy or part of it, export a working copy as TSV or JSON, CV suggestions for a field. These are named here so that the contract leaves room for them; they are specified with the editing API.

**Constraints:**

- **Streaming in both directions, bounded memory in the webapp.** Files reach 32 MB in practice and 64 MB by limit, and the largest SML has 846 × 1,990 cells. The webapp never holds a whole file or a whole section in memory to pass it on.
- **Every engine parameter is validated and constrained** (Jakarta Bean Validation; level enum, max messages range, size limits on every part), and values over a limit are rejected, never clamped — the same rules as the public API.
- **Bounded concurrency per engine:** each engine runs at most a configured number of jobs and answers "busy" (HTTP 503 with `Retry-After`) beyond it. The webapp retries asynchronous validations with backoff; synchronous REST calls pass "busy" on to the client as today's synchronous limiter does.
- **Timeouts:** the webapp sets a connect timeout and an overall job timeout per call (configurable; defaults sized for the largest real file with semantic validation).
- **Errors** are JSON with a stable machine-readable code (`unsupported_version`, `busy`, `invalid_request`, `file_too_large`, `internal`).

### 5.4 Version routing

1. The webapp reads the declared version from the upload. TSV: the existing `MzTabVersionDetector`. JSON: **new code**, a streaming JSON read (Jackson `JsonParser`) that stops at `metadata.mzTab-version` and never builds a model; today the version of a JSON upload comes from a full jmzTab-m parse, which leaves the webapp. Both are on the critical path, because routing depends on them; they get the shared fixtures of §7. The webapp never parses anything else.
2. Mapping from declared version to engine, configured (e.g. `engines.mztabm.routes`), initially:

   | Declared version | Engine |
   |---|---|
   | `2.0.0-M` (and any `2.0.x-M`) | 2.0 |
   | `2.1.0-M` (and any `2.1.x-M`) | 2.1 |
   | missing | the configured default (`engines.default-version`: `2.0` until 2.1 is established, then `2.1`) — see below |
   | `1.0…` | rejected as today (mzTab 1.0 retired) |
   | anything else | rejected: one ERROR message naming the declared version and the supported ones |

   **A route exists only where its engine is configured (E13).** In production (no 2.1 engine), a `2.1.x-M` file gets one ERROR message: "mzTab-M 2.1 is not yet supported by this validator. It is available for testing on the development validator: <configured URL>." The link is a property (`engines.unavailable-hint-url`), empty for self-hosters by default.

   The default version must be one whose engine is configured; the webapp refuses to start otherwise.

   **The default only chooses the engine [my call].** A file without `mzTab-version` is invalid in both versions (Error-2008), and stays so: it is not validated "as if" it declared the default version, and no assumed version is passed to the engine. The default decides which version's other checks run and report. The SPA and the editor also use it as the preselected version (§5.6, §5.7).
3. The engine's own reading of the version is returned with the result. If it differs from the webapp's (it should not), the result keeps the engine's and the webapp logs a warning.
4. **The result records:** declared version, engine version label (e.g. "mzTab-M 2.1, jmzTab-m 2.1.0-SNAPSHOT build abc123"), and whether the default was applied. The persisted result and `GET /rest/v2/validations/{id}` gain these fields; the old `mzTabVersion` field stays.
5. **Public OpenAPI document [my call].** Today `/validate`, `/convert` and `/convertPlain` describe their mzTab-M bodies with jmzTab-m's model classes (`@Schema(implementation = MzTab.class)`; about 25 of the 41 schemas in `frontend/openapi/mztab-m-api.json`). Without jmzTab-m, the webapp takes the model schemas from each jmzTab-m line's **OpenAPI document** (`api/src/main/resources/mzTab_m_openapi.yml`), a schema file, not Java classes, at build time, and publishes them side by side with a version prefix (e.g. `MzTab_2_0`, `MzTab_2_1`). The mzTab-M body of these endpoints becomes `oneOf` the two. On the wire nothing changes. Generated clients see new type names; this is noted in the release notes. A hand-written copy of the model in the webapp is not allowed (anti-goal), and an opaque `object` body is rejected because it loses the documented contract.
6. **Public synchronous endpoints** (`/validate`, `/validatePlain`, `/convert`, `/convertPlain`) route the same way. The request shapes do not change. `/validate` and `/validatePlain` may gain the optional validation-profile parameter of the SPA design; when given, rule E9 applies.

### 5.5 Validation profiles per version

- **New catalog entry `mztab-m-2.1-default` 1.0.0**: a copy of jmzTab-m 2.1.x's bundled `mzTab-M-mapping.xml`, with `mzTabVersions: ["2.1.0-M"]`. `mztab-m-2.0-default` stays the copy of 1.0.x's file. The two copies are separate files even while their content is identical.
- **Keeping them in step:** a test in each engine module compares the catalog copy with the mapping bundled in that engine's jmzTab-m jar and fails with a clear message when jmzTab-m's file changes. Updating the catalog then means adding a new profile **version** (catalog versions are immutable, SPA design §5.2).
- **Rule E9:** a catalog profile whose `mzTabVersions` does not include the file's version (or the default version applied to it) is rejected with a clear error (asynchronous: at creation if the version is known, otherwise as the validation's single ERROR message). A custom mapping file is used as given, for any version; the result says "custom".
- `GET /rest/v2/validation-profiles` returns `mzTabVersions` for each entry (it already exists in the model) and accepts an optional `mzTabVersion` filter. It lists only profiles for versions whose engine is configured, so production does not offer `mztab-m-2.1-default` [my call].
- A future LipidCompass profile (SPA design M15) declares its own versions in the same way.

### 5.6 SPA changes

**Validator form**
- When a file is chosen, the SPA reads the declared version from the first lines (TSV) or the start of the document (JSON) in the browser, with the same rules as the server, and shows it as a badge next to the file name ("mzTab-M 2.1"). No version → "no version declared · validated as 2.0" (the configured default from app info).
- The profile select lists only profiles for that version, plus "Custom mapping file" and "None". Its default is the version's default profile (`mztab-m-2.0-default` or `mztab-m-2.1-default`). Before a file is chosen, the select shows the default version's profiles.
- If the browser cannot read the version (e.g. an unreadable file), the server's routing decides and the result page shows the version used.

**Versions not available on this instance** (E13): the SPA learns the available versions from app info. In production, 2.1 examples and profiles are not listed; the version filter shows only 2.0; the About page and the form's version badge for a 2.1 file point to the development validator [my call].

**Examples page**
- Example files and mapping files are grouped by mzTab-M version with a version filter (All · 2.1 · 2.0) at the top [my call; exact control is the builder's]. Each entry shows a version badge.
- "Example mapping files" becomes **"Mapping files"**, built from two sources:
  - the validation profile catalog (each profile version downloadable as XML, with its versions and description), first;
  - the external draft mappings from `examples.properties` (`DRAFT` stays a visible status, e.g. the mzTab-L lipidomics draft), after them.
- **2.1 examples [my call]:** `example_study_variable_group.mztab` and `official-profile-{m,mf,mfe,ms}.mztab` from jmzTab-m `master`, by pinned commit URL (not the branch head), until HUPO-PSI publishes official 2.1 examples. "Validate" works as today, by example id.
- `examples.properties` gains an explicit `mzTabVersion` (`2.0.0-M`, `2.1.0-M`) per entry, replacing the free-text `version` for mzTab-M files. A mapping file's `version` stays free text (`2.0`, `DRAFT`). The existing `section` (`example`, `mapping`) stays. `GET /rest/v2/examples` returns `mzTabVersion`; the version filter uses it.

**Landing page examples card:** shows a few examples of the default version, with a link to the full, filterable list.

**Result page:** the header badge shows the mzTab-M version, the engine label in a tooltip or detail line, and "default applied" when the file declared no version.

**About page:** a short section on supported versions, which jmzTab-m line validates which, and that 2.0 receives bug fixes only once 2.1 is the default.

### 5.7 Editor (milestone 2)

The standalone design's editor is built for one mzTab-M version. With this design:

- **New file:** the user chooses mzTab-M 2.0 or 2.1 when creating a file; the default is the configured default version. **Existing file:** its declared version, shown and not editable.
- **Version-specific fields and tables come from the version's own reference, not from a hand-kept copy.** For example, 2.0 offers `study_variable[n]-factors` and 2.1 offers `study_variable_group[n]` instead. The editor learns these differences from a per-version description generated from that jmzTab-m line (its OpenAPI model `api/src/main/resources/mzTab_m_openapi.yml`, its mapping rules, or a descriptor endpoint of the engine). The mechanism is decided in the milestone-2 plan; generating the descriptions at build time from each line's OpenAPI document is the author's preferred option [my call].
- All parsing, validation, CV suggestions and export of a working copy go through the engine of the working copy's version. A working copy records its version; it cannot change.
- The milestone-2 server plan (`lipidcompass/docs/design/mztabm-editor/plans/standalone-m1-server.md`, to be revised anyway per SPA design §10) must be revised to route through the engines and to drop the webapp's direct jmzTab-m use.

### 5.8 Failure handling

- **Engine unavailable or timing out:** the validation finishes as FAILED with one ERROR message: "The mzTab-M 2.1 validator is temporarily unavailable. Please try again later." It is never left RUNNING. Synchronous endpoints answer 503.
- **Busy:** asynchronous validations are retried with backoff up to the job timeout, staying QUEUED in the meantime; synchronous calls get 503 with `Retry-After`.
- **Restart of the webapp during a call:** handled like today's interrupted validation (SPA design §5.2: reported as failed with a clear message).
- **Health:** the webapp's `/actuator/health` reports each engine as a component (`engine-2.0`, `engine-2.1`) without failing the webapp's own readiness, so that one engine outage does not stop the other version or the result pages. App info lists which versions are currently available, and the SPA shows a notice when one is not.
- **Startup order:** the webapp starts without engines and checks them lazily and periodically.

### 5.9 Deployment

- **Services per stack (E6, E10):**

  | Stack | Webapp | Engine 2.0 | Engine 2.1 |
  |---|---|---|---|
  | `/mztabvalidator` (production) | `2.0.0` release | `2.0.0` release (jmzTab-m 1.0.x release) | — |
  | `/mztabvalidator-dev` | `2.0.0-SNAPSHOT` (later SNAPSHOTs) | SNAPSHOT | SNAPSHOT (jmzTab-m 2.1.0-SNAPSHOT) |
  | LipidCompass | stays on 1.0.x until it opts in (SPA design §5.7); its engines come with that opt-in [my call] | | |

  Engines are attached only to the stack's internal network: no Traefik labels, no published ports.
- **`docs/release/2.0.0-deployment.md` is rewritten** for this layout as part of the work: new engine services in both compose patches, the jmzTab-m 1.0.8 release before the webapp release, and the engine images in the snapshot publish step. Engines are attached only to the stack's internal network: no Traefik labels, no published ports.
- **Addresses:** configured base URLs (`engines.mztabm.v2_0.url`, `…v2_1.url`) with defaults for the compose service names (`http://mztab-engine-2-0:8084`, `http://mztab-engine-2-1:8085`) [my call]. In the LipidCompass stack (Eureka), the URLs are still configured explicitly; service discovery is not required.
- **No authentication between webapp and engines [my call]:** they are reachable only on the internal network. If a stack ever needs it, a shared-secret header can be added without changing the contract.
- **Memory:** parsing moves from the webapp to the engines. Starting values [my call]: webapp 1024M, each engine 2048M container limit with `-XX:MaxRAMPercentage=75`, to be confirmed by the large-file check (§7). Capacity is not a constraint: the three swarm nodes have 128 GB each [decided].
- **Volumes:** only the webapp has one (unchanged). Engines are stateless (E5).
- **Changes in other repos** (docker-recipes-external compose files, lipidcompass-ansible) are prepared as PRs, as in `docs/release/2.0.0-deployment.md`; the project lead or ops merges and deploys them.
- **Local development:** a compose file (or `run-dev.sh` per module) starts both engines next to the webapp; the webapp's dev profile points at them.

---

## 6. Documentation

- **In the SPA:** the form's version badge and profile hints explain which rules apply; the About page section of §5.6; the examples page explains the version filter.
- **`docs/self-hosting/`:** the three services, their images and ports, the engine URL and timeout properties, the default-version setting, memory sizing, and that only the webapp needs a volume.
- **`docs/release/`:** the rollout additions (new images and services per stack).
- **Release notes:** 2.1 support, per-version validation, the 2.1 default profile, the new result fields.
- **`README.adoc`:** the new modules and how to run them locally.
- The project lead reviews the documentation before release.

---

## 7. Testing

- **Engines** (both, from the shared suite):
  - validate, convert and section output on 2.0 and 2.1 fixtures, including the largest real file (LCS-00050);
  - version-specific expectations where the lines differ (2.0 keeps `study_variable[n]-factors` through TSV → JSON → TSV; 2.1 reports Warn-2067 for factors in a 2.1 file);
  - parameter validation and rejection (over-limit sizes, invalid levels), busy behaviour, the hardened mapping parser;
  - the catalog-copy check of §5.5.
- **Webapp:**
  - routing table of §5.4, including missing, unknown and 1.0 versions, against stub engines (e.g. MockWebServer);
  - rule E9 (profile/version mismatch) and the version-filtered profile list;
  - engine unavailable, busy and timeout paths (no validation left RUNNING);
  - the existing REST contract tests keep passing, now against stub engines;
  - the enforcer rule and the ArchUnit test proving there is no jmzTab-m on the classpath.
- **Contract:** the engine OpenAPI document is the single source; a test fails when an engine's implemented API and the document differ.
- **Version detection:** one fixture set (TSV and JSON, version first, last, missing, 1.0, unknown, BOM, very large JSON with the metadata late) used by the webapp's TSV and JSON detectors and by the SPA's.
- **SPA:** unit tests for the browser-side version detection (the shared fixtures), the filtered profile select and the examples filter.
- **End to end (CI):** webapp plus both real engines: a 2.0 and a 2.1 file through upload → result → tabs; a 2.0 example and a 2.1 example; a profile/version mismatch; one engine stopped.
- **Large-file check:** LCS-00050 through the 2.0 engine, with the memory limits of §5.9, and the result page paging as in the SPA design.

---

## 8. Answered questions

Answered by the project lead on 2026-10-30:

1. **Release:** the engine split ships in 2.0.0, prioritizing mzTab-M 2.0; 2.1 is integrated early on the development validator only (E10).
2. **Capacity:** sufficient; three swarm nodes with 128 GB RAM each (E6).
3. **Dependencies:** releases depend only on released versions; development builds may use SNAPSHOTs (E11).
4. **2.0 → 2.1 conversion:** future work, also for the SPA editor component (E12, §11).

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Section data for the largest files is slow to stream from engine to webapp | Measure on LCS-00050 first; the section format stays line-oriented so it can be streamed straight into storage |
| More memory per stack than the nodes have | Sizing in §5.9 confirmed by the large-file check before rollout |
| The two jmzTab-m lines diverge in API so that shared engine sources no longer compile for one of them | Version-specific code behind interfaces in the engine module that needs it; the 2.0 line is frozen, so divergence mostly comes from 2.1 |
| Catalog mapping copies silently drift from jmzTab-m's bundled files | The catalog-copy test of §5.5 |
| An engine outage makes one version unusable | Health components, SPA notice, clear failure messages; the other version keeps working |
| Browser-side and server-side version detection disagree | Same rules and shared fixtures; the server's routing is authoritative and the result shows the version used |
| A SNAPSHOT jmzTab-m dependency reaches a release | `requireReleaseDeps` in the release profile; the 2.1 engine is not part of releases until jmzTab-m 2.1.0 is released (E11) |
| The webapp 2.0.0 release waits for the jmzTab-m 1.0.8 release | Release 1.0.8 early in the rollout; it contains only changes already merged on `1.0.x` |

---

## 10. Left to the builder

- Exact engine API shapes, the streaming format (multipart, NDJSON or similar) and the client (generated or hand-written).
- How the shared engine sources are compiled per engine (§5.2), as long as there is one copy.
- Ports, property names and timeouts (defaults sized by measurement).
- The SPA controls for the version filter and badges, within the design system of the SPA design §5.6.
- Retry and backoff parameters.
- Whether the browser-side detection reads a fixed number of bytes or lines, as long as it matches the server's rules.

## 11. Future work

- **Convert a 2.0 file to 2.1 [decided: later].** In the API (e.g. a conversion endpoint) and in the **SPA editor component** (e.g. "Upgrade to mzTab-M 2.1"), including mapping `study_variable[n]-factors` to `study_variable_group[n]`. Which engine performs it (the 2.1 engine reading 2.0 input, or a dedicated step that uses both) is decided then. Nothing in this design may make it harder: working copies record their version (§5.7), and the engine contract can gain an operation.
- **The 2.1 engine in releases** once jmzTab-m 2.1.0 is released (§5.2), followed by **switching the default version to 2.1** (`engines.default-version`) and freezing the 2.0 engine to bug fixes (E3).

