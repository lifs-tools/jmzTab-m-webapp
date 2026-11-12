# Editor Before Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Settle what must be done before phase 2a is deployed: the two items design §6 names as deployment gates, and the defects found in the 2a reviews of 2026-11-08 that break the editor for normal sessions.

**Architecture:** Server: copy runs get their own bounded executor; a per-copy resolution report built from the copy's last validation. SPA: the store starts a run for a reopened copy whose last run is stale; baselines and run kinds no longer mark every message New or drop the CV results.

**Tech Stack:** Java 25 / Spring Boot, Angular 21 + Material 21, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-31-mztabm-editor-design.md` §6 "Carried into the next phase" items 1 and 2, §5.4, §5.9. Review findings of 2026-11-08 (UAT review D1, D2; architecture review, see Task 5).

**Not in this plan (recorded for the next phase, design §6):** UAT D3 (failed save shown as Saved, no Retry, 503 reason lost — partly covered by Tasks 1 and 5c), D4 (typed text dropped on blur/Tab; no unsaved-change warning), D5 (Fix this file does not say a copy exists; Delete now does not name it; same download names; no discard), D6 (no axe check on /edit; focus after Remove and into the raw editor), D7 (raw editor wording, Undo does not say what it undid).

## Global Constraints

- SQL only as `private static final String` with `:named` parameters (StaticSqlTest).
- Every request parameter validated and rejected with 400, never clamped.
- Existing endpoint contracts unchanged; OpenAPI changes additive only (regenerate `frontend/openapi/mztab-m-api.json`).
- Docs in the same change: edit-page help, guide "Fixing a file", `docs/release-notes/2.0.0.md`, `docs/release/2.0.0-deployment.md` (gates ticked).
- Both engines in tests that run a validation (`EngineTestSupport`).

## Review Focus

1. Many copies validating at once: a plain validation (upload) still starts within its normal time; copy runs queue in their own pool and a full queue answers 503 with `Retry-After`, as edit batches do.
2. The per-copy report's line numbers are those of the copy's current revision; a stale or running validation is said so in the report, never presented as current.
3. Reopening `/edit/:id` after any interruption (reload during the idle wait, failed check, server restart) ends in a finished check, never in "Checking…" for good.
4. With a validation profile: an unedited copy shows no "New" message; a structural run never replaces a pending or finished CV check silently; the page says when the CV rules were last checked.

---

### Task 1: Copy runs get their own bounded executor (§6 item 1)

**Files:**
- Modify: `server/src/main/java/org/lifstools/mztab/validator/webapp/WebConfig.java` (new bean `workingCopyRunExecutor`)
- Modify: `server/.../workingcopy/WorkingCopyService.java` (constructor `@Qualifier`, ~225; submission of runs)
- Modify: `server/src/main/resources/application.properties` + `docs/self-hosting/configuration.md` (`workingcopy.runs.threads`, `workingcopy.runs.queue`)
- Test: `WorkingCopyServiceTest`, new `WorkingCopyRunExecutorTest`

- [ ] **Step 1: Failing tests**
  - `WorkingCopyRunExecutorTest.copyRunsDoNotUseTheValidationPool`: the service's executor is not `toolThreadPoolTaskExecutor`.
  - `…aFullRunQueueAnswers503`: with threads=1, queue=1 and a blocked engine, a third copy asking for a run gets `WorkingCopyBusyException` (mapped to 503 with `Retry-After`, as the edit queue does); the copy's state stays as before (no `running` shown).
  - `…uploadValidationsStartWhileCopiesRun`: with the copy pool saturated, a session validation through `ValidationRunner` starts (does not wait on the copy pool).
- [ ] **Step 2: Run them, see them fail** — `./mvnw -B -pl server -am test -Dfrontend.skip=true -DskipSnapshotEngines -Dtest='WorkingCopyRunExecutorTest,WorkingCopyServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement.** Defaults: threads `max(1, CPUs/2)`, queue 16 (measure: one 31 MB structural run is ~1.5 s, design §9). Validate the properties at startup (> 0), fail fast otherwise. The SPA shows the 503's message on a check (UAT D3: today it shows only "could not be checked") and retries after `Retry-After`.
- [ ] **Step 4: Run, see them pass.**
- [ ] **Step 5: Commit** — "Copy runs get their own bounded pool; a full queue answers 503"

### Task 2: Per-copy resolution report (§6 item 2, §5.9)

**Files:**
- Modify: `server/.../guidance/ResolutionReport.java` (overload taking file name, version, profile and the copy's messages instead of a `ValidationRecord`)
- Modify: `server/src/main/java/org/lifstools/mztab2/server/api/validation/WorkingCopiesApiController.java` (`GET /{id}/report?format=md`)
- Modify: SPA `pages/edit/edit.page.{ts,html}` (link "Download report" → the copy's report; keep "report of the upload" on the result page only), help, guide, release notes (`2.0.0.md:25` says the editor offers only the upload's report)
- Test: `ResolutionReportTest`, `WorkingCopiesApiTest`, `edit.page.spec.ts`, e2e `editor.spec.ts`

- [ ] **Step 1: Failing tests**
  - `WorkingCopiesApiTest.reportOfTheCopyUsesItsLines`: edit so that a message's line moves (DeleteLine above it), run, fetch the report → the line number is the copy's, not the upload's.
  - `…reportSaysWhenTheCheckIsNotCurrent`: after an edit without a finished run → the report's header says the check is of revision n and the copy is at n+1 (or 409 — builder decides; the SPA then offers it only when current).
  - `…reportOfAnUncheckedCopy` → 409 with a message, no empty report.
  - `…unknownFormatIs400`.
  - edit.page spec: the report link points to `rest/v2/working-copies/{id}/report?format=md` and is labelled "Download report".
  - e2e: download the report after a quick fix → the fixed message is absent.
- [ ] **Step 2: Run them, see them fail.**
- [ ] **Step 3: Implement.** The report names the copy (file name, "corrected copy", revision, time of the check, structural or with CV rules) and the guidance of each message group, as the upload's report does.
- [ ] **Step 4: Run, see them pass.**
- [ ] **Step 5: Commit** — "Editor: a resolution report of the copy, with its own line numbers"

### Task 3: A reopened copy always gets a current check (UAT D1)

**Confirmed in code (2026-11-08):** `working-copy.store.ts` (create, ~238) starts a run only when `!info.validation`; `WorkingCopyService.info` (~438) marks the last validation `stale` when its revision is older; `validating` (~129) treats `stale` as pending. Reloading within the 1.5 s idle wait, after a failed check, or after a server restart (runs live in memory) leaves "Checking your latest changes…" for good, quick fixes disabled and JSON off.

**Files:** `pages/edit/working-copy.store.ts` + `working-copy.store.spec.ts`; e2e `editor.spec.ts`

- [ ] **Step 1: Failing tests** — store spec: opening a copy whose validation is stale and not RUNNING requests a run of the same kind as the last one; one that is RUNNING polls; one that is current requests nothing. e2e: edit a cell, reload at once → the check finishes.
- [ ] **Step 2–4:** fail, implement, pass.
- [ ] **Step 5: Commit** — "Editor: a reopened copy whose check is out of date is checked again"

### Task 4: Messages and CV checks with a validation profile (UAT D2)

**Confirmed in code (2026-11-08):** `WorkingCopyService.create` (~316-321) stores the session's messages only as the baseline of the session's kind (semantic with a profile); the editor's first run is structural (`store.ts` ~238) and compares with an empty structural baseline, so every message of an unedited copy shows **New**, and the CV messages from the result page disappear. The idle run after an edit is structural and replaces a semantic result; a "Check CV rules" pressed within the idle wait is downgraded (`run.againSemantic = semantic`, ~599; the idle timer is not cancelled).

**Files:** `WorkingCopyService.java`, `MessageDiff` (if needed); `working-copy.store.ts`, `edit.page.{ts,html}` + specs; e2e with the default profile.

- [ ] **Step 1: Failing tests**
  - `WorkingCopyServiceTest.firstStructuralRunOfAProfiledSessionMarksNothingNew`: the baseline for a structural run of a profiled session is the session's messages without the semantic ones (or the copy's first run is of the session's kind — builder decides; the result must be: an unedited copy shows no New).
  - store spec: a structural idle run never replaces a pending semantic request; "Check CV rules" cancels a pending idle run and runs semantic.
  - edit.page spec: the status line says "CV rules checked at revision n" or "CV rules not checked since your last change".
  - e2e: validate with the default profile → Fix this file → no "New" badge on any card.
- [ ] **Step 2–4:** fail, implement, pass.
- [ ] **Step 5: Docs** — guide "Fixing a file" (structural check after each change, CV rules on request, what the status line says). **Commit** — "Editor: profiled sessions start without false New messages; CV checks are not replaced silently"

### Task 5: Findings of the architecture review (2026-11-08)

The review found no path that corrupts the server's history or breaks byte-exact undo (one IMMEDIATE transaction per write with the revision check inside it, exact line images, snapshot reads, generation checks). The risks are in what the SPA targets and what the API reports. Each item: failing test first.

**5a. A cell edit can land in the wrong column after a column change (high).** Confirmed in code: `LINE_OPS` (`working-copy.store.ts:47`) holds only `DeleteLine`, `InsertLine`, `MoveRow`, `JoinWithPrevious`; `DeleteColumn`, `InsertColumn`, `MergeColumns`, `SplitCells` and `SetRawLine` on a table row do not raise the line-move revision. A table page read before such an edit stays editable while its reload is in flight (indefinitely if the reload fails: `table-view.component.ts` keeps `data()`, `startEdit` checks neither loading nor error) and its `SetCell(line, cell)` is sent at the new revision, overwriting the column that moved into that position (`WorkingCopyEditor.setCell` ~369 has no precondition).
- [ ] Tests: store spec "edits behind a column change" (each column op raises the layout revision; a SetCell read before it is refused with the conflict notice); table-view spec (no edit while the page reloads or after a failed reload; Retry offered); server `WorkingCopyEditorTest.setCellRefusesAChangedHeader` if the precondition below is chosen.
- [ ] Implement: rename the line-move revision to a layout revision counting column ops, `SplitCells` and `SetRawLine` on table rows; and server-side, an optional precondition on `SetCell` (expected header name of the column, or the expected old value) refused with 409. The duplicate-column dialog sends `readAt` and the header line's expected text.

**5b. Message actions use the current revision instead of the messages' revision (medium; worse than §6 item 8 says).** Confirmed in code: `editAsText` and `inspect` stamp `readAt = store.currentRevision()` (`edit.page.ts:319`, `:363`), and while a run is RUNNING `WorkingCopyService.info` (~424-440) returns the previous run's messages labelled with the new run's revision and `stale=false`. After a line move, "Edit this line as text" opens and saves whatever line now has that number, with no 409.
- [ ] Tests: `WorkingCopyServiceTest.runningInfoKeepsTheMessagesRevision`; edit.page spec: after a DeleteLine above a message's line, "Edit this line as text" from that message gets the conflict notice, not the moved line.
- [ ] Implement: `WorkingCopyValidation.messagesRevision` (additive in the OpenAPI); every action started from a message passes it as `readAt`.

**5c. A saved edit reported as failed (medium-low).** `WorkingCopyService.edit` commits, then `changed()` writes `META_CHANGED` outside the transaction (~540), which can fail with SQLITE_BUSY → 503; a lost response has the same effect. The view says "could not be saved", reloads showing the change, the client revision does not advance, and the next edit gets a 409 "changed in another tab".
- [ ] Tests: service test with a failing post-commit step → the response still reports the committed revision; store spec: after a non-409 error the store re-reads the copy's info before saying the edit failed.
- [ ] Implement: `META_CHANGED` inside the editor's transaction; the response carries the committed revision (not re-read); the client re-reads info on a non-409 error.

**5d. Small (fix in passing):** reject unpaired UTF-16 surrogates with 400 (stored as `?` by sqlite-jdbc; probe 2026-11-08) and count `textChars` without SQLite `LENGTH()` (stops at NUL); the revision check also compares the copy's generation (a deleted and re-created copy restarts at 0).

"Download .mzTab" while saving (review 5, UAT D4) is in Task 6's checklist: disable it while edits are queued or in flight, and return the revision in a response header.

### Task 6: Deployment checklist

- [ ] "Download .mzTab" is disabled while edits are queued or in flight; `/file` returns the revision in `X-Working-Copy-Revision` (test in edit.page spec and `WorkingCopiesApiTest`).
- [ ] `docs/release/2.0.0-deployment.md`: add the gates of Tasks 1–5 with the check that proves each (test name or manual step), and the persistent storage volume of both deployments (design §6 "Done when").
- [ ] Run `cd frontend && npm test`, `./mvnw -B verify`, e2e per `docs/self-hosting/building.md`, with `engine21.jar` present (design §6 item 7: a missing 2.1 jar must fail, not skip — fix `EngineTestSupport.has21()` here if not done).
- [ ] **Commit** — "Release: deployment gates for the editor"
