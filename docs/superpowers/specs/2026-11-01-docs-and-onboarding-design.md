# Documentation and Getting Started with mzTab-M

**Status:** draft for the project lead's review, 2026-11-01.
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

- The walkthrough comes as **both** a documentation chapter (the reference) and an **interactive tour** in the real document viewer ("Try it").
- People with a spreadsheet are pointed to the **LipidCompass submission template**, the most mature route today; more integrations will follow, so the page lists routes, not one tool.
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
- A getting-started hint that gets in the way of the user who just wants to validate: it is not a modal, never blocks the form, and stays dismissed.
- Breaking existing links: `/guide`, `/problems` and their `#…` anchors keep working.

## 3. Current state

- **Routes:** `/` landing (validator form), `/result/:id`, `/edit/:id`, `/about`, `/examples`, `/guide`, `/problems`. The app bar has Validate, Examples, REST API (Swagger UI), About and LIFS Tools; the footer links "Fixing a file" (`/guide`) and "Common problems" (`/problems`).
- **The guide** is one Angular template with `<h2 id="guide-…">` cards and `<h3>` subsections. Specs assert its key sentences and that it names the real button labels.
- **The problems page** lists the guidance catalog (`/rest/v2/guidance`).
- **Examples** come from `examples.properties`, which links to files on `raw.githubusercontent.com`; the Examples page validates one by id.
- **The document viewer** (library `mztabm-editor`: metadata view, table views) can already highlight a metadata entry, a group, rows, a cell or a column, open the group or tab, and scroll it into view. The result page and the editor drive it from messages (`targetOf`).

## 4. Decisions

| # | Decision | Whose |
|---|---|---|
| D1 | One **Documentation** item in the app bar, at `/docs`, replaces the footer-only access to the guide and problems page. | lead |
| D2 | Docs pages form a **tree** (§5.1): a side navigation of chapters and pages, and on each page a floating **"On this page"** list of its sections that follows the scroll position. | lead |
| D3 | The walkthrough exists as a **chapter** (annotated excerpts) **and** an **interactive tour** in the real viewer, sharing one step list. | lead |
| D4 | Spreadsheets → **LipidCompass submission template**, presented as one route among future ones. | lead |
| D5 | The teaching examples are the **cleaned HUPO-PSI lipidomics pair**, adapted (§5.4), plus the **2.1 study design example**. | lead |
| D6 | The teaching files are **bundled with the webapp**, not fetched from GitHub, so the tour's steps and the files cannot drift apart and the tour works offline from GitHub. The page names and links the HUPO-PSI originals. | my call |
| D7 | `/guide` and `/problems` **redirect** to their new places with their fragments; old anchors keep working. | my call |
| D8 | The landing hint is a **compact, dismissible card** next to the form, not a hero that pushes the form down; dismissal is remembered per browser. | my call |
| D9 | The bundled teaching files are **served by the server as examples** (classpath resources listed in the examples catalog, section `tutorial`), so the tour uses the existing `POST /examples/{id}/validations`. Each file's **step list lives next to it** and is served with the example. If the tour's result has expired, the tour validates the example again without asking: it is a public example, not user data. | my call |
| D10 | Chapter excerpts are **rendered from the bundled file**, not pasted: each step names its line range (`lines: [from, to]`) besides its target, and the chapter shows those lines. | my call |
| D11 | The **submission template's address is configuration** (`docs.submission-template-url`). Without it, the Getting started page describes the route and says where to ask, without a link. | my call |
| D12 | The **teaching files are authored by the builder** from the cleaned pair (#153 branch `fix/lipidomics-example-quirks`, validated on both engines on 2026-11-01), with masses computed from the formulas. Their scientific content is part of the lead's documentation review. | my call |

## 5. Design

### 5.1 Documentation structure

```
Documentation  (/docs)
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
│     What changed · Converting a 2.0 file to 2.1 · Validating 2.1 files
├── Fixing a file              /docs/fixing        (today's /guide)
├── Common problems            /docs/problems      (today's /problems)
└── For tools and services     /docs/integration
      REST API (link to Swagger UI) · Self-hosting (links to docs/self-hosting on GitHub)
```

- **Side navigation:** chapters and their pages, the current page marked. Below 1100 px it collapses into a "Contents" menu at the top of the page.
- **"On this page":** the current page's `h2`/`h3` sections, floating beside the text from 1100 px, with the section in view marked. Below that it is a collapsible list under the page title.
- **Previous/next** links at the end of each page follow the tree order.
- **Anchors:** every section has a stable id (`#metadata-ms-runs`, …). The guidance catalog, the resolution report and the problems page may link into the docs by these ids. The ids of today's guide (`#guide-result`, `#guide-fixing`, …) stay valid.
- **App bar:** Validate · Examples · **Documentation** · REST API · About · LIFS Tools. The footer keeps short links to Getting started, Fixing a file and Common problems.
- **Search** is not part of this design. The tree is small; browser find works on each page.

### 5.2 Getting started (landing hint and page)

**Landing hint (D8).** A small card beside the validator form (below it on narrow screens):

> **New to mzTab-M?** Learn the four parts of a file with a short walkthrough of a real lipidomics example. **[Get started]** · **[Take the tour]** · ✕

✕ hides it and stays hidden in this browser (local storage, read defensively; without storage, the card shows). The About page and the docs remain the way back.

**The Getting started page** answers "which route is mine":

- **"My software exported an mzTab-M file."** Validate it. Read the summary of what is wrong, then use Understanding mzTab-M to see where in the file it is, and Fixing a file to fix it here or in the tool.
- **"I have a spreadsheet of results."** Use the **LipidCompass submission template** (Excel, address from `docs.submission-template-url`, D11). It asks for the study, samples and lipids in a table; LipidCompass's curation pipeline converts it to validated mzTab-M (as the submissions repository describes it). Have ready: the lipid names (shorthand nomenclature), the quantities and their unit, the samples and their groups, the instrument and method. More routes (tool integrations) will be listed here as they become available.
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
- Links that point to `/guide` or `/problems` inside the app (result page, editor help, guidance panel, footer, resolution report) are updated to the new paths.

## 6. Documentation of this feature

This feature is documentation. In addition: the release notes (2.0.0, "Documentation" section) describe the new menu and the tour; `docs/self-hosting/configuration.md` notes that the teaching files are bundled (no outbound access needed).

## 7. Testing

- Navigation: the tree renders, the current page is marked, previous/next follow the tree, the "On this page" list marks the section in view (spec with a scroll stub), and the collapsed variants below 1100 px.
- Redirects: `/guide#guide-fixing` → `/docs/fixing#guide-fixing`; `/problems` → `/docs/problems`.
- Steps: every step's target resolves to an element in its example file (a spec per step list, loading the bundled file through the viewer's data source).
- Examples: the bundled files validate without errors or warnings on their engines (engine test on both lines).
- Tour (e2e): start from the landing hint, step through, each step highlights its element; version switch; close returns to the landing page. Axe checks on a docs page and the tour.
- Landing hint: dismiss persists; works without storage.
- Docs content specs: key sentences and labels from shared constants, as today.

## 8. Phasing

One phase, independent of the editor's 2b: (a) docs layout, tree, redirects, moving guide and problems; (b) getting started page and landing hint; (c) teaching examples and the step lists, chapter pages; (d) the tour; (e) the 2.0/2.1 page. (a)–(c) are useful on their own; the tour (d) builds on them.

## 9. Open points (the lead's)

1. **Where users download the submission template.** The template's current link is in the private repository `lifs-tools/lipidcompass-submissions`, so users cannot open it. It needs a public home (e.g. lipidcompass.org or a public release asset); until then the page ships without the link (D11).

## 10. Left to the builder

- How docs content is authored (Angular templates as today, or Markdown rendered at build time), as long as anchors are stable, labels come from shared constants and the content is covered by specs.
- The exact layout and styling of the docs shell, within the LIFS design and the existing tokens.
- The wording of steps and pages; the lead reviews it (CLAUDE.md: documentation review is requested explicitly).
