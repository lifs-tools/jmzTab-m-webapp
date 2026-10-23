# mzTab-M Validator SPA — Milestone 1 Design

**Date:** 2026-10-23
**Status:** Builder-checked; open questions answered (2026-10-23). Awaiting approval.
**Parent design:** `lipidcompass/docs/design/mztabm-editor/specs/standalone-editor-design.md` (2026-10-19, "the standalone design" below). This document changes its milestone order and specifies the new milestone 1 in detail. Everything in the standalone design that this document does not override still applies.

Decisions are tagged **[decided]** where the project lead decided them and **[my call]** where the author decided and the project lead can still correct it.

---

## 1. Intent

jmzTab-m-webapp is the public mzTab-M validator (apps.lifs-tools.org/mztabvalidator, and /mztabvalidator-dev). Its UI is server-rendered Thymeleaf with jQuery 3.6, Bootstrap 4.6 and DataTables. The standalone design decided to replace that UI completely with an Angular SPA, and to build a server-backed mzTab-M editor on top of it.

**This milestone ships the validator as an Angular SPA first [decided].** The editor follows in milestone 2. The SPA gets the LIFS look of lifs-tools.org, so the validator reads as part of the LIFS tool family.

### Why this order

- The validator is the part people use today. Rebuilding it first gives the new stack (multi-module build, Angular workspace, theme, REST endpoints, validation profiles) daily users before the much larger editor lands on it. The standalone design names "a sixth orphan nothing uses day to day" as an anti-goal; shipping the validator first keeps every step in use.
- The anti-goal "no half-retired Thymeleaf UI" is met naturally: the first SPA release replaces the whole UI at once.

### What changes in the standalone design

| Standalone design | Now |
|---|---|
| D8 [proposed]: milestone 1 = editing API + editor in a minimal SPA; milestone 2 = validator pages and Thymeleaf retirement | **[decided]** Milestone 1 = validator SPA + Thymeleaf and mzTab 1.0 retirement (this document). Milestone 2 = editing API (SQLite working copies) + editor library, element and webjar, plus LipidCompass adoption. |
| §5.7: the result page shows the file's sections "by the editor in `readonly` mode" | **[decided]** Milestone 1 uses a **simple read-only section viewer** in the SPA. The editor's read-only mode replaces it in milestone 2. |
| §5.4 validation profiles: built with the editing API | Moved into milestone 1 (the validator form needs them). |
| §5.1 multi-module layout, task 1 of `plans/standalone-m1-server.md` | Moved into milestone 1. The CI part of that task is already done (PRs #73, #76: JDK 25, wrapper from Maven Central, publish only from master). |
| `plans/standalone-m1-server.md` tasks 2–7, 9–11 (working copies, edits, located validation, export, CV suggestions) | Milestone 2. That plan must be revised before it is executed. |

---

## 2. Goals, non-goals, anti-goals

### Goals

1. An Angular SPA that reaches **feature parity** with every item of the current UI (standalone design §3.2b), except mzTab 1.0:
   - landing page with the validator form;
   - asynchronous validation with a progress state;
   - result page: summary, message table, read-only sections of the validated file;
   - delete a result;
   - examples, About, error and not-found pages;
   - retention notice;
   - LIFS branding and Matomo with consent.
2. **Validation profiles** (standalone design §5.4): a curated catalog plus a user-supplied mapping file, selectable in the form and in the new REST endpoints.
3. **The LIFS design system** (§5.6) applied throughout, aligned with lifs-tools.org.
4. **Results survive restarts and redeploys** for the advertised retention period (§3, fact 1).
5. **In the same release** [decided]: delete the Thymeleaf UI and all its dependencies, and retire mzTab 1.0 completely.
6. The existing public REST contracts keep working unchanged.

### Non-goals

- The editor, working copies, the editing API and SQLite (milestone 2).
- The custom element, webjar and npm packages (milestone 2).
- Validating a file from an arbitrary URL. The current UI cannot do it, and a public server fetching user-supplied URLs is an SSRF risk. (Examples are an exception, §5.3.)
- User accounts or login. The app stays anonymous (`WebSecurityConfig` permits all requests in every profile, commit 5df8aae).
- Translations. The current UI is English only; so is the SPA.
- Implementing `/convert` and `/convertPlain` (#71). They stay as they are until milestone 2, where the standalone design implements them.

### Anti-goals (failure even if it "works")

- A release in which Thymeleaf pages and SPA pages both serve parts of the UI, or Thymeleaf leftovers remain after the switch.
- A browser tab that freezes on the largest real files (standalone design §3.3: up to 32 MB, an SML table of 846 × 1,990).
- Validation results that differ from the reference implementation's. jmzTab-m stays the only parser and validator (D5).
- Broken links: bookmarks and links in papers to `/mztabvalidator/`, `/mztabvalidator/about` or `/mztabvalidator/result/{id}` stop working.
- A page that looks like a generic Material app instead of a LIFS tool.
- Breaking scripts that call `/rest/v2/validate` or `/rest/v2/validatePlain` (e.g. `lipidcompass-submissions/src/curation/validate.py`).

---

## 3. Current state (facts this design relies on)

1. **Validation results live only in memory.** `DefaultToolResultService` keeps them in a `ConcurrentHashMap<UUID, ToolResult>`. The uploaded files are stored on disk under `storage.location` and deleted after `minCleanupAge` days (default 7). A restart or redeploy loses every result while the page still promises 7 days.
2. **Validation runs asynchronously.** `POST /` stores the files and redirects to `/validate/{sessionId}`, which starts `ValidationService.runValidation(...)` and redirects to `/result/{sessionId}`. That page reloads itself every few seconds while the status is not final.
3. **The section views load the whole file.** `MzTabValidationService.parse(...)` → `MzTabMValidator.parse(...)` parses the file and turns every section into `List<Map<String,String>>` for one Thymeleaf render. DataTables then pages in the browser.
4. **The form** (`ValidationForm`): file, validation level (Info/Warn/Error), max errors, mzTab version (1.0 or 2.0-M), a "check CV mapping" toggle and an optional mapping-file upload (default: the bundled `mappings/mzTab-M-mapping.xml`).
5. **Uploaded mapping files are parsed safely** by `SafeCvMappingLoader` (DOCTYPE rejected, no external entities; commit 190f1cc).
6. **Examples** come from `examples.properties` (name, URI on GitHub HUPO-PSI/mzTab, description, version, focus, createdBy). The page only links to them.
7. **Analytics:** a client-side Matomo snippet (`html-head.html`) with `requireConsent`, tracker `//lifs-tools.org/matomo/`, site id from `ga.id`. Separately, `PiwikTracker` records server-side events through the same `ga.*` properties.
8. **Error handling:** `ValidationController` has `@ExceptionHandler`s for missing files, multipart errors (upload too large), `NoHandlerFoundException` and generic exceptions, all rendering Thymeleaf error views.
9. **Branding:** the footer shows the FZ Jülich logo (`static/img/fz-juelich-logo.png`) and the LIFS signet.
10. **Build:** single-module Maven, `maven.compiler.release` 24, Spring Boot 3.5.16, springdoc 2.8.17, jmzTab-m 1.0.7-SNAPSHOT. CI builds with JDK 25. Images: Jib (Zulu 24) in CI; `Dockerfile` on `eclipse-temurin:25-jre`.
11. **Deployments** (checked 2026-10-23):

    | Instance | Defined in | Image | Replicas | Storage |
    |---|---|---|---|---|
    | `/mztabvalidator` (production) | `docker-recipes-external/external/applications/docker-compose-mztabvalidator.yml` | `1.0.28` (pinned) | 2, Traefik sticky cookie `mztabvalidator-session` | volume on `/var/data/mztabvalidator` (NFS, shared by both replicas) at `/tmp/upload-dir` |
    | `/mztabvalidator-dev` | `…/docker-compose-mztabvalidator-dev.yml` | `1.0.30-SNAPSHOT` (floating) | 1 | volume on `/var/data/mztabvalidator-dev` (NFS) |
    | LipidCompass `mztab-validator` | `lipidcompass-ansible/roles/lipidcompass/templates/docker-compose-lipidcompass-infra.yml.j2` (private repo) | `1.0.30-SNAPSHOT` (floating) | 1 | none: container tmpdir, wiped on redeploy |

    CI pushes `<version>` and `latest` on every push to master (#73, #76), so both floating instances pick up whatever master holds at their next redeploy. `/var/data` is an NFS mount with readdir caching.

---

## 4. Decisions

| # | Decision | Status |
|---|---|---|
| M1 | Milestone order reversed: validator SPA first, editor second. | decided |
| M2 | Result sections are shown by a simple read-only viewer until the editor exists. | decided |
| M3 | Results page layout **A**: result header and banner, then tabs (Messages, Metadata, Summary, Features, Evidence). | decided |
| M4 | **Design system:** the LIFS tokens and components of §5.6, aligned with lifs-tools.org as the leading design. | decided |
| M5 | **App bar:** the "slim" option — the lifs-tools.org nav bar (blue, Inter, logo) with the app name. | decided |
| M6 | **Corners:** most surfaces square (0); only small boxes mildly rounded (2–3px). | decided |
| M7 | **Status colours** error/warn/info/ok as in §5.6. | decided |
| M8 | **FZ Jülich instead of ISAS** everywhere in the UI, branding and docs. | decided |
| M9 | Thymeleaf and mzTab 1.0 are removed in the same release that ships the SPA (from the standalone design: D11, D12). | decided |
| M10 | Angular **21** for the workspace. | my call |
| M11 | Results and section data are persisted in the storage directory, next to the uploaded files. | my call |
| M12 | Fonts and icons are self-hosted, not loaded from Google at runtime. | my call |
| M13 | New rollout path: `/mztabvalidator-dev` first, production after a parity check. | my call |
| M14 | **One replica per instance.** Production goes from 2 replicas to 1 with this release. Volumes may later be replaced by S3. | decided |
| M15 | The public catalog starts with `mztab-m-2.0-default` only. The LipidCompass profile is added once finalized, as a copy of the private authoritative file. | decided |

---

## 5. Design

### 5.1 Repository layout and build

The standalone design §5.1 layout, restricted to what milestone 1 needs:

```
jmzTab-m-webapp/
  pom.xml          # parent (packaging pom); groupId org.lifs-tools
  server/          # the Spring Boot app; the current src/ moves here (git mv, history kept)
  frontend/        # Angular workspace, built by frontend-maven-plugin (pinned Node and npm)
    projects/
      webapp/      # the SPA
  docs/
```

- The server keeps the artifact id `mztab-validator-webapp`, so image names and deployments do not change.
- The `frontend` module's build output is packaged so that the server serves it from `classpath:/static/` (e.g. the frontend module produces a jar of `dist/`, and the server depends on it). The exact mechanism is left to the builder.
- `projects/mztabm-editor`, `projects/mztabm-editor-element` and `webjar/` are **not** created in milestone 1. The workspace is laid out so that they can be added later without moving the SPA.
- A generated TypeScript client for the server's OpenAPI document (`/v3/api-docs/mztab-m-api`) is used by the SPA. The builder decides whether it is generated at build time from the running spec or from a committed spec file; either way, CI must fail when the client is stale.
- **Angular 21 [my call].** The milestone-2 editor library must be consumable by Angular 21 and 22 apps (standalone design §5.3), and LipidCompass stays on 21 for now. Building the workspace on 21 guarantees that; an upgrade to 22 is a later, separate change.
- Jib, the `Dockerfile`, the CI workflow and `README.adoc` follow the module move. CI keeps the PR/master split from #73/#76.

### 5.2 Server

#### Validation sessions

A validation keeps today's id: the session UUID under which the files are stored. Lifecycle:

1. **Create:** multipart upload of the mzTab-M file (TSV or JSON, ≤ 64 MB), validation level, max messages, and **either** a catalog validation-profile id, **or** an uploaded mapping file, **or** no profile (structural validation only). The response returns the id at once; validation runs asynchronously as today.
2. **Status and result:** the client polls by id until the status is final. The result carries:
   - the parameters: original file name, level, max messages, profile id and version or "custom", mzTab-M version read from the file;
   - status, start and end time, and expiry time (upload time + `minCleanupAge`);
   - counts per level;
   - the `ValidationMessage` list;
   - which sections exist and their sizes (metadata line count, SML/SMF/SME rows and columns).
3. **Sections:** see §5.2 "Read-only sections".
4. **Delete:** removes the files, the result and the section data at once.
5. **Expire:** the existing daily cleanup removes everything in the session directory, including the persisted result.

**Persistence [my call].** The result (status, parameters, messages, counts) is written as JSON into the session's storage directory and read back on demand. The in-memory map becomes at most a cache. An unfinished validation that is interrupted by a restart is reported as failed with a clear message, not left "running" forever. This rule relies on a **single replica** per instance (M14): the instance that reads a "running" result is the one that ran it.

**Storage stays behind `StorageService` [my call].** The persisted result, the section data and custom mapping files are read and written through `StorageService` (new slots or methods as needed), never through direct `java.nio.file` paths elsewhere. The volumes may later be replaced by S3 (project lead, 2026-10-23); with this rule that becomes a new `StorageService` backend, not a rewrite. Milestone 1 implements only the existing file backend.

**mzTab-M version.** It is read from the file's `MTD mzTab-version`, not chosen in the form (standalone design §5.2). A file that declares `1.0` (or otherwise is not mzTab-M) gets one clear error message saying that mzTab 1.0 is no longer supported.

#### Read-only sections

Requirements:
- metadata of any size, grouped by element type (e.g. `assay[1..1521]`, `sample[...]`), each group paged;
- SML, SMF and SME tables paged on the server, with sort and filter on any column, including `abundance_*` and `opt_*` columns;
- a column selection, so that the client never has to receive all 1,990 columns of a wide table at once;
- responses fast enough for interactive paging on the largest file in standalone design §3.3.

**Proposed approach [my call]:** when validation parses the file (it already does), the server also writes each section to the session directory as **jmzTab-m's own JSON** (one document for metadata, one line per row for each table). Section requests read, sort, filter and page from those files; a small bounded cache may keep the most recently used ones in memory. This needs no second parser (D5), survives restarts like the result, and stays well short of milestone 2's SQLite working copies. If the measurements in §8 show it is too slow, the builder may choose a different store, as long as the requirements above hold.

The section data is derived from the parsed model, so a file that cannot be parsed has no sections. The result page then shows only the messages, which is today's behaviour.

#### Validation profiles

Standalone design §5.4, catalog part, in milestone 1:

- **Catalog:** files plus an index in this repo. Each entry has an id, title, description/use case, mzTab-M version (2.0 or 2.1), profile version and the mapping XML. A merged PR is the approval. CI validates every catalog XML against jmzTab-m's `CvMapping.xsd`.
- **Initial entry [decided]:** only `mztab-m-2.0-default`, today's bundled `mzTab-M-mapping.xml`.
- **The LipidCompass profile comes later [decided].** `lipidcompass-submissions` is private, and its summary mapping is still being finalized. Once the LIFS team finalizes it, it is added to the public catalog as `lipidcompass-summary-2.0`. The authoritative file stays in `lipidcompass-submissions/config/validation/mzTab_2_0-M_summary-mapping.xml`; the catalog holds a **copy** of a finalized version (option b). Keeping the copy in step (a sync task or CI check) is part of that later change, not of milestone 1. Until then, LipidCompass passes its mapping as a custom upload.
- **Versions are immutable and kept [my call].** A catalog entry is identified by id **and** version; once merged, a version's XML never changes, and a change means a new version. The catalog stores one XML per id and version. The profile list shows the latest version of each id; requests may name a version and otherwise get the latest; results record the id and version used. This keeps the standalone design's promise that LipidCompass and the submission scripts resolve a profile "by id and version", and keeps old results reproducible.
- **Custom mapping file:** uploaded with the validation, parsed by `SafeCvMappingLoader`, validated against the XSD, stored in the session directory and deleted with it. A server property can disable custom uploads.
- **Semantic validation runs whenever a profile (catalog or custom) is chosen.** "No profile" means structural validation only. Semantic validation needs outbound access to EBI OLS; this is documented for self-hosters.
- The SPA's form defaults to `mztab-m-2.0-default` [my call], matching today's default mapping.

#### REST API

All new endpoints live under `/rest/v2` in `org.lifstools.mztab2.server.api` (or sub-packages), so the existing springdoc group publishes them. The API says "validation profile", never bare "profile" (that word already means the mzTab-M profile). **Required capabilities** (exact shapes are the builder's):

- create a validation (multipart, §5.2);
- get a validation's status and result;
- page, sort and filter a section; get metadata by element group;
- delete a validation;
- list validation profiles (id, title, description, versions);
- list examples (from `examples.properties`), and start a validation of an example by its id (§5.3);
- app info for the SPA: version and build, retention days, upload limit, whether custom mapping uploads are allowed, and the Matomo site id and URL (only if configured).

**Unchanged:** `/rest/v2/validate` and `/rest/v2/validatePlain`, including the `semanticValidation=false` default. They may gain an optional validation-profile parameter; nothing else changes. `/convert` and `/convertPlain` are untouched (non-goal).

#### Routing, errors and security

- `/rest/**`, `/v3/api-docs/**`, the Swagger UI and `/actuator/**` behave as today. Errors under `/rest/**` are JSON only (`ErrorResponse`), including 413 for uploads that are too large.
- Every other non-asset GET is forwarded to the SPA's `index.html`, so client-side routes and old URLs (§5.5) work on reload.
- `WebSecurityConfig` stays as it is (permit all, CSRF and CORS disabled). The SPA and API share one origin.

#### What is deleted in this release

- `ValidationController`, `templates/`, `static/css`, `static/js` (DataTables), `i18n/messages.properties` (texts move into the SPA), `ValidationForm` (or what remains of it outside the REST layer), `Page` if unused.
- Dependencies: `spring-boot-starter-thymeleaf` (and the layout dialect), `org.webjars:jquery`, `org.webjars:bootstrap`, the webjars locator, `ognl` if only Thymeleaf needed it.
- mzTab 1.0: `EbiValidator`, the `MZTAB_1_0` branch of `MzTabValidationService` (and the version switch if nothing else needs it), `uk.ac.ebi.pride:jmztab-modular-model` / `jmztab-modular-util`, the `jmztab.version` property.
- The images that the SPA does not reuse.

`PiwikTracker` (server-side events) stays.

### 5.3 SPA pages

All texts in English. The pages follow the approved mockups (§5.6).

**Landing page (`/`)**
- Hero: eyebrow "LIFS Tools · Report & share", title "Validate your mzTab-M files", lead text, retention note.
- Validator card:
  - file drop zone with a "Browse files" button (`.mzTab`, `.mztab`, `.txt`, `.tsv`, `.json`; upload limit shown);
  - validation level and max messages;
  - validation profile select (catalog entries plus "None — structural validation only"), with each profile's description, **or** a mapping-file upload (hidden if the server disallows it);
  - the Validate button and a note that semantic checks query EBI OLS.
- Side cards: example files (§ below), and "For scripts" links (REST API / Swagger UI, pymzTab-m, rmzTab-m).

**Validation in progress:** the result page shows a progress state (indeterminate bar, the file name and parameters) and polls until the status is final. It never reloads the whole page.

**Result page (`/result/{id}`, layout A)**
- Header: eyebrow "Result · kept until <date>", the file name, badges (mzTab-M version, validation profile and version, level and max messages), actions "Download report" (the messages as CSV or JSON) [my call] and "Delete now" (with confirmation).
- Banner (callout): passed / passed with warnings or info / failed, with the counts per level. A failed parse says so.
- Tabs:
  - **Messages (n):** a table with level, line, category and message; text filter, level filter and category filter; paged.
  - **Metadata:** grouped key/value lists by element type, large groups paged.
  - **Summary / Features / Evidence:** server-paged tables with sort, filter and a column selection; tabs for absent sections are shown disabled with "—".
- An unknown or expired id shows a not-found state that explains the retention period and links back to the form.

**Examples:** the list from `examples.properties` (name, focus, description, creating software, version) with a download link and a "Validate" action. The action validates the example **by its id** [my call]; the server fetches only the URI configured for that id, never a client-supplied URL.

**About (`/about`):** the current About content updated: mzTab-M, jmzTab-m, the validation levels, validation profiles, retention and privacy, citation, mzTab 1.0 retirement notice, links. FZ Jülich, not ISAS (M8).

**Errors:** a not-found page for unknown routes and a generic error state for failed requests, in the style of the LIFS error pages (eyebrow, title, short explanation, tips, buttons).

### 5.4 Analytics and privacy

- Matomo stays client-side with consent required (`requireConsent`), tracker `//lifs-tools.org/matomo/`, site id and URL from the app-info endpoint. If `ga.id` is empty, no tracker loads.
- Page views are tracked on SPA route changes.
- The footer has an "Analytics" link that opens the consent choice (opt in / opt out); the choice is remembered by Matomo's own consent mechanism.
- The footer links to the lifs-tools.org Imprint & Privacy Policy, Impressum & Datenschutzerklärung and Terms of Service pages.

### 5.5 URLs

Under the existing `/mztabvalidator` context path:

| Path | Behaviour |
|---|---|
| `/` | landing page |
| `/result/{id}` | result page (same ids as today) |
| `/about` | About |
| `/examples` | examples (also shown on the landing page) |
| `/validate/{id}` | old intermediate URL: redirects to `/result/{id}` |
| `/validate/{id}/delete` | old delete URL: shows the result page with the delete confirmation open; never deletes on GET |
| anything else | SPA not-found page |

The REST API, OpenAPI document and Swagger UI paths do not change.

### 5.6 Design system

Leading design: **lifs-tools.org** as deployed (Gantry 5 / Bostan theme plus `lifs-landing.css`, checked against the live site on 2026-10-23). Approved mockup: `docs/superpowers/specs/2026-10-23-validator-spa/mockup.html`.

**Tokens** (CSS custom properties on `:root`, prefixed `--lifs-*`):

| Group | Tokens |
|---|---|
| Content colours (from `lifs-landing.css`) | primary `#16628F`, sky `#3C9BD6`, site-blue `#5897BF` (buttons), site-blue-dark `#468BB7` (button hover), rose `#98457A` (eyebrow accent only), sky-ink `#1F6FA5`, ink `#16304A` (headings), slate `#46596B` (body), grey `#5A6C7D` (labels), paper `#FAFCFD`, mist `#EDF3F8`, tint `#E4EFF7`, line `#D6E2EA` |
| Chrome colours (Bostan theme) | nav `#3D84A8` with white text (hover `rgba(255,255,255,.8)`), footer text `#677180` (hover `#3D84A8`), copyright band `#414856` with white text |
| Status (new, approved) | error `#B42318` on `#FCEDEB`; warn `#8A5A00` on `#FFF4DC`; info = primary on tint; ok `#1F7A4D` on `#E6F4EC` |
| Fonts | Inter (nav bar and footer only), Space Grotesk 600 (headings), IBM Plex Sans 400/500/600 (text, forms, tables), IBM Plex Mono 400/500 (eyebrows, labels, codes, counts) |
| Corners | 0 for buttons, cards, fields, tables, tabs, callouts and the app bar; 2px for badges and level chips; 3px for small tiles |

Rose is the brand accent and is never used for errors.

**Components** (Angular Material restyled through its M3 theme and system tokens, plus a few own components):
- **App bar:** the lifs-tools.org nav bar: `#3D84A8`, height about 72px, the LIFS word-and-mark logo (`LIFS-bildmarke`, max 50px high), the app name "mzTab-M Validator" with "LIFS Tools" below it, uppercase Inter nav items (Validate, Examples, REST API, About, LIFS Tools ↗) and a GitHub icon. Collapses to a menu on narrow screens.
- **Hero:** eyebrow, Space Grotesk title, lead text; a 1px `line` rule below.
- **Buttons:** square, 14px text on a 38px line, 18px side padding. Filled (site-blue, hover site-blue-dark), outlined (site-blue border and text, filled on hover), text, and a danger outline (error colour).
- **Fields:** square outlined fields with floating labels; focus border primary, 2px.
- **Cards:** 1px `line` border, white or paper background, no shadow, a mono uppercase card title.
- **Badges and level chips:** mono 10–10.5px, 2px radius; level chips use the status colours.
- **Tabs:** text tabs with a 2px primary underline for the active tab and a mono count.
- **Tables:** mono uppercase header on paper, 1px row rules, hover tint.
- **Callout:** square, 4px left border in the status colour, status background.
- **Footer:** partner logos (Uni Wien, MPI-CBG, FZ Borstel, Forschungszentrum Jülich), "With funding from the:" BMFTR and BMBWF, the legal links and the Analytics link in Inter `#677180`, then the `#414856` copyright band with the LIFS signet, "© 2016 - <year> LIFS Tools Consortium" and "Top".

**Theme mapping:** an Angular Material M3 theme with the primary palette built from `#16628F`, shape tokens at 0 (with the 2–3px exceptions above), brand typography Space Grotesk and plain typography IBM Plex Sans. The `--lifs-*` tokens are the source; Material's `--mat-sys-*` values are derived from them. In milestone 2, the editor's `--mztabm-*` tokens (standalone design §5.5) take their defaults from the same `--lifs-*` tokens.

**Assets [my call]:** fonts bundled from their OFL-licensed npm distributions (e.g. `@fontsource`), Material Symbols self-hosted, and logos copied from lifs-tools.org into the SPA's assets. No font, icon or script CDN at runtime.

**Accessibility:** WCAG 2.1 AA contrast for text (the palette's grey is the minimum for small text, as on the site), visible focus outlines, keyboard-operable tabs, tables and dialogs, and `prefers-reduced-motion` respected.

### 5.7 Deployment and rollout

- **Persistent storage is part of milestone 1's definition of done.** Results are now persisted (M11). The public stacks already mount volumes (§3, fact 11); LipidCompass's `mztab-validator` service gets one in lipidcompass-ansible.
- **One replica (M14).** The production compose file goes from `replicas: 2` to `replicas: 1` in the same change that deploys 2.0.0. The sticky-cookie labels can stay; they become irrelevant.
- **Version and branches [my call]:**
  - Milestone 1 is released as **2.0.0**: the UI is replaced and mzTab 1.0 support is removed, even though the REST contracts stay compatible.
  - The work lands on an integration branch (`feat/validator-spa`) through reviewed PRs, and is merged to master once, for the release. Master stays on 1.0.x until then, so the floating `1.0.30-SNAPSHOT` images used by `-dev` and LipidCompass never carry an in-between state.
  - CI publishes `2.0.0-SNAPSHOT` images from the integration branch (an addition to the #73/#76 workflow, e.g. a manually triggered job), never `latest`.
- **Rollout [my call]:** `/mztabvalidator-dev` switches to the `2.0.0-SNAPSHOT` image first. Production switches to `2.0.0` only after a parity check against the list in §2 goal 1, using the real files from standalone design §3.3. There is no release in which production serves both UIs. LipidCompass stays on 1.0.x until it opts in.
- **Changes in other repos [my call]:** the implementation prepares them as PRs (the `docker-recipes-external` compose files, the lipidcompass-ansible volume); the project lead or ops merges and deploys them.
- Operational limits from the standalone design stay: 64 MB uploads, the daily cleanup. A configurable total-storage cap that refuses new validations when reached is added.
- The SPA determines its base path at runtime, since the same image serves `/mztabvalidator`, `/mztabvalidator-dev` and LipidCompass's `/mztab-validator`.

---

## 6. Documentation

- **In the SPA:** the About page (§5.3) and short inline help on the form (levels, profiles, custom mapping files, retention).
- **`README.adoc`:** the new module layout, how to build and run (including the frontend), the self-hosting requirements (outbound OLS access, `storage.location` volume, retention, analytics properties), and the Docker image name `lifs/mztab-validator-webapp` (the README still says `isas/…`).
- **Catalog page:** a list of the validation profiles and their use cases, generated from the catalog index (in the SPA or the README; builder's choice).
- **Release notes:** the new UI, the retirement of mzTab 1.0, and unchanged REST contracts.
- The project lead reviews the documentation before the release.

---

## 7. Testing

- **Server:** MockMvc tests for every new endpoint, including:
  - profile choice (catalog / custom / none) and custom-upload rejection;
  - results surviving a restart (persisted result reloaded);
  - section paging, sorting, filtering and column selection on a wide fixture;
  - delete;
  - the old-URL routing and SPA forwarding;
  - JSON errors under `/rest/**` (413 included).
- **Unchanged contracts:** the existing `ValidateApiIntegrationTest` and `DefaultProfileAccessIntegrationTest` keep passing.
- **Catalog:** every catalog XML validates against `CvMapping.xsd` in CI.
- **SPA:** unit tests for components and services (test runner: builder's choice).
- **End to end:** browser tests against the packaged jar covering upload → result → tabs → delete, an example validation, a custom mapping upload, the not-found page and an old URL. They run in CI.
- **Large-file check:** the largest real file (LCS-00050: 1,521 assays, 846 × 1,990 SML) validates, and its result page pages through the summary without freezing the browser.

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| Section JSON for the largest files is slow to write or page | Measure on LCS-00050 early; switch the store if needed (§5.2) |
| The multi-module move breaks Jib, the Dockerfile, CI or the release pipeline | Do the move first, as its own change, with the full CI run and an image build |
| Parity gaps discovered in production | Dev-first rollout and the parity checklist (§5.7) |
| Results are lost on redeploy because a deployment lacks the volume | Volume in both deployments is part of the definition of done |
| Disk filled by anonymous uploads (now that results persist) | Total-storage cap; existing daily cleanup |
| Visual drift from lifs-tools.org | Tokens copied from the live site; review against the approved mockup |
| Third-party scripts depend on `/validate/{id}` or other Thymeleaf URLs | Old URLs redirect (§5.5); the REST API is unchanged |

---

## 9. Left to the builder

- Exact REST endpoint shapes, payloads and paging parameters.
- How the frontend build output reaches the server jar.
- Whether the TS client is generated from the running spec or a committed spec file.
- The SPA's internal structure, state management and test runner.
- The table component for wide sections (Material table with CDK virtual scrolling, or another MIT grid), as long as the client never renders the whole table.
- The store behind section paging, if the proposed JSON files do not meet the requirements.
- Responsive breakpoints and the narrow-screen menu.

## 10. Open items outside this design

- **Copyright headers:** 38 Java files carry "Copyright … Leibniz Institut für Analytische Wissenschaften - ISAS e.V.". They are legal notices and stay unchanged unless the project lead decides otherwise.
- **`plans/standalone-m1-server.md`** (lipidcompass) must be revised for milestone 2 before it is executed.
