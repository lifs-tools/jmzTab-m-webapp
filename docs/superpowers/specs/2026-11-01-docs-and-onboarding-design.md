# Documentation and Getting Started with mzTab-M

**Status:** draft, revised after the project lead's first review (2026-11-01).
**Builds on:** `2026-10-23-validator-spa-design.md` (the SPA, its shell and pages), `2026-10-31-mztabm-editor-design.md` (guide, problems page, editor, §5.10a), `2026-10-30-mztabm-version-engines-design.md` (2.0 and 2.1 engines).

---

## 1. Intent

The people who reach the validator are mostly **lipid researchers who want to deposit data in a public repository**. They arrive with one of two things:

- **a file their software exported** as mzTab-M, which the repository or the validator now complains about; or
- **a spreadsheet of results** and no mzTab-M yet.

Both need to understand the format well enough to act: what the metadata, summary (SML), features (SMF) and evidence (SME) are, how the rows refer to each other, and what the validator's messages point at. The help that exists today, the guide "Fixing a file" and "Common problems", is reachable only from the footer and the result page. It is a flat page each and explains the editor, not the format.

The project lead tested 2a and asked for:

1. a **Documentation** item in the main navigation, with the guide and the problems page folded in, a **hierarchical page structure** and **floating sections** so users can find their way around;
2. a **"How to get started with mzTab-M"** hint on the landing page, leading to a **step-wise walkthrough** of the parts of an mzTab-M file, based on an **educational but otherwise typical mzTab-M 2.0 and 2.1 file**;
3. a short **hint on converting a 2.0 file to 2.1**.

Decided in conversation (2026-11-01):

- **Documentation is a top-level item of the app bar.** "Fixing a file" and "Common problems" are reached through it, not through the footer.
- **The landing page gets a section about the format**: a brief summary of what mzTab-M is, what it is used for and who uses it, linking to the documentation's main page.
- **The About page is the candidate for the documentation's main page**: its format material moves into the documentation (D13).
- The walkthrough comes as **both** a documentation chapter (the reference) and an **interactive tour** in the real document viewer ("Try it").
- People with a spreadsheet are pointed to the **LipidCompass submission template**, the most mature route today; more integrations will follow, so the page lists routes, not one tool.
- The submission template gets a **public home on lipidcompass.org**: it is added to the LipidCompass repository as a UI asset, offered in its submission pages and help, and the validator links it there.
- The example is the **HUPO-PSI lipidomics pair** (`examples/2.0/manual_null_null_lipidomics.mztab` and `examples/2.1/manual_null_null_lipidomics_v2.1.mztab`), with its quirks fixed upstream ([HUPO-PSI/mzTab-M#153](https://github.com/HUPO-PSI/mzTab-M/pull/153)), adapted for teaching. The 2.1 **study design example** (`examples/2.1/example_study_variable_group.mztab`) illustrates multi-factor designs.

## 2. Goals, non-goals, anti-goals

### Goals

1. A user who has never seen mzTab-M can, in about ten minutes, name the four sections, follow one lipid from its summary row to its features and evidence, and tell which part of their own file a validator message points at.
2. A user with a spreadsheet knows which route produces mzTab-M for them, today the LipidCompass submission template, and what information they need to have at hand.
3. A user with a 2.0 file knows the concrete steps to make it a 2.1 file and how to check it.
4. All help lives in one place with one navigation, and every page section has a stable link that the guidance, the resolution report and other pages can point to.

### Non-goals

- Reproducing the specification. The documentation explains and links to the specification for the normative text.
- An automatic 2.0 → 2.1 converter. This is a hint and a checklist, with validation on the 2.1 engine.
- Authoring a file from scratch in the browser. That is phase 2b (guided authoring) of the editor design.
- Translations.

### Anti-goals (failure even if it "works")

- An example or a tour that teaches something the validator then rejects: the bundled examples must validate on their engine without errors or warnings.
- Documentation that drifts from the application: labels, buttons and messages in the docs come from the same constants as the UI, as for the guide today (`core/quick-fix-labels.ts`).
- An introduction that gets in the way of the user who just wants to validate: the format section sits below the form, is not a modal and never pushes the form down.
- Breaking existing links: `/guide`, `/problems`, `/about` and their `#…` anchors keep working.

## 3. Current state

- **Routes:** `/` landing (validator form), `/result/:id`, `/edit/:id`, `/about`, `/examples`, `/guide`, `/problems`. The app bar has Validate, Examples, REST API (Swagger UI), About and LIFS Tools; the footer links "Fixing a file" (`/guide`) and "Common problems" (`/problems`).
- **The guide** is one Angular template with `<h2 id="guide-…">` cards and `<h3>` subsections. Specs assert its key sentences and that it names the real button labels.
- **The problems page** lists the guidance catalog (`/rest/v2/guidance`).
- **Examples** come from `examples.properties`, which links to files on `raw.githubusercontent.com`; the Examples page validates one by id.
- **The document viewer** (library `mztabm-editor`: metadata view, table views) can already highlight a metadata entry, a group, rows, a cell or a column, open the group or tab, and scroll it into view. The result page and the editor drive it from messages (`targetOf`).

## 4. Decisions

| # | Decision | Whose |
|---|---|---|
| D1 | One **Documentation** item in the app bar, at `/docs`, is the only way into the help: the guide and the problems page live under it, and the **footer no longer links them**. | lead |
| D2 | Docs pages form a **tree** (§5.1): a side navigation of chapters and pages, and on each page a floating **"On this page"** list of its sections that follows the scroll position. | lead |
| D3 | The walkthrough exists as a **chapter** (annotated excerpts) **and** an **interactive tour** in the real viewer, sharing one step list. | lead |
| D4 | Spreadsheets → **LipidCompass submission template**, presented as one route among future ones. | lead |
| D5 | The teaching examples are the **cleaned HUPO-PSI lipidomics pair**, adapted (§5.4), plus the **2.1 study design example**. | lead |
| D6 | The teaching files are **bundled with the webapp**, not fetched from GitHub, so the tour's steps and the files cannot drift apart and the tour works offline from GitHub. The page names and links the HUPO-PSI originals. | my call |
| D7 | `/guide` and `/problems` **redirect** to their new places with their fragments; old anchors keep working. | my call |
| D8 | The landing page gets an **"About mzTab-M" section below the validator form**: what the format is, what it is used for, who uses it, with links to Getting started, the tour and the documentation's main page. It replaces the dismissible card of the first draft: a section below the form cannot get in the way, so it needs no dismissal. | lead (section), my call (placement, no card) |
| D9 | The bundled teaching files are **served by the server as examples** (classpath resources listed in the examples catalog, section `tutorial`), so the tour uses the existing `POST /examples/{id}/validations`. Each file's **step list lives next to it** and is served with the example. If the tour's result has expired, the tour validates the example again without asking: it is a public example, not user data. | my call |
| D10 | Chapter excerpts are **rendered from the bundled file**, not pasted: each step names its line range (`lines: [from, to]`) besides its target, and the chapter shows those lines. | my call |
| D11 | The **submission template is downloaded from lipidcompass.org**, where it is a public asset of the LipidCompass UI (`/assets/templates/lipidcompass-submission-template.xlsx`, template v3, a stable name; the version is stated next to the link; a filled-in example sits next to it as `lipidcompass-submission-template-example.xlsx`). The address is configuration (`docs.submission-template-url`) with that URL as its default, and the page also links LipidCompass's data submission help (`https://lipidcompass.org/guide/data-submission`). Configured empty, the page describes the route and says where to ask, without a link. | lead (home), my call (config) |
| D12 | The **teaching files are authored by the builder** from the cleaned pair (#153 branch `fix/lipidomics-example-quirks`, validated on both engines on 2026-11-01), with masses computed from the formulas. Their scientific content is part of the lead's documentation review. | my call |
| D13 | **The About page becomes documentation.** Its format material (mzTab-M and jmzTab-m, versions and engines, validation levels, profiles and mapping files, the editor) moves into the documentation's main page and chapters (§5.7). What is about the service (retention and privacy, how to cite, version) becomes the page "About this service" at `/docs/about`. `/about` redirects there, and the app bar drops "About". | lead (candidate), my call (split) |
| D14 | The app bar's **Documentation item is a menu**: its chapters plus "All documentation" (`/docs`). On narrow screens the chapters appear in the existing navigation menu under a "Documentation" heading. The item is marked active on every `/docs` page. | my call |

## 5. Design

### 5.1 Documentation structure

```
Documentation  (/docs)         mzTab-M in brief: what it is, its uses, its audiences · the chapters
├── Getting started            /docs/getting-started
│     What mzTab-M is · Which route is yours (export / spreadsheet / have a file) · Validate · Next steps
├── Understanding mzTab-M      /docs/format
│   ├── Overview: one file, four sections            /docs/format   (the chapter's own page)
│   ├── Metadata (MTD)                                /docs/format/metadata
│   ├── Summary (SML)                                 /docs/format/summary
│   ├── Features (SMF)                                /docs/format/features
│   ├── Evidence (SME)                                /docs/format/evidence
│   ├── How the rows refer to each other              /docs/format/references
│   ├── Controlled vocabularies and parameters        /docs/format/cv-parameters
│   └── Describing your study design                  /docs/format/study-design
├── mzTab-M 2.0 and 2.1        /docs/versions
│     Versions and engines (from About, with live status) · What changed · Converting a 2.0 file to 2.1 · Validating 2.1 files
├── Validating a file          /docs/validating
│     Validation levels · Profiles and mapping files · Network access (OLS) · Reading the result   (from About)
├── Fixing a file              /docs/fixing        (today's /guide)
├── Common problems            /docs/problems      (today's /problems)
├── For tools and services     /docs/integration
│     REST API (link to Swagger UI) · pymzTab-m, rmzTab-m · Self-hosting (links to docs/self-hosting on GitHub)
└── About this service         /docs/about         (today's /about, D13)
      Retention and privacy · How to cite · Version
```

- **Side navigation:** chapters and their pages, the current page marked. Below 1100 px it collapses into a "Contents" menu at the top of the page.
- **"On this page":** the current page's `h2`/`h3` sections, floating beside the text from 1100 px, with the section in view marked. Below that it is a collapsible list under the page title.
- **Previous/next** links at the end of each page follow the tree order.
- **Anchors:** every section has a stable id (`#metadata-ms-runs`, …). The guidance catalog, the resolution report and the problems page may link into the docs by these ids. The ids of today's guide (`#guide-result`, `#guide-fixing`, …) stay valid.
- **App bar:** Validate · Examples · **Documentation ▾** · REST API · LIFS Tools (D14). "About" is gone from the app bar; it is the last page of the documentation (D13).
- **Footer:** legal links, Analytics and the copyright band only. It no longer links Fixing a file or Common problems (D1).
- **Search** is not part of this design. The tree is small; browser find works on each page.

### 5.2 Getting started (landing section and page)

**Landing section (D8).** A full-width section below the validator form and the examples, titled **"About mzTab-M"**, in three short columns (stacked on narrow screens), each two or three sentences:

- **What it is.** The HUPO-PSI standard for reporting the results of mass-spectrometry metabolomics and lipidomics: one tab-separated text file with the study's metadata, the identified molecules with their quantities, and the features and evidence behind them.
- **What it is for.** Depositing results in public repositories, exchanging them between tools, and reanalysing published studies without the software that produced them.
- **Who uses it.** Researchers whose software exports mzTab-M or who prepare their results for a repository, the tools that write and read it, and the repositories that accept it, LipidCompass among them.

Below the columns: **[Get started with mzTab-M]** (`/docs/getting-started`) · **[Take the tour]** · **Read the documentation** (`/docs`). The section is static content, part of the landing page's template, with no storage and no dismissal. The wording is the builder's; the lead reviews it.

The form stays the first thing on the page.

**The Getting started page** answers "which route is mine":

- **"My software exported an mzTab-M file."** Validate it. Read the summary of what is wrong, then use Understanding mzTab-M to see where in the file it is, and Fixing a file to fix it here or in the tool.
- **"I have a spreadsheet of results."** Use the **LipidCompass submission template** (Excel, version 3, downloaded from lipidcompass.org with its filled-in example, D11). One workbook describes a study with one or more datasets; its sheets (Study, Contacts, Datasets, Samples, Factors, one quantities sheet per dataset, Lipids, Checks) ask for the study, samples and lipids in a table; LipidCompass's curation pipeline converts it to validated mzTab-M (as the submissions repository describes it). Have ready: the lipid names (shorthand nomenclature), the quantities and their unit, the samples and their groups, the instrument and method. How to hand it in is described in LipidCompass's data submission help, which the page links. More routes (tool integrations) will be listed here as they become available.
- **"I want to understand the format first."** Take the tour or read Understanding mzTab-M.

### 5.3 The walkthrough: chapter and tour

**One step list, two presentations.** A step is: a title, a short explanation (2–5 sentences), the **element** it is about (a metadata group or key, a section, a row, a column or cell, given as the viewer's existing highlight target), and optionally a **"look for"** hint ("the `SMF_ID_REFS` cell lists the features of this lipid"). Steps are data (one file per example and version), so the chapter and the tour cannot disagree.

**Steps (2.0 example; the 2.1 tour adds the profile step):**

1. *One file, four sections.* The line prefixes `MTD`, `SMH/SML`, `SFH/SMF`, `SEH/SME`, and `COM`.
2. *What the file is.* `mzTab-version`, `mzTab-ID`, `description`, `publication`.
3. *How it was measured.* `instrument[1]`, `software[n]`, `quantification_method`.
4. *Samples, runs and assays.* `sample[n]`, `ms_run[n]`, `assay[n]` and their references.
5. *Groups to compare.* `study_variable[n]` with its `assay_refs`, `average_function`.
6. *Units and vocabularies.* `cv[n]`, `small_molecule-quantification_unit`, how `[CV, accession, name, value]` reads.
7. *The summary row.* One lipid per row: name, identifiers, formula, abundance per assay and per study variable.
8. *Its features.* `SMF_ID_REFS` → the SMF rows: m/z, charge, retention time, abundance per assay.
9. *Its evidence.* `SME_ID_REFS` → the SME rows: the identification, spectra reference, confidence.
10. *(2.1)* *The profile.* `mzTab-profile` says which sections the file has; fields that belong to an absent section are reported (Info-2057).
11. *Validate your own file.* Back to the form.

**The chapter** (`/docs/format/…`) shows each step with an excerpt of the example file (the relevant lines, tabs shown as arrows, the element highlighted), the explanation, and a link to the full file (download and the HUPO-PSI original).

**The tour** ("Take the tour" / "Try it" on a chapter page) validates the bundled example through the existing examples flow and opens its result page with a **tour panel** in place of the messages column: step text, Back / Next, step *n* of *m*, Close. Each step drives the viewer with its target exactly as a message does (open the group or tab, highlight, scroll). A version switch (2.0 / 2.1) restarts the tour on the other file. The tour never edits the file.

**Study design step.** A separate short tour/chapter page on `example_study_variable_group.mztab` (2.1): study variable groups as factors, study variables as levels, assays as design cells, and the 2 × 3 example.

### 5.4 The teaching examples

Base: the cleaned HUPO-PSI pair from #153 (branch `fix/lipidomics-example-quirks`; use the merged files if #153 has been merged by then). For teaching, the bundled copies are adapted:

- **two samples** (control and treated), one assay and one study variable each, so the abundance columns compare something;
- a **second lipid** measured full-scan (e.g. PC 34:1 with one [M+H]1+ feature and one evidence row) next to the SRM ceramide with its fragment features, so both common shapes appear;
- `mzTab-ID` and `description` that say it is a teaching example;
- the 2.1 file declares its profile and keeps the summary section if the profile allows it without warnings (else the tour explains the choice; settle against the 2.1 engine when building);
- the unit question in #153 (picomolal vs. "pmol per mg protein") settled in the teaching copy by choosing a consistent unit and description.

**Acceptance:** each file validates on its own engine (structural and with the default CV mapping) with no errors or warnings; info messages are listed in the tour where they teach something (e.g. Info-2052 on a 2.0 file without a profile).

The adapted files are offered back to HUPO-PSI as additional examples once they are final (a separate PR).

### 5.5 mzTab-M 2.0 and 2.1

A page with:

- **What changed in 2.1**, as a short table: the change, what it means for a lipidomics file, whether a 2.0 file must change. The builder compiles it from the 2.1 specification draft and jmzTab-m 2.1 (code lists, `MzTabProfileValidator`, schema), and every row is checked against the 2.1 engine with a test file. Known items to verify: `mzTab-version 2.1.0-M`; `mzTab-profile` and the conditional fields (Info-2052/2057); `study_variable_group`; nullable SMF `charge`.
- **Converting a 2.0 file to 2.1**, as numbered steps: change `mzTab-version`; declare `mzTab-profile` for the sections present; remove fields of absent sections (the 2057 quick fix does this in the editor); optionally describe factors with `study_variable_group`; validate on the 2.1 engine. Where 2.1 validation is only on the development validator, the page says so and links it (`engines.unavailable-hint-url`).
- Which tools write 2.1 today: left as a list to fill as tools adopt it; no claims without a source.
- If a row of the change table cannot be verified against the 2.1 engine and a source, it is left out; shipping only the verified items is acceptable.

### 5.6 Moving the guide and problems page

- `/docs/fixing` is today's guide content; `/docs/problems` today's problems page. `/guide` and `/problems` redirect with their fragment (D7).
- The guide's cards become sections of the docs layout; its specs move with it and keep asserting the button labels from the shared constants.
- Links that point to `/guide`, `/problems` or `/about` inside the app (result page, editor help, guidance panel, resolution report,) are updated to the new paths. The footer's two help links are removed (D1).

### 5.7 Folding the About page into the documentation (D13)

| Today's About section | Goes to |
|---|---|
| mzTab-M and jmzTab-m | the documentation's main page (`/docs`), next to the brief summary of the format |
| mzTab-M versions (with the live engine table) | mzTab-M 2.0 and 2.1 (`/docs/versions#versions-engines`) |
| Validation levels | Validating a file (`/docs/validating#levels`) |
| Validation profiles and mapping files | Validating a file (`/docs/validating#profiles`) |
| Fixing a file | dropped: Fixing a file is its own page |
| Retention and privacy · How to cite · Version | About this service (`/docs/about`) |

The ids of today's About sections (`#about-formats`, `#about-versions`, `#about-levels`, `#about-profiles`, `#about-editor`, `#about-retention`, `#about-cite`, `#about-version`) keep working: `/about#<id>` redirects to the page that now holds the section, and the section carries the old id as well. The about page's specs move with their sections.

## 6. Documentation of this feature

This feature is documentation. In addition: the release notes (2.0.0, "Documentation" section) describe the new menu and the tour; `docs/self-hosting/configuration.md` notes that the teaching files are bundled (no outbound access needed).

## 7. Testing

- Navigation: the tree renders, the current page is marked, previous/next follow the tree, the "On this page" list marks the section in view (spec with a scroll stub), and the collapsed variants below 1100 px.
- Redirects: `/guide#guide-fixing` → `/docs/fixing#guide-fixing`; `/problems` → `/docs/problems`; `/about` → `/docs/about`; `/about#about-levels` → `/docs/validating#about-levels`.
- App bar: the Documentation menu lists the chapters and is active on `/docs/**`; "About" is gone. Footer: no help links.
- Landing section: present below the form, links to Getting started, the tour and `/docs`.
- Steps: every step's target resolves to an element in its example file (a spec per step list, loading the bundled file through the viewer's data source).
- Examples: the bundled files validate without errors or warnings on their engines (engine test on both lines).
- Tour (e2e): start from the landing section, step through, each step highlights its element; version switch; close returns to the landing page. Axe checks on a docs page, the landing page with its new section, and the tour.
- Docs content specs: key sentences and labels from shared constants, as today.

## 8. Phasing

One phase, independent of the editor's 2b: (a) docs layout, tree, app bar menu, footer, redirects, moving guide, problems and About; (b) getting started page and landing section; (c) teaching examples and the step lists, chapter pages; (d) the tour; (e) the 2.0/2.1 page. (a)–(c) are useful on their own; the tour (d) builds on them.

## 9. Open points (the lead's)

None. Settled in the first review:

1. **Where users download the submission template:** on lipidcompass.org (D11). The LipidCompass change that adds it is separate from this feature; the validator's release checks that the default URL answers before it ships, and otherwise configures it empty.

### Known issues found while building (not fixed here)

1. **`ValidateApiIntegrationTest` can hang a local `./mvnw verify`.** While this feature was built, the full server suite ran for more than 30 minutes inside this class, blocked in `EngineClient.call`. The machine was also running a separate validator stack with engines on 8084 and 8085. The class registers an in-process `EngineProxy` for its 2.0 engine, so the link to that stack is a suspicion, not a confirmed cause. The class predates this feature and its code was not changed. Local verification of this feature excluded it with `'-Dtest=!ValidateApiIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`; CI runs it. Next steps when it is picked up: take a thread dump of the hanging fork to see which URL the call waits on, and give the test a bounded `engines.job-timeout` so a hang fails fast instead of blocking the build.
2. **The problems page has no "On this page" list.** Section 5.1 gives every docs page one. The list reads the page's `h2[id]`/`h3[id]` headings; on the problems page the ids sit on each entry's `article`, not on its `h2`, so the list renders nothing. Deep links to entries (`/docs/problems#<id>`) do scroll once the entries from `/rest/v2/guidance` have rendered. A fix is to move the ids onto the headings, or to let the list read the `article` ids.
3. **The result viewer does not scroll to a highlighted metadata group, only to a key.** On a phone (390 px) tour steps 2–5 highlight a group that sits off-screen. Tracked as a GitHub issue.

## 10. Left to the builder

- How docs content is authored (Angular templates as today, or Markdown rendered at build time), as long as anchors are stable, labels come from shared constants and the content is covered by specs.
- The exact layout and styling of the docs shell, within the LIFS design and the existing tokens.
- The wording of steps and pages; the lead reviews it (CLAUDE.md: documentation review is requested explicitly).
