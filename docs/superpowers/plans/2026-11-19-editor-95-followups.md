# Editor #95 Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close issue #95 by adding a client timeout and retry policy for editor requests and fixing its seven follow-ups.

**Architecture:**
- The server gains two things: a distinct 409 code for "nothing to undo or redo", and `WorkingCopyInfo.reportRevision`. It also logs a full run queue correctly, and the run loop moves out of `WorkingCopyService` into its own class with no change in behaviour.
- The SPA store handles that code without a conflict, notices a replaced copy in `committedOrFailed()`, checks a replaced copy at once, and enables the report link from `reportRevision`.
- An HTTP interceptor applies a per-request timeout carried in the `HttpContext`, and maps a timeout to a lost answer (status 0), so the store's existing lost-answer handling takes over.

**Tech Stack:** Java 25 and Spring Boot 3.5 (server), Angular with signals and Vitest (SPA).

**Spec:** issue #95 ("Editor (2.0.0): follow-ups from the pre-deploy review, and a client timeout to decide"). The timeout decision is the one taken in conversation on 2026-11-19 and recorded under Global Constraints. Design background: `docs/superpowers/specs/` (editor and working copies).

## Global Constraints

- **Timeout (#95 decision):**
  - Every editor request gets a client timeout of **30 s**.
  - The import preview (`previewWorkingCopyImport`) and still missing (`getWorkingCopyStillMissing`) get **120 s**, because they wait for the engine.
  - Requests outside the editor (upload, synchronous validation, conversion) get no client timeout.
  - A timeout is reported as an `HttpErrorResponse` with status 0 and `statusText` `'Timeout'`.
- **Retry (#95 decision):**
  - Reads (`getWorkingCopy` in the poll, `reread`, `reload`, `conflict()` and `committedOrFailed()`, and the page and table reads in `working-copy-source.ts` and `edit.page.ts`) retry up to **2** times on status 0 only, after **1 s** and then **3 s**.
  - Edits, undo and redo are never resent blindly. A timeout goes through `committedOrFailed()`. If the re-read shows that the change was not applied, the store resends it **once**: that is when `lastChangeId` is not the change's, the revision is still the one sent, and the generation is unchanged.
  - Engine-backed requests are not retried automatically; the existing "Try again" stays.
  - The existing 503 Retry-After handling is unchanged.
- **Never two applies:** every change carries its revision, so a resend after a commit is refused (409). Tests must show that no change is applied twice.
- **New error code:** `nothing_to_undo`, a 409 with the `ErrorResponse` code field, used the way the existing `copy_replaced` code is.
- **`WorkingCopyInfo.reportRevision`:** a nullable `Long` added as the last component. It is the revision of the run whose report `GET /{id}/report` would serve now, or null if it would answer 409. Old clients ignore it.
- **No behaviour change in the split (Task 3):** all existing tests pass unchanged, apart from imports and package-private hooks moving with the code.
- **Copy:**
  - Nothing to undo or redo is handled quietly: no notice, and the buttons update from the re-read.
  - A full run queue is logged as "the run queue is full".
- **Build:**
  - Webapp builds run offline: `./mvnw -B -o -nsu …`, always with `-am` when `-pl` is used. Never run `install`.
  - Frontend: `cd frontend && npm test`, plus `npx ng build webapp`.
  - Regenerate `frontend/openapi/mztab-m-api.json` (`OpenApiSpecExportTest`) when the API changes, then run `npm run generate:api`.
- **Commits:** headers "Copyright 2026 LIFS Tools Consortium." with the Apache 2.0 block for new files. The trailers come from the session.

## Review Focus

1. A request that times out after the server committed it is never applied twice. The resend is refused (409) and goes through the conflict path.
2. "Nothing to undo" from a second queued undo keeps queued edits and shows no "another tab" notice.
3. A copy deleted and created again while a save is in flight is announced once, as replaced, and is checked at once.
4. When a re-read fails after its retries, the editor never hangs: the failure surfaces and the next action works.
5. Timeouts never apply to uploads or synchronous validation.

---

### Task 1: Server: nothing_to_undo, reportRevision, full-queue log

**Files:**
- Modify:
  - `server/.../workingcopy/WorkingCopyService.java` (undoOrRedo ~892; `info()` ~689-723; run submit ~996-1000)
  - `server/.../workingcopy/WorkingCopyInfo.java`
  - `server/.../api/validation/RestErrorAdvice.java` (~91, ~119-130, ~184-200)
  - `WorkingCopiesApiController` (OpenAPI text for undo and redo)
  - `frontend/openapi/mztab-m-api.json`
- Test: the existing undo/redo API tests, `WorkingCopyServiceTest` (or the nearest existing class for `info()`), and a `RestErrorAdvice` test with Spring's `OutputCaptureExtension`.

**Interfaces:**
- Produces:
  - 409 bodies with code `nothing_to_undo` for undo or redo with nothing to do;
  - `WorkingCopyInfo.reportRevision` (`@Nullable Long`, last component);
  - `RestErrorAdvice` logs "the run queue is full" when the cause is a `RejectedExecutionException`.

- [ ] Tests first:
  - `undoWithNothingToUndoIs409NothingToUndo` (and the same for redo): assert status 409 and code `nothing_to_undo`.
  - `reportRevisionIsTheLastDoneRun`:
    - null before any run;
    - the run's revision after a DONE run;
    - still that revision while a later run is RUNNING, and after a later run FAILED;
    - it agrees with `GET /report` answering 200 or 409 in each state.
  - `aFullRunQueueIsLoggedAsSuch`: the captured log contains "the run queue is full" and not "stayed locked".
- [ ] Implement:
  - a `NothingToUndoException` subclass of `WorkingCopyConflictException`, mapped to 409 code `nothing_to_undo`;
  - `info()` reads the same state `resolutionReport` uses and fills `reportRevision`;
  - `copyBusy` branches on the cause.
- [ ] Regenerate the OpenAPI JSON and run the server suite.
- [ ] Commit: "Working copies: nothing to undo has its own code; info names the report's revision; a full run queue is logged as such".

### Task 2: Deterministic run-executor tests

**Files:** Modify `server/src/test/java/.../workingcopy/WorkingCopyRunExecutorTest.java`. Allow package-private test hooks in `WorkingCopyService` only if needed.

- [ ] `uploadValidationsStartWhileCopiesRun` must discriminate on any CPU count:
  - set the copy-run executor's thread and queue sizes explicitly in the test;
  - hold every copy-run thread with `EngineProxy` gates;
  - then assert that the upload validation finishes while they are held.
- [ ] `aBusyEnginesRetry…` uses no `Thread.sleep` for ordering. It awaits a condition with a deadline (a hook or a polled state), and asserts RUNNING after the retry has demonstrably happened.
- [ ] Both tests pass 10 times in a row: `-Dsurefire.rerunFailingTestsCount=0`, in a loop.
- [ ] Commit: "WorkingCopyRunExecutorTest: deterministic on any host; no sleeps for ordering".

### Task 3: Split the run loop out of WorkingCopyService

**Files:**
- Create: `server/.../workingcopy/WorkingCopyRuns.java` (package-private).
- Modify: `WorkingCopyService.java`.

**Interfaces:**
- Produces: `WorkingCopyRuns`, holding the `Run` class, `requestValidation`, `deadline`, `submit`, `runLoop`, `schedule`, `abandon`, `validateOnce`, `profileMismatch`, `mappingFile`, `done`, `readUpTo`, `persist`, `persistQuietly`, `ofKind`/`baseline`, and the `beforeRunEnds` hook.
- `WorkingCopyService` keeps its public API and delegates to it. It uses `markChanged(id, rev)`, `snapshot(id)` and `isRunning(id)` where it read `runs` before (`changed()`, `info()`, `resolutionReport`).
- Shared helpers (`openQuietly`, `revisionQuietly`, `settings`, `parseValidation`, `lastValidation`) stay in the service or in a small shared helper, whichever gives no cycle.

- [ ] A pure move with no behaviour change.
- [ ] The full server suite passes unchanged, apart from imports and hooks moving.
- [ ] ArchUnit tests still pass.
- [ ] Report the new line counts.
- [ ] Commit: "Working copies: the run loop in its own class".

### Task 4: Store: nothing to undo, a replaced copy on a failed save, a check on replacement, report link

**Files:**
- Modify: `frontend/projects/webapp/src/app/pages/edit/working-copy.store.ts`, `edit.page.ts|html`.
- Test: `working-copy.store.spec.ts`, `edit.page.spec.ts`.

**Interfaces:**
- Consumes: from Task 1, `nothing_to_undo` and `WorkingCopyInfo.reportRevision`.

- [ ] Specs first:
  - `aSecondUndoWithNothingToUndoKeepsQueuedEdits`: no `reloads++`, no notice, an edit queued behind it is sent, and `canUndo` is updated from a quiet re-read.
  - `aFailedSaveThatFindsACopyCreatedAgainAtALowerRevisionAnnouncesIt`: the `COPY_REPLACED_NOTICE` is shown once, and `committedOrFailed()` takes the replaced branch when the generation differs, whatever the revision.
  - `aReplacedCopyWithoutARunIsCheckedAtOnce`: replaced through `conflict()` and through the poll, each requests one check, and nothing loops.
  - `theReportLinkFollowsReportRevision`: reopening a copy whose `reportRevision` is set while a later run is RUNNING enables the link.
- [ ] Implement:
  - handle the code at the 409 branch in `enqueue`;
  - in `committedOrFailed()`, test the generation before `now <= sent`;
  - in `setInfo`'s replaced branch, call `checkOnOpen(info)`;
  - set `reportReady` from `reportRevision != null || DONE`.
- [ ] `npm test`, then commit: "Editor: nothing to undo is not a conflict; a replaced copy is noticed on a failed save and checked at once; the report link follows the server".

### Task 5: Timeout and retry for editor requests

**Files:**
- Create: `frontend/projects/webapp/src/app/core/request-timeout.interceptor.ts` (+ spec), with `REQUEST_TIMEOUT` (`HttpContextToken<number | null>`, default null) and helpers `editorRequest(timeoutMs = 30_000)` and `engineRequest()` (120 s) that combine with `quietRequest()`.
- Modify:
  - `app.config.ts`;
  - `working-copy.store.ts`;
  - `working-copy-source.ts`;
  - `edit.page.ts` (its line and table reads).

**Interfaces:**
- Produces: a request with `REQUEST_TIMEOUT` set errors after that time with `HttpErrorResponse({status: 0, statusText: 'Timeout'})`. Requests without it are unaffected.

- [ ] Specs first:
  - Interceptor:
    - times out at the token's value;
    - leaves a request without the token alone;
    - the error is status 0 / 'Timeout'.
  - Store, using fake timers:
    - a re-read that times out is retried after 1 s, then 3 s, then fails; on failure the editor surfaces it, and the next edit is still sent;
    - an edit that times out, whose re-read shows it applied (lastChangeId), is answered from the re-read and not resent;
    - an edit whose re-read shows it not applied is resent once, and a second timeout reports the failure;
    - a resend refused with 409 goes through the conflict path, and there is no double apply;
    - the import preview and still missing use 120 s and are not retried;
    - upload, validate and convert requests carry no token.
- [ ] Implement:
  - the interceptor;
  - the helpers;
  - a `retryReads` operator (status 0 only, delays of 1 s and 3 s);
  - the resend-once in `enqueue` after `committedOrFailed()`.
- [ ] `npm test`, `npx ng build webapp`. Add a release-notes bullet under the unreleased section: "The editor no longer waits forever for a server that does not answer". It must not mention LipidCompass.
- [ ] Commit: "Editor: a client timeout for editor requests; reads retried, a change resent only when it was not applied".
