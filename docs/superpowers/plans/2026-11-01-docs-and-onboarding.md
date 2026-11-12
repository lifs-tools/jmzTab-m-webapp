# Documentation and Getting Started with mzTab-M Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One Documentation area (`/docs`) that folds in the guide, the problems page and the About page, explains mzTab-M with bundled teaching files as chapters and as a tour in the real viewer, and introduces the format on the landing page.

**Architecture:** The docs are Angular pages under a `DocsShellComponent` (side navigation from one tree, floating "On this page", previous/next). The teaching files and their step lists are classpath resources of the server, offered as examples of section `tutorial`, with two new read-only endpoints (`/content`, `/tour`). The tour validates a teaching file through the existing example flow and runs on the result page, which shows a tour panel instead of the messages and drives the viewer with each step's target exactly as a message does.

**Tech Stack:** Spring Boot server (Java 25, Jackson), Angular 21 standalone components with signals, Angular Material, Vitest (`@angular/build:unit-test`), Playwright with axe, ng-openapi-gen.

**Spec:** `docs/superpowers/specs/2026-11-01-docs-and-onboarding-design.md`

## Global Constraints

- Work on branch `docs/onboarding-design` (worktree `scratchpad/wt-onboarding`, rebased on `feat/validator-spa` at `571159f`). Commit per task; messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never push.
- The webapp has no jmzTab-m dependency and never parses mzTab-M semantically. Server checks on teaching files are textual (lines, tab-separated fields, line prefixes) only.
- Every new request parameter is validated and constrained; invalid input is rejected with 400, never clamped. Example ids in paths must match `[A-Za-z0-9._-]{1,100}`.
- Released dependencies only; no new dependency (no Markdown renderer: docs are Angular templates).
- Labels, buttons and messages named in the docs come from the shared constants (`core/quick-fix-labels.ts`, `core/editor-labels.ts`), as in today's guide.
- Anti-goals from the spec: the landing section never pushes the validator form down and is not a modal; `/guide`, `/problems`, `/about` and their `#…` anchors keep working; a bundled example never teaches something the validator rejects (no errors, no warnings on its engine).
- Teaching files derived from HUPO-PSI examples are CC BY 4.0: each file's first `COM` line names its original path in HUPO-PSI/mzTab-M and says it was adapted; the docs link the originals.
- mzTab-M 2.0 is frozen: `engine-mztabm-2.0` gets tests only.
- New Java files carry the Apache header with `Copyright 2026 LIFS Tools Consortium.` like their neighbours.
- In-app section links use `routerLink` with `fragment`, never a bare `href="#…"` (the `<base href>` is the context path, so `#x` would resolve to the start page).
- Shell is zsh: quote globs (`--include='**/x.spec.ts'`) and `"====="` strings; write `${VAR}` before a colon.
- A foreign webapp holds port 8083. Run the local stack as `WEBAPP_PORT=8093 ENGINE20_PORT=8194 ENGINE21_PORT=8195 STORAGE_LOCATION="$(mktemp -d)" scripts/run-dev-stack.sh --build` (in the background) and the e2e tests with `E2E_BASE_URL=http://localhost:8093/mztabvalidator/`; never stop the process on 8083.
- Documentation is part of the feature: every task updates the docs its change affects, and the last task requests the lead's review of all new pages.

**Commands used below**

- Frontend unit tests (from `frontend/`): `node/node node_modules/.bin/ng test webapp --no-watch --include='<glob>'`. In a fresh worktree, symlink `node_modules` and `node` from the main checkout and copy the generated `projects/webapp/src/app/api` (gitignored) before the first run; regenerate it with `npm run generate:api` after an OpenAPI change.
- Server tests: `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.
- OpenAPI refresh: `./mvnw -B -pl server -am test -Dfrontend.skip=true -Dtest=OpenApiSpecExportTest -Dopenapi.update=true -Dsurefire.failIfNoSpecifiedTests=false`, then `cd frontend && npm run generate:api`.
- Engine tests: `./mvnw -B -pl engine-mztabm-2.0 -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false` (same for `engine-mztabm-2.1`).

## Review Focus

1. **An instance with only the 2.0 engine** (the production rollout): the 2.1 tour, the study design "Try it" and the tour's version switch are hidden; chapter pages and the versions page still render, because file content and steps do not depend on an engine. Tests: Task 6 `renders the chapter when its example is not offered for validation`, Task 7 `hides the version switch when the counterpart is not offered`.
2. **A tour link opened after its result expired** (reload, bookmark, shared link): the tour validates the example again and returns to the same step instead of showing "not found". Test: Task 7 `restarts an expired tour at the same step`.
3. **Section links under a context path** (`/mztabvalidator/`): table-of-contents links, redirects and deep links keep the context path and the fragment, and land on the section, also on first load. Tests: Task 1 `toc links keep the route and fragment`, `redirects /guide#guide-fixing to /docs/fixing#guide-fixing`; Task 8 e2e `opens a deep link to a docs section under the context path`.
4. **Narrow screens (< 1100 px)**: the docs navigation collapses into "Contents", "On this page" becomes a collapsible list, and the tour panel works in the single-pane result layout (it sits above the tabs and switches to the step's tab). Tests: Task 1 `collapses the side navigation below 1100 px`, Task 7 `shows the tour panel above the tabs in the single-pane layout`.
5. **Unexpected ids on the new endpoints**: content or tour of a remote example answers 404 and never fetches anything; an id outside the pattern answers 400; a `classpath:` URI outside `tutorial/` is refused. Tests: Task 4 `contentOfRemoteExampleIs404WithoutFetching`, `idOutsidePatternIs400`, `classpathOutsideTutorialIsRefused`.

---

## File Structure

```
server/src/main/java/org/lifstools/mztab2/server/api/validation/
  ExamplesApiController.java        modify: DTO fields, /content, /tour, id pattern
  ExampleFetcher.java               modify: classpath:tutorial/ branch
  TutorialCatalog.java              create: loads bundled files and tours, validates them at startup
  TourSteps.java                    create: pure textual checks of a tour against its file
  TourDto.java                      create: TourDto, TourStepDto, TourTargetDto records
  AppInfoApiController.java         modify: submission template URLs (Task 3)
server/src/main/java/org/lifstools/mztab/validator/webapp/
  domain/ExampleFile.java           modify: tour, originalUri
  DocsProperties.java               create (Task 3)
server/src/main/resources/
  examples.properties               modify: tutorial entries
  application.properties            modify: docs.* defaults
  tutorial/teaching-lipidomics-2.0.mztab, -2.1.mztab, study-design-2.1.mztab
  tutorial/*.tour.json
frontend/projects/webapp/src/app/
  app.routes.ts, app.config.ts      modify
  core/page-titles.ts               modify
  core/tutorial.store.ts            create (Task 6)
  core/tour.service.ts              create (Task 7)
  shell/app-bar.*, shell/site-footer.*   modify
  pages/docs/
    docs-tree.ts                    tree, order, neighbours, titles
    docs-redirects.ts               keepFragment, aboutRedirect
    docs-shell.component.*          layout
    docs-toc.component.*, active-section.ts
    docs-home.page.*                /docs
    getting-started.page.*          Task 3
    format-chapter.page.*, excerpt.ts, excerpt.component.*   Task 6
    versions.page.*, validating.page.*, service-about.page.*  Task 2 (versions extended in Task 8)
    fixing.page.*                   moved from pages/guide
    problems.page.*                 moved from pages/problems
    integration.page.*              Task 8
  pages/landing/about-mztabm.component.*   Task 3
  pages/result/tour-panel.component.*      Task 7
engine-mztabm-2.0/src/test/java/.../TutorialExamplesTwoZeroTest.java   Task 5
engine-mztabm-2.1/src/test/java/.../TutorialExamplesTwoOneTest.java, TwoOneChangesTest.java   Tasks 5, 8
frontend/e2e/docs.spec.ts, tour.spec.ts
```

`pages/guide`, `pages/problems` and `pages/about` are removed; their files move into `pages/docs` with their specs.

---

### Task 1: Docs shell, tree, menu, redirects for guide and problems

**Files:**
- Create: `pages/docs/docs-tree.ts`, `pages/docs/docs-redirects.ts`, `pages/docs/docs-shell.component.{ts,html,scss,spec.ts}`, `pages/docs/docs-toc.component.{ts,html,scss,spec.ts}`, `pages/docs/active-section.ts` (+ `.spec.ts`), `pages/docs/docs-home.page.{ts,html,scss,spec.ts}`, `pages/docs/docs-tree.spec.ts`, `pages/docs/docs-redirects.spec.ts`
- Move: `pages/guide/guide.page.*` → `pages/docs/fixing.page.*` (class `FixingPage`), `pages/problems/problems.page.*` → `pages/docs/problems.page.*` (keep class `ProblemsPage`)
- Modify: `app.routes.ts`, `app.config.ts`, `core/page-titles.ts`, `shell/app-bar.component.{ts,html,scss,spec.ts}`, `shell/site-footer.component.{html,spec.ts}`, `pages/edit/edit.page.html:140`, `docs/release-notes/2.0.0.md` (the `/problems` and `/guide` sentence)

(Paths under `frontend/projects/webapp/src/app/` unless absolute.)

**Interfaces:**
- Produces:
  - `export interface DocsNode { readonly path: string; readonly title: string; readonly children?: readonly DocsNode[] }`
  - `export const DOCS_HOME: DocsNode` = `{ path: '/docs', title: 'Documentation' }`
  - `export const DOCS_TREE: readonly DocsNode[]` (chapters in §5.1 order; after this task: Fixing a file `/docs/fixing`, Common problems `/docs/problems`; later tasks insert their nodes at their §5.1 positions)
  - `export function docsOrder(): readonly DocsNode[]` (home first, then depth-first)
  - `export function docsNeighbours(path: string): { prev: DocsNode | null; next: DocsNode | null }`
  - `export function docsTitle(path: string): string | null`
  - `export function keepFragment(target: string): RedirectFunction`
  - `export function activeSection(sections: readonly { id: string; top: number }[], threshold: number): string | null` — the last section whose `top <= threshold`, else the first section's id, else null
  - `DocsShellComponent` (selector `app-docs-shell`), route `docs` with children; `DocsTocComponent` (selector `app-docs-toc`, input `container: HTMLElement`)
  - `pageTitle('/docs/…')` returns `mzTab-M Validator · <docsTitle>`
  - Ids added to the guide's `h3`s (exact values below), which later tasks and the guidance may link to.

- [ ] **Step 1: Write the failing tests**

`docs-tree.spec.ts`:
```ts
it('orders home first, then the chapters', () => {
  expect(docsOrder().map((n) => n.path)).toEqual(['/docs', '/docs/fixing', '/docs/problems']);
});
it('gives neighbours in tree order', () => {
  const n = docsNeighbours('/docs/fixing');
  expect(n.prev).toBe(DOCS_HOME);
  expect(n.next?.path).toBe('/docs/problems');
  expect(docsNeighbours('/docs').prev).toBeNull();
  expect(docsNeighbours('/docs/problems').next).toBeNull();
});
it('titles a docs path', () => expect(docsTitle('/docs/problems')).toBe('Common problems'));
```
`active-section.spec.ts`: `activeSection([{id:'a',top:-40},{id:'b',top:30},{id:'c',top:600}], 100)` is `'b'`; with all tops above 100 → the last id; with all below → `'a'`; empty → `null`.

`docs-redirects.spec.ts` (RouterTestingHarness with the app routes):
- `redirects /guide#guide-fixing to /docs/fixing#guide-fixing`
- `redirects /problems to /docs/problems` and keeps `#unknown-cv-term` when given

`docs-shell.component.spec.ts`:
- `marks the current page in the side navigation` (`aria-current="page"` on the `/docs/fixing` link when routed there)
- `shows previous and next links in tree order`
- `collapses the side navigation below 1100 px` (BreakpointObserver stub delivering `matches: false` for `(min-width: 1100px)` after creation: a "Contents" button with `aria-expanded="false"` replaces the list; one `toSignal` off the single query)

`docs-toc.component.spec.ts`:
- `lists the h2 and h3 sections with ids` (container with `h2#a`, `h3#b`, `h2` without id → two entries)
- `toc links keep the route and fragment` (link `href` ends with `/docs/fixing#a` with `<base href="/mztabvalidator/">` in the test document)
- `marks the section in view` (stub `getBoundingClientRect` tops, dispatch `scroll`, after the next animation frame the second entry has `aria-current="location"`)
- `is a collapsible list below 1100 px` (BreakpointObserver stub `matches: false` → a `details` element titled "On this page", closed)

`fixing.page.spec.ts` (moved guide spec, all existing assertions kept) plus:
- `every section heading has an id` (all `h2, h3` have a non-empty `id`)

`app-bar.component.spec.ts`:
- `offers a Documentation menu with the chapters and All documentation` (menu items' texts equal `['All documentation', ...DOCS_TREE titles]`)
- `marks Documentation active on docs pages` (router at `/docs/problems` → trigger has class `nav__item--active`)
- existing About item assertions stay for now (Task 2 removes About)

`site-footer.component.spec.ts`: delete the two help-link tests; `shows the legal links opening in a new tab` expects `['Imprint & Privacy Policy', 'Impressum & Datenschutzerklärung', 'Terms of Service']`.

- [ ] **Step 2: Run them and see them fail**

Run: `node/node node_modules/.bin/ng test webapp --no-watch --include='projects/webapp/src/app/{pages/docs,shell}/**/*.spec.ts'`
Expected: FAIL (modules not found; footer list still has the help links).

- [ ] **Step 3: Implement**

- `app.routes.ts`: `docs` → `DocsShellComponent` with children `''` (`DocsHomePage`), `fixing` (`FixingPage`), `problems` (`ProblemsPage`), each `title: pageTitle('/docs/…')`; `guide` → `redirectTo: keepFragment('/docs/fixing')`; `problems` → `redirectTo: keepFragment('/docs/problems')`. `keepFragment` returns `inject(Router).parseUrl(target + (fragment ? '#' + fragment : ''))`.
- `app.config.ts`: `provideRouter(routes, withInMemoryScrolling({ anchorScrolling: 'enabled', scrollPositionRestoration: 'enabled' }))`; docs headings get `scroll-margin-top` equal to the app bar height.
- Guide `h3` ids, in order: `guide-select`, `guide-edit-value`, `guide-remove-line`, `guide-raw-line`, `guide-quick-fix`, `guide-duplicate-column`, `guide-undo`, `guide-checking`. The guide's `routerLink="/problems"` becomes `/docs/problems`; `edit.page.html:140` links `/docs/fixing`.
- The fixing and problems pages drop their own hero; the shell renders the page title (`docsTitle`) as `h1`. The docs home for now lists the chapters (cards from `DOCS_TREE`); Task 2 adds its content.
- App bar: `NavItem` gains `menu?: readonly NavItem[]`; Documentation sits between Examples and REST API; its trigger is a `button` with `matMenuTriggerFor`, active when the URL path starts with `/docs` (a `toSignal` of `NavigationEnd`). In the mobile menu, a "Documentation" heading (non-interactive) followed by the same links.
- Footer: remove the two help links.
- `page-titles.ts`: paths starting with `/docs` use `docsTitle`.
- Release notes: the guidance bullet's last sentence names the Documentation menu, `/docs/problems` and `/docs/fixing`, and says the old addresses redirect.

- [ ] **Step 4: Run the tests and see them pass**

Run the Step 2 command, then the whole webapp suite: `node/node node_modules/.bin/ng test webapp --no-watch`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A frontend/projects/webapp/src/app docs/release-notes/2.0.0.md
git commit -m "Docs: Documentation menu and shell; guide and problems move to /docs with redirects"
```

---

### Task 2: Fold the About page into the documentation

**Files:**
- Create: `pages/docs/validating.page.*`, `pages/docs/versions.page.*`, `pages/docs/service-about.page.*` (each with `.spec.ts`)
- Modify: `pages/docs/docs-home.page.*`, `pages/docs/docs-tree.ts`, `pages/docs/docs-redirects.ts` (+ spec), `app.routes.ts`, `shell/app-bar.component.{ts,spec.ts}`, `core/page-titles.ts`
- Delete: `pages/about/*` (its spec assertions move to the new specs)

**Interfaces:**
- Consumes: Task 1 tree, shell, `keepFragment`.
- Produces: `export const aboutRedirect: RedirectFunction`; `export const ABOUT_SECTION_PAGES: Readonly<Record<string, string>>` =
  `{ 'about-formats': '/docs', 'about-versions': '/docs/versions', 'about-levels': '/docs/validating', 'about-profiles': '/docs/validating', 'about-editor': '/docs/fixing', 'about-retention': '/docs/about', 'about-cite': '/docs/about', 'about-version': '/docs/about' }`; unknown or no fragment → `/docs/about`.
  Tree nodes: `mzTab-M 2.0 and 2.1` `/docs/versions`, `Validating a file` `/docs/validating` (before Fixing a file), `About this service` `/docs/about` (last).
  Section ids (h2 id / wrapping section id): docs home `formats`/`about-formats`; versions `versions-engines`/`about-versions`; validating `levels`/`about-levels`, `profiles`/`about-profiles`, `network`, `reading-result`; service about `retention`/`about-retention`, `cite`/`about-cite`, `version`/`about-version`.

- [ ] **Step 1: Write the failing tests**

- `docs-redirects.spec.ts`: `redirects /about to /docs/about`; `redirects /about#about-levels to /docs/validating#about-levels`; `redirects /about#about-editor to /docs/fixing#about-editor`.
- `versions.page.spec.ts`: the engine table assertions from `about.page.spec.ts` (rows per engine, "Not available right now" for an unavailable engine, the fallback note without engines).
- `validating.page.spec.ts`: the levels list (INFO, WARN, ERROR), the profiles text, the custom-mapping paragraph only when `customMappingsEnabled`, the EBI OLS link.
- `service-about.page.spec.ts`: retention with `retentionDays` (`kept for 7 days`) and without; the citation DOI link `https://doi.org/10.1021/acs.analchem.8b04310`; version and build date.
- `docs-home.page.spec.ts`: `explains what mzTab-M is, its uses and audiences` (headings "What it is", "What it is for", "Who uses it"), the jmzTab-m link, and the chapter cards.
- `app-bar.component.spec.ts`: `has no About item`.

- [ ] **Step 2: Run them and see them fail** (Task 1 command). Expected: FAIL.

- [ ] **Step 3: Implement**

Move the About sections per spec §5.7 into the pages above (wording kept; the "Fixing a file" section is dropped). The docs home opens with the brief summary of the format (the long form of the landing section, Task 3) followed by "mzTab-M and jmzTab-m" and the chapter cards. `about` route → `redirectTo: aboutRedirect`. Remove About from the app bar items and `page-titles.ts`.

- [ ] **Step 4: Run the whole webapp suite and see it pass.**

- [ ] **Step 5: Commit**: `git commit -m "Docs: the About page becomes documentation; /about redirects by section"`

---

### Task 3: Getting started, the landing section, submission template URLs

**Files:**
- Create: `server/.../webapp/DocsProperties.java`, `server/src/test/.../DocsPropertiesTest.java`, `pages/docs/getting-started.page.*`, `pages/landing/about-mztabm.component.*` (+ specs)
- Modify: `AppInfoApiController.java` (+ its test), `application.properties`, `frontend/openapi/mztab-m-api.json` (regenerated), `pages/landing/landing.page.{html,spec.ts}`, `pages/docs/docs-tree.ts`, `app.routes.ts`, `docs/self-hosting/configuration.md`

**Interfaces:**
- Produces:
  - `@ConfigurationProperties("docs") @Validated public record DocsProperties(@Nullable String submissionTemplateUrl, @Nullable String submissionTemplateExampleUrl)`; blank → null; otherwise an absolute `https` URI of at most 2048 characters, else startup fails with a message naming the property.
  - `application.properties`: `docs.submission-template-url=https://lipidcompass.org/assets/templates/lipidcompass-submission-template.xlsx`, `docs.submission-template-example-url=https://lipidcompass.org/assets/templates/lipidcompass-submission-template-example.xlsx`.
  - `AppInfoDto` gains `@Nullable String submissionTemplateUrl, @Nullable String submissionTemplateExampleUrl` (appended).
  - Tree node `Getting started` `/docs/getting-started` (first chapter).
  - `AboutMzTabmComponent` (selector `app-about-mztabm`), placed in `landing.page.html` after the `.cols` block.

- [ ] **Step 1: Write the failing tests**

- `DocsPropertiesTest`: `blankIsNull`, `httpsIsKept`, `httpIsRejected` (`http://x` → `IllegalArgumentException` naming `docs.submission-template-url`), `relativeIsRejected`, `tooLongIsRejected` (2049 chars).
- `AppInfoApiControllerTest`: `offersTheSubmissionTemplateUrls` (defaults from `application.properties`), `omitsThemWhenConfiguredEmpty`.
- `getting-started.page.spec.ts`: `names the three routes` (headings "My software exported an mzTab-M file", "I have a spreadsheet of results", "I want to understand the format first"); `links the template, its example and LipidCompass's submission help` (hrefs equal the app-info URLs and `https://lipidcompass.org/guide/data-submission`); `says where to ask without a template URL` (no template link; a link to `https://lifs-tools.org/support.html`).
- `about-mztabm.component.spec.ts`: three columns with headings "What it is", "What it is for", "Who uses it"; links `/docs/getting-started` and `/docs`.
- `landing.page.spec.ts`: `places the mzTab-M section after the validator form` (`app-validator-form` precedes `app-about-mztabm` in document order).

- [ ] **Step 2: Run them and see them fail** (server command with `-Dtest='DocsPropertiesTest,AppInfoApiControllerTest'`; frontend command on the three specs).

- [ ] **Step 3: Implement**, wording from spec §5.2 (the landing columns verbatim in substance; the page lists "have ready" items and says more routes will follow). Refresh the OpenAPI spec and the client. `configuration.md`: a "Documentation" subsection with both properties, their defaults, `DOCS_SUBMISSION_TEMPLATE_URL` / `DOCS_SUBMISSION_TEMPLATE_EXAMPLE_URL`, and "empty: the Getting started page names the route without a link".

- [ ] **Step 4: Run the server test class, `OpenApiSpecExportTest`, and the whole webapp suite; see them pass.**

- [ ] **Step 5: Commit**: `git commit -m "Docs: Getting started and the landing section on mzTab-M; submission template URLs are configuration"`

---

### Task 4: Bundled tutorial examples on the server

**Files:**
- Create: `TutorialCatalog.java`, `TourSteps.java`, `TourDto.java` (records), tests `TutorialCatalogTest.java`, `TourStepsTest.java`, `ExamplesApiControllerTutorialTest.java`; test fixtures `server/src/test/resources/tutorial-fixture/fixture-2.0.mztab`, `fixture-2.0.tour.json`
- Modify: `ExampleFile.java` (`tour`, `originalUri`), `ExampleFetcher.java`, `ExamplesApiController.java`, `ExampleFetcherTest` (existing), OpenAPI spec and client

**Interfaces:**
- Produces (Java, package `org.lifstools.mztab2.server.api.validation`):
  - `public record TourTargetDto(String tab, @Nullable String group, @Nullable String key, @Nullable Integer rowIndex, @Nullable String column, @Nullable Boolean header)` — same shape as the frontend's `DocTarget`; `rowIndex` is the 0-based data row of its section.
  - `public record TourStepDto(String id, String chapter, String title, String text, @Nullable String lookFor, @Nullable TourTargetDto target, List<Integer> lines)` — `lines` is `[from, to]`, 1-based, inclusive.
  - `public record TourDto(String exampleId, String title, @Nullable String counterpart, List<TourStepDto> steps)`.
  - `final class TourSteps { static List<String> problems(List<String> fileLines, TourDto tour) }` — empty when valid. Rules: ids unique and non-blank; `chapter` ∈ `overview, metadata, summary, features, evidence, references, cv-parameters, profile, study-design`; `title`/`text` non-blank; `lines` has two values with `1 <= from <= to <= fileLines.size()`; `tab` ∈ `metadata, summary, features, evidence`; a metadata `key` is the second field of an `MTD` line inside `lines`; a metadata `group` alone matches an `MTD` key equal to it or starting with `group + "["` inside `lines` (group `general`: a key without `[`); `rowIndex` is below the number of data lines of the tab's prefix (`SML`/`SMF`/`SME`) and that line is inside `lines`; `column` is a field of the tab's header line (`SMH`/`SFH`/`SEH`), and with `header` true the header line is inside `lines`.
  - `@Component public class TutorialCatalog` with `Optional<String> content(String exampleId)` and `Optional<TourDto> tour(String exampleId)`; it loads every configured example whose `uri` is `classpath:tutorial/…` at startup and throws `IllegalStateException` listing all `TourSteps.problems` when any tour is invalid or a file is missing.
  - `ExampleDto` gains `@Nullable URI originalUri, boolean tour` (appended). A bundled example's `uri` is the relative `rest/v2/examples/{id}/content`.
  - `GET /rest/v2/examples/{id}/content` (operationId `getExampleContent`, `text/plain;charset=UTF-8`) and `GET /rest/v2/examples/{id}/tour` (operationId `getExampleTour`, JSON `TourDto`); both 404 for an unknown or non-bundled example, regardless of engine availability; 400 when `id` does not match `[A-Za-z0-9._-]{1,100}`.
  - `ExampleFetcher.download(URI)`: `classpath:tutorial/<name>` with `<name>` matching `[A-Za-z0-9._-]+` copies the resource to a temp file; any other `classpath:` URI throws `ExampleFetchException`.
  - `examples.properties` keys per entry: `tour=classpath:tutorial/<name>.tour.json`, `originalUri=<https URL>`.
  - The tour JSON file holds `{ "title", "counterpart", "steps": [...] }`; `exampleId` is set from the example entry, not the file.

- [ ] **Step 1: Write the failing tests**

`TourStepsTest` (inline file lines, one test per rule): `acceptsAValidTour`, `rejectsDuplicateIds`, `rejectsUnknownChapter`, `rejectsLinesOutOfRange`, `rejectsKeyOutsideLines`, `rejectsUnknownKey`, `acceptsGroupWithIndexedKeys` (`instrument` with `instrument[1]-name`), `rejectsRowIndexBeyondRows`, `rejectsColumnMissingFromHeader`, `acceptsNullTarget`.

`TutorialCatalogTest` (catalog built over the fixture via a test `ExampleFileConfig`): `servesContentAndTour`, `failsStartupListingEveryProblem` (fixture tour with two bad steps → message contains both step ids), `ignoresRemoteExamples`.

`ExampleFetcherTest`: `copiesBundledTutorialFile`, `classpathOutsideTutorialIsRefused` (`classpath:application.properties`, `classpath:tutorial/../application.properties`).

`ExamplesApiControllerTutorialTest` (`@WebMvcTest` slice, `EngineRegistry` stub with line 2.0 only):
- `contentIsPlainText` (200, `text/plain;charset=UTF-8`, body starts with `COM`)
- `tourHasStepsAndExampleId`
- `contentOfRemoteExampleIs404WithoutFetching` (`MTBLS263.mztab`; `ExampleFetcher` mock never called)
- `idOutsidePatternIs400` (`/rest/v2/examples/a%20b/tour`)
- `contentDoesNotDependOnEngines` (a 2.1 tutorial example with only the 2.0 engine: content 200, list omits it)
- `listMarksBundledExamples` (`tour: true`, `uri` = `rest/v2/examples/<id>/content`, `originalUri` set)
- `validatesBundledExample` (POST `/rest/v2/examples/<id>/validations` → 202 using the classpath branch)

- [ ] **Step 2: Run them and see them fail** (`-Dtest='TourStepsTest,TutorialCatalogTest,ExampleFetcherTest,ExamplesApiControllerTutorialTest'`).

- [ ] **Step 3: Implement** the interfaces above. The id pattern is checked in the controller before any lookup (`@Pattern` on the path variable with method validation, or an explicit check throwing `IllegalArgumentException`, which the existing handler maps to 400). Refresh OpenAPI and the client.

- [ ] **Step 4: Run the four classes, `OpenApiSpecExportTest` and the full server suite (`./mvnw -B -pl server -am test -Dfrontend.skip=true`); see them pass.**

- [ ] **Step 5: Commit**: `git commit -m "Examples: bundled tutorial files with step lists, served as content and tour"`

---

### Task 5: The teaching files and their step lists

**Files:**
- Create: `server/src/main/resources/tutorial/teaching-lipidomics-2.0.mztab`, `teaching-lipidomics-2.1.mztab`, `study-design-2.1.mztab`, and a `.tour.json` for each; `engine-mztabm-2.0/src/test/java/org/lifstools/mztab/validator/engine/TutorialExamplesTwoZeroTest.java`; `engine-mztabm-2.1/src/test/java/org/lifstools/mztab/validator/engine/TutorialExamplesTwoOneTest.java`; `server/src/test/java/.../TutorialFilesTest.java`
- Modify: `server/src/main/resources/examples.properties`

**Interfaces:**
- Consumes: Task 4's tour JSON format, `TourSteps.problems`, `TutorialCatalog`.
- Produces: example ids `teaching-lipidomics-2.0.mztab`, `teaching-lipidomics-2.1.mztab` (counterparts of each other), `study-design-2.1.mztab` (no counterpart); section `tutorial`; `version`/`mzTabVersion` `2.0`/`2.0.0-M` and `2.1`/`2.1.0-M`; `originalUri` `https://github.com/HUPO-PSI/mzTab-M/blob/main/examples/2.0/manual_null_null_lipidomics.mztab`, `…/examples/2.1/manual_null_null_lipidomics_v2.1.mztab`, `…/examples/2.1/example_study_variable_group.mztab`. Steps of the lipidomics tours, in order, with their chapters (spec §5.3, plus one step for the references page):

  | # | id | chapter | element |
  |---|---|---|---|
  | 1 | `sections` | overview | none (`target: null`); lines: the first lines of each section |
  | 2 | `what-file` | metadata | group `general` |
  | 3 | `measured` | metadata | group `instrument` |
  | 4 | `samples-runs-assays` | metadata | group `assay` |
  | 5 | `groups` | metadata | group `study_variable` |
  | 6 | `units-vocabularies` | cv-parameters | key `small_molecule-quantification_unit` |
  | 7 | `summary-row` | summary | `rowIndex: 0` |
  | 8 | `features` | features | column `SMF_ID` header, then the rows via `lookFor` |
  | 9 | `evidence` | evidence | `rowIndex: 0` |
  | 10 | `one-lipid` | references | column `SMF_ID_REFS` of `rowIndex: 0` in summary; lines span SML to SME |
  | 11 | `profile` (2.1 only) | profile | key `mzTab-profile` |
  | 12 | `validate-yours` | overview | none |

  The study design tour has chapter `study-design` throughout (study variable groups, study variables, assays, the 2 × 3 design, then `validate-yours`).

- [ ] **Step 1: Write the failing tests**

`TutorialExamplesTwoZeroTest` / `TwoOneTest`: read `Path.of("..", "server", "src", "main", "resources", "tutorial", name)`, validate with `EngineValidator` (mocked `CvParameterLookupService`, `ValidationLevel.INFO`, 500, no mapping) and assert `outcome.parsed()` and no message of level ERROR or WARN (`as("messages: %s", messages)`). 2.0: `teaching-lipidomics-2.0.mztab`; 2.1: `teaching-lipidomics-2.1.mztab` and `study-design-2.1.mztab`.

`TutorialFilesTest` (server, real `examples.properties`): `everyTutorialExampleHasAValidTour` (`TutorialCatalog` boots without exception; each tour has ≥ 1 step); `firstCommentNamesTheOriginal` (line 1 starts with `COM` and contains `HUPO-PSI/mzTab-M` and `CC BY 4.0`); `lipidomicsToursCoverEveryChapter` (2.0 tour chapters ⊇ `overview, metadata, summary, features, evidence, references, cv-parameters`; 2.1 additionally `profile`).

- [ ] **Step 2: Run them and see them fail** (files missing).

- [ ] **Step 3: Author the files**

Start from #153's branch `fix/lipidomics-example-quirks` of HUPO-PSI/mzTab-M (or `main` if #153 is merged) and #151's `example_study_variable_group.mztab` (or `main`'s if #151 is not merged). Adapt per spec §5.4: two samples (control, treated), one assay and one study variable each; a second lipid PC 34:1 measured full-scan with one `[M+H]1+` feature and one evidence row, masses computed from the formula (C42H82NO8P, monoisotopic neutral mass 759.5778; [M+H]+ 760.5851); `mzTab-ID` and `description` say "teaching example"; one consistent quantification unit with a matching description; the 2.1 file declares `mzTab-profile` and keeps the summary section only if the 2.1 engine reports no warning for it. Write the steps per spec §5.3, 2–5 sentences each, every step's `lines` the smallest range showing its element.

- [ ] **Step 4: Run the engine tests and `TutorialFilesTest`; see them pass.**

- [ ] **Step 5: Semantic check against running engines**

Start the local stack (Global Constraints) and post each file with its version's default mapping:
```bash
curl -s -F "file=@server/src/main/resources/tutorial/teaching-lipidomics-2.0.mztab" \
  -F "mapping=@server/src/main/resources/validation-profiles/mztab-m-2.0-default/1.1.0.xml" \
  "localhost:8194/engine/v1/validations?level=INFO&maxErrors=500" | grep '"type":"outcome"'
```
(2.1 files on 8195 with `mztab-m-2.1-default/1.0.0.xml`.) Expected: no message with `"message_type":"error"` or `"warn"`; record the info messages in the task report (they are candidates for the tour's text, e.g. Info-2052). Stop the stack afterwards.

- [ ] **Step 6: Commit**: `git commit -m "Tutorial: teaching files for mzTab-M 2.0 and 2.1 and a study design example, with step lists"`

---

### Task 6: Understanding mzTab-M chapter pages

**Files:**
- Create: `core/tutorial.store.ts` (+ spec), `pages/docs/excerpt.ts` (+ spec), `pages/docs/excerpt.component.*` (+ spec), `pages/docs/format-chapter.page.*` (+ spec)
- Modify: `pages/docs/docs-tree.ts` (chapter "Understanding mzTab-M" `/docs/format` with children metadata, summary, features, evidence, references, cv-parameters, study-design, titles per §5.1), `app.routes.ts`, `core/page-titles.ts` via the tree

**Interfaces:**
- Consumes: generated `ExamplesService` operations `getExampleContent`, `getExampleTour`, `listExamples`; models `TourDto`, `TourStepDto`, `TourTargetDto`.
- Produces:
  - `@Injectable({ providedIn: 'root' }) class TutorialStore { tour(exampleId: string): Observable<TourDto>; lines(exampleId: string): Observable<readonly string[]>; offered(): Signal<ReadonlySet<string> | undefined> }` (cached per id with `shareReplay(1)`; `offered` = ids of `listExamples` with section `tutorial`).
  - `export function excerptMarks(lines: readonly string[], step: TourStepDto): ReadonlySet<number>` — 1-based line numbers to mark, inside `step.lines`: metadata key → its `MTD` line; group → its `MTD` lines; row → that data line; header column → the header line; null target → none.
  - `ExcerptComponent` (selector `app-excerpt`, inputs `lines`, `step`): `<pre>` with line numbers, tabs rendered as `→` (`aria-hidden`) plus a visually hidden "tab", marked lines with `<mark>`.
  - Route data for `FormatChapterPage`: `{ chapter: string; exampleId: string }`; `/docs/format` → `overview`, `/docs/format/<x>` → `<x>`, `study-design` uses `study-design-2.1.mztab`, all others `teaching-lipidomics-2.0.mztab`.
  - Each step renders as a section `id="step-<step.id>"` with `h2` title, excerpt, text and "Look for" note.

- [ ] **Step 1: Write the failing tests**

- `excerpt.spec.ts`: one test per target kind on a five-line inline file, plus `staysInsideTheStepLines`.
- `excerpt.component.spec.ts`: `shows tabs as arrows with a text alternative`, `marks the step's element`, `numbers the lines from the range start`.
- `format-chapter.page.spec.ts` (TutorialStore stub): `shows the chapter's steps in order`; `links the download and the HUPO-PSI original` (hrefs `rest/v2/examples/teaching-lipidomics-2.0.mztab/content` and the `originalUri`); `renders the chapter when its example is not offered for validation` (`offered()` empty → steps and excerpts present, no "Try it" button); `shows an error note when the file cannot be loaded`.
- `tutorial.store.spec.ts`: `requests each file once` (two subscriptions, one HTTP request).

- [ ] **Step 2: Run them and see them fail.**

- [ ] **Step 3: Implement.** The chapter intro prose per chapter lives in the page template (`@switch`); the overview page also explains the four sections and the `COM` prefix. "Try it" is added in Task 7.

- [ ] **Step 4: Run the whole webapp suite; see it pass.**

- [ ] **Step 5: Commit**: `git commit -m "Docs: Understanding mzTab-M, chapters with excerpts from the teaching files"`

---

### Task 7: The tour

**Files:**
- Create: `core/tour.service.ts` (+ spec), `pages/result/tour-panel.component.*` (+ spec), `frontend/e2e/tour.spec.ts`
- Modify: `pages/result/result.page.{ts,html,scss,spec.ts}`, `pages/landing/about-mztabm.component.*`, `pages/docs/format-chapter.page.*`, `pages/docs/getting-started.page.html` ("Take the tour" in the third route)

**Interfaces:**
- Consumes: `TutorialStore`, `ExamplesService.validateExample`, `DocTarget`, result page `showTarget`.
- Produces:
  - `@Injectable({ providedIn: 'root' }) class TourService { start(exampleId: string, step = 1, replaceUrl = false): Observable<void> }` — validates the example (`level: 'INFO'`) and navigates to `/result/<id>?tour=<exampleId>&step=<step>`.
  - Result page query params `tour` (example id, pattern `[A-Za-z0-9._-]{1,100}`; anything else, or an id whose tour does not load, leaves the normal result page) and `step` (1-based; a value that is not an integer within the tour's steps shows step 1).
  - `Focus.group` becomes `MessageGroup | null`; the stepper and back bar render only when `group` is set. A tour step calls `showTarget({ group: null, targets: [target], labels: [step.title], index: 0, all: false, moveFocus: false })`; a step without target shows its tab without highlight (metadata for the first step).
  - `TourPanelComponent` (selector `app-tour-panel`): inputs `tour: TourDto`, `index: number`, `counterpartOffered: boolean`; outputs `back`, `next`, `close`, `switchVersion`. Shows title, text, "Look for", "Step n of m", Back / Next (Next on the last step reads "Validate your own file" and navigates to `/`), Close, and the version switch ("Show this in mzTab-M 2.1" / "2.0") when `counterpartOffered`.
  - In the split layout the panel replaces the messages section; in the single-pane layout it sits above the tablist and the step's tab is selected.

- [ ] **Step 1: Write the failing tests**

- `tour.service.spec.ts`: `validates the example and opens the result at the step` (POST body `{ level: 'INFO' }`, navigation `['/result', 'r1']` with `queryParams { tour, step: 3 }`).
- `tour-panel.component.spec.ts`: `shows step n of m`; `disables Back on the first step`; `offers Validate your own file on the last step`; `hides the version switch when the counterpart is not offered`.
- `result.page.spec.ts`:
  - `shows the tour panel instead of the messages in the split layout`
  - `shows the tour panel above the tabs in the single-pane layout` (BreakpointObserver stub delivering `false` after creation)
  - `drives the viewer with the step's target` (step with metadata key → `metadataHighlight()` has that key; table step → the step's tab is selected and `tableHighlight().focusRow` equals `rowIndex`)
  - `restarts an expired tour at the same step` (GET validation 404 with `?tour=x&step=4` → `TourService.start('x', 4, true)` called, no not-found view)
  - `ignores a malformed tour parameter` (`?tour=../x` → normal result page)
- `about-mztabm.component.spec.ts`: `Take the tour starts the 2.0 lipidomics tour` (`start('teaching-lipidomics-2.0.mztab')`), shown only when offered.
- `format-chapter.page.spec.ts`: `Try it starts the tour at the chapter's first step` (step number = index of that step in the tour + 1).
- `frontend/e2e/tour.spec.ts`: from the landing page "Take the tour", step through all steps checking each highlighted element (`.mark`/`[aria-current]` per the viewer's existing highlight markers) is visible; switch to 2.1 and back; Close returns to `/`; axe on a tour step with no violations beyond the tolerated brand-colour pairs (reuse the existing axe helper).

- [ ] **Step 2: Run the unit tests and see them fail.**

- [ ] **Step 3: Implement.** The tour never edits: no "Fix this file" call to action inside the panel.

- [ ] **Step 4: Run the webapp suite; then e2e locally**

Start the local stack (Global Constraints), then:
```bash
E2E_BASE_URL=http://localhost:8093/mztabvalidator/ npm --prefix frontend run e2e -- tour.spec.ts
```
Expected: PASS.

- [ ] **Step 5: Commit**: `git commit -m "Tour: step through a teaching file in the result viewer"`

---

### Task 8: mzTab-M 2.0 and 2.1, tools and services, docs sweep

**Files:**
- Create: `engine-mztabm-2.1/src/test/java/org/lifstools/mztab/validator/engine/TwoOneChangesTest.java` with small files under `engine-mztabm-2.1/src/test/resources/changes/`; `pages/docs/integration.page.*` (+ spec); `frontend/e2e/docs.spec.ts`
- Modify: `pages/docs/versions.page.*` (+ spec), `pages/docs/docs-tree.ts`, `docs/release-notes/2.0.0.md`, `docs/self-hosting/configuration.md`, `README.adoc` if it links `/guide` or `/about`

**Interfaces:**
- Consumes: Task 2 versions page, `AppInfoStore` (`engines`, `unavailableHintUrl`).
- Produces: versions page sections `what-changed`, `converting`, `validating-21` (ids); tree node `For tools and services` `/docs/integration` (before About this service).

- [ ] **Step 1: Write the failing tests**

- `TwoOneChangesTest`: one test per row of the change table, each on a minimal file: `acceptsVersion210M`; `reportsMissingProfileAsInfo2052`; `reportsFieldOfAbsentSectionAsInfo2057`; `readsStudyVariableGroups`; `acceptsNullSmfCharge`. A row whose test cannot pass against jmzTab-m 2.1 is left out of the table (spec §5.5) and its test deleted; note it in the task report.
- `versions.page.spec.ts`: `shows the profile step of the 2.1 teaching file` (the `profile` step of `teaching-lipidomics-2.1.mztab` rendered with `app-excerpt` under `what-changed`); `lists only the verified changes` (rows equal the constant `VERIFIED_CHANGES` exported by the page, which names its test for each row); `numbers the conversion steps` (5 items, the third names the quick-fix label from `core/quick-fix-labels.ts` for `remove-mismatched-field`); `links the development validator when 2.1 is unavailable` (engine 2.1 `available: false`, `unavailableHintUrl` set → link present; available → absent).
- `integration.page.spec.ts`: links `swagger-ui/index.html`, pymzTab-m, rmzTab-m and `https://github.com/lifs-tools/jmzTab-m-webapp/tree/master/docs/self-hosting`.
- `frontend/e2e/docs.spec.ts`: `opens a deep link to a docs section under the context path` (`docs/validating#about-levels` → heading in viewport); `old addresses redirect` (`guide#guide-fixing`, `problems`, `about#about-cite`); `Documentation menu reaches every chapter`; axe on `/docs/getting-started`, `/docs/format/metadata` and the landing page.

- [ ] **Step 2: Run them and see them fail.**

- [ ] **Step 3: Implement** spec §5.5 (no claims without a source; "which tools write 2.1" is an empty list with the sentence that it fills as tools adopt it). Docs sweep: `grep -rn "/guide\|/problems\|/about" frontend/projects frontend/e2e server/src/main docs README.adoc` shows only redirects, tests of redirects and the release-note sentence. Release notes: a "Documentation" section (menu, chapters, tour, landing section, redirects). `configuration.md`: the teaching files are bundled, no outbound access needed for them.

- [ ] **Step 4: Full verification**

`./mvnw -B verify` (all modules, including the frontend build and unit tests), then the e2e suite against 8093 as in Task 7 Step 4 (all specs). Expected: PASS; record counts in the task report.

- [ ] **Step 5: Commit**: `git commit -m "Docs: mzTab-M 2.0 and 2.1, tools and services; release notes"`

- [ ] **Step 6: Request the lead's documentation review** (CLAUDE.md): list every new page (`/docs`, getting-started, the format chapters, versions, validating, fixing, problems, integration, about) and the teaching files with their step texts, and ask for review of wording and scientific content.
