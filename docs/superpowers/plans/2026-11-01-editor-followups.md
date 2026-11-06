# Editor Follow-ups (lead's test) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the duplicated/empty guidance texts, let the editor select a message's element, remove metadata lines, and add a quick fix for Info-2057.

**Architecture:** Server: `GuidanceResolver` fallback, one catalog entry, one quick fix class in `workingcopy/quickfix`. SPA: guidance panel template; library `metadata-view` gets a remove action emitting `DeleteLine`; the edit page wires message selection to the existing jump/highlight inputs.

**Tech Stack:** Java 25 / Spring Boot, Angular 21 + Material 21, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-31-mztabm-editor-design.md` §5.10a (items 1–7), with §5.5, §5.6 and §5.10.

## Global Constraints

- SQL only as `private static final String` with `:named` parameters (StaticSqlTest).
- Every request parameter validated and rejected with 400, never clamped.
- The webapp never parses mzTab-M semantically and has no jmzTab-m dependency; quick fixes match by numeric code + message text.
- Existing endpoint contracts unchanged; OpenAPI changes additive only (regenerate `frontend/openapi/mztab-m-api.json` if a DTO changes).
- The library `frontend/projects/mztabm-editor` imports nothing from `projects/webapp`.
- Docs in the same change: guide page "Fixing a file", edit-page help, `docs/release-notes/2.0.0.md`.
- Quick fixes are tested against both engines (`QuickFixEngineTest`, 2.0 and 2.1).

## Review Focus

1. A catalog entry whose explanation equals the cause text: shown once, details toggle absent.
2. A 2057 message naming a column (not a metadata key): the fix deletes the right column by position, or is not offered when the position is unclear.
3. "Remove this line" on the last entry of a metadata group: the group view updates without error; undo restores the line byte-exact.
4. Selecting a message whose element is in a collapsed metadata group or another tab: the group/tab opens and the element is scrolled into view.
5. Selecting a second message after the first: the first highlight is cleared.

---

### Task 1: Guidance shows each text once, details folded, 2057 entry

**Files:**
- Modify: `server/src/main/java/org/lifstools/mztab/validator/webapp/guidance/GuidanceResolver.java` (fallback at ~122)
- Modify: `server/src/main/resources/guidance/catalog.json` (new entry `profile-field-mismatch`, codes `Info-2057`, `quickFix` `remove-mismatched-field` — the fix class arrives in Task 2; if the catalog test requires the fix id to exist, add the entry's `quickFix` in Task 2 instead)
- Modify: `frontend/projects/webapp/src/app/pages/result/guidance-panel.component.{html,ts,scss,spec.ts}`
- Test: `GuidanceResolverTest`, `ResolutionReportTest`, `GuidanceCatalogTest`

- [ ] **Step 1: Failing tests**
  - `GuidanceResolverTest.fallbackDoesNotRepeatTheCause`: a message with no catalog match → `explanation` is the cause, `cause` is null.
  - `ResolutionReportTest`: a catalog-matched group with a cause → the Markdown contains the cause once (`Cause: …`); a fallback group → its text once.
  - guidance-panel spec: explanation equal to cause → the text appears once and there is no details toggle; a catalog guidance with a different cause → cause is inside a collapsed `<details>` with summary "Details from the validator"; `fixInTool` empty and no tool variant → no "Fix it in your tool" heading; `fixHere` empty → no "Fix it here" heading.
  - `GuidanceCatalogTest`: `Info-2057` resolves to `profile-field-mismatch`.
- [ ] **Step 2: Run them, see them fail** — `./mvnw -B -pl server -am test -Dfrontend.skip=true -DskipSnapshotEngines -Dtest='GuidanceResolverTest,ResolutionReportTest,GuidanceCatalogTest' -Dsurefire.failIfNoSpecifiedTests=false`; `cd frontend && npm run generate:api && npx ng test webapp --watch=false --include='**/guidance-panel*'`
- [ ] **Step 3: Implement.** Catalog entry text (exact):
  - title: `Field or column without its section`
  - explanation: `Some metadata fields and columns are only needed together with a particular table, such as the small molecule feature (SMF) section. The file declares a profile without that section but still contains such a field or column. The file stays readable; the validator reports the mismatch so the content matches the declared profile.`
  - fixHere: `Use the quick fix to remove the field or column, or remove the line with "Remove this line". If the section is meant to be there, add it in your tool and export again.`
  - fixInTool: `Write conditional fields and columns only when the section they belong to is written, or declare the profile that includes that section.`
- [ ] **Step 4: Run the tests, see them pass.**
- [ ] **Step 5: Commit** — "Guidance shows each text once; validator wording folded; guidance for 2057"

### Task 2: Remove a metadata line; quick fix for 2057

**Files:**
- Modify: library `metadata-view.component.{ts,html,scss}` + spec (`metadata-view-editing.spec.ts`)
- Create: `server/.../workingcopy/quickfix/RemoveMismatchedField.java`; register in `QuickFixes`
- Modify: `QuickFixesTest`, `QuickFixEngineTest` (+ fixtures `engine-remove-mismatched-field.mzTab` for 2.0 and its 2.1 variant, built from `jmzTab-m/test-utils/src/main/resources/metabolomics/official-profile-ms.mztab` reduced to the smallest file that still gets 2057), SPA `FIXES_FOR_ALL` if the fix applies to all
- Modify: `catalog.json` entry's `quickFix` if deferred from Task 1

- [ ] **Step 1: Failing tests**
  - metadata-view: editable view shows a "Remove this line" button per entry (accessible name `Remove line <n>`); clicking emits a `DeleteLine` edit for that entry's line number; read-only view shows none.
  - `QuickFixesTest.removeMismatchedFieldDeletesTheMetadataLine` (preview has one `DeleteLine` of the named line) and `…DeletesTheNamedColumn` (one `DeleteColumn` with the column's position); a 2057 message whose key/column cannot be found → fix not offered (400 on preview, like other inapplicable fixes); `all=true` over several messages → one op per distinct line/column, line-moving ops bottom-up.
  - `QuickFixEngineTest`: applying the preview on the fixture → the engine no longer reports 2057, on 2.0 and 2.1.
- [ ] **Step 2: Run them, see them fail.**
- [ ] **Step 3: Implement.** Match 2057 by numeric code; take the field/column name from the message text (quoted name before ` is present`), find the line by MTD key or the column in SMH/SFH/SEH by exact name; if the name occurs in more than one column, use the message's column position when present, else do not offer the fix.
- [ ] **Step 4: Run them, see them pass.**
- [ ] **Step 5: Commit** — "Remove a metadata line in the editor; quick fix for fields without their section"

### Task 3: Selecting a message selects its element; e2e; docs

**Files:**
- Modify: `frontend/projects/webapp/src/app/pages/edit/edit.page.{ts,html}` + `edit.page.spec.ts`; `messages-tab.component.*` if selection is emitted there
- Create: `frontend/e2e/fixtures/profile-field-mismatch.mzTab` (the Task 2 fixture), extend `frontend/e2e/editor.spec.ts`
- Modify: guide page "Fixing a file" + spec, edit-page help, `docs/release-notes/2.0.0.md`

- [ ] **Step 1: Reproduce first.** Write the edit-page spec that selects a 2057 group for `small_molecule_feature-quantification_unit` (metadata group in a collapsed panel) and asserts the entry is highlighted and its panel open, without pressing "Show". Find why it fails today (target resolution of the key's group, selection vs. "Show", or the highlight input not replayed) before changing code.
- [ ] **Step 2: Failing tests** for spec §5.10a item 5 and Review Focus 4–5: first selection jumps; a second selection clears the first highlight; a message with a line but no element offers "Edit this line as text".
- [ ] **Step 3: Implement.**
- [ ] **Step 4: e2e** `editor.spec.ts`: upload the fixture, open the editor, select the 2057 message → entry highlighted → "Remove this line" → after the recheck the message is marked fixed → undo brings the line back.
- [ ] **Step 5: Docs** — guide "Fixing a file" (selecting a message selects its element; "Remove this line"), edit-page help, release notes "Fix a file in the browser" bullets.
- [ ] **Step 6: Run** `cd frontend && npm test`, `./mvnw -B verify`, e2e per `docs/self-hosting/building.md`. **Commit** — "Selecting a message selects its element in the editor"
