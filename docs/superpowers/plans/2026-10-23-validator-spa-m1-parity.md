# Validator SPA 2.0.0: parity checklist

Source: spec §2 goal 1 and the parent design §3.2b (mzTab 1.0 excluded). Items marked `[x]` were verified **locally** against the packaged jar (`server/target/mztab-validator-webapp-2.0.0-SNAPSHOT.jar`, port 8083, context path `/mztabvalidator`, 2026-10-23). They are **not** yet verified on `/mztabvalidator-dev`; repeat the checklist there (see `docs/release/2.0.0-deployment.md`) before the production switch. Items left `[ ]` need a browser interaction or the dev stack.

- [ ] **Landing page with the validator form** (file upload, level, max errors, semantic validation, profile, custom mapping upload). How to check: open `/`, submit a file with each option set. (Local: `/` returns 200 and the form renders; interactive pass left to -dev; the Playwright e2e suite covers the form.)
- [x] **Asynchronous validation with progress state.** How to check: submit a large file; the status moves QUEUED, then FINISHED. Local: `POST /rest/v2/validations` returned QUEUED, polling reached FINISHED.
- [x] **Result page: summary and message table.** How to check: open `/result/{id}`; verdict banner, counts and filterable messages. Local: screenshot of the result page of the large file shows verdict "passed with notes" and the three messages.
- [x] **Result page: read-only sections** (metadata, SML summary, SMF features, SME evidence). How to check: switch the tabs, page, sort, filter, shift the column window. Local: metadata 97,256 lines, summary 846 x 1,989; endpoints respond (see large-file check).
- [x] **Delete a result.** How to check: "Delete now" on the result page, result then 404. Local: `DELETE /rest/v2/validations/{id}` returned 204, then 404; `GET /validate/{id}/delete` redirects to `/result/{id}?confirmDelete=true` and does not delete.
- [x] **Examples page.** How to check: `/examples` lists the examples and "validate" starts one (needs outbound access to GitHub). Local: page 200, `/rest/v2/examples` 200; live validation of an example not run.
- [x] **About page.** How to check: `/about`. Local: 200.
- [x] **Error and not-found pages.** How to check: `/nonexistent/page` shows the SPA not-found page; upload a file over 64 MB shows the too-large error. Local: unknown route served by the SPA (200 with the not-found view); the 413 JSON path is covered by server tests.
- [x] **Retention notice.** How to check: the result page shows "kept until {date}" (7 days). Local: screenshot shows "Kept until 30 Oct 2026".
- [ ] **LIFS branding and Matomo with consent.** How to check: nav and footer with LIFS and funder logos; on -dev (where `ga.id` is set) the footer "Analytics" link opens the consent choice and no tracker request is sent before consent. Local: branding verified in the screenshot; Matomo not configured locally (`analytics: null` in `/rest/v2/app-info`).
- [x] **Old URLs.** How to check: `/validate/{id}` redirects to `/result/{id}`. Local: 302 as expected.
- [x] **REST contracts unchanged.** How to check: the Swagger UI and `/v3/api-docs` load; `/rest/v2/validate` and `/validatePlain` respond as before (script `lipidcompass-submissions/src/curation/validate.py` against -dev). Local: `/v3/api-docs` 200, `/swagger-ui.html` redirects to the UI; the contract tests pass in `./mvnw verify`.
- [ ] **Real files from the standalone design §3.3** validated on -dev (including the 32 MB file with the 846 x 1,990 table, in Chrome: page through Summary and shift the column window five times without the tab freezing). Local part below; the in-browser interaction is left to -dev.
- [ ] **Heap stays below the container memory limit** while validating LCS-00050 and paging Summary on -dev. How to check: watch `docker stats` for the `mztabvalidator-dev` container during the large-file check; memory use stays below the 2048M limit and the container is not restarted (the image runs with `-XX:MaxRAMPercentage=75`).

## Large-file check (local jar, no profile)

File: `LCS-00050-01.mzTab` of lipidcompass-submissions (private, not stored in this repository). Local jar, default heap, Apple silicon laptop, empty temporary storage that was deleted afterwards.

| Measure | Result |
|---|---|
| File size | 31,154,686 bytes (31.2 MB) |
| Time to FINISHED | 2.16 s on the server (`startedAt` to `finishedAt`); 2.49 s measured by the client including 0.5 s polling |
| Outcome | `PASSED_WITH_NOTES` (3 INFO messages), mzTab-M 2.0.0-M |
| SML table | 846 rows x 1,989 columns (SMH has 1,990 fields including the row prefix) |
| `SECTIONS/sml.jsonl` | 29,852,615 bytes (29.9 MB) |
| 5 consecutive `GET .../tables/sml?page=N&size=50` (N=1..5) | 5.8, 4.2, 4.9, 6.5, 8.8 ms (about 92 KB each) |
| 5 column-window shifts (`columns=` SML_ID plus 50 columns, windows 1-50, 51-100, ...) | 5.7, 3.9, 4.8, 3.5, 4.2 ms (about 95 KB each) |
| Target | < 1 s each: **met** (all under 10 ms) |

The headless-browser screenshot of the result page rendered the tab labels "Metadata 97,256 lines" and "Summary 846 x 1,989". The tab-freeze check in an interactive Chrome session (paging and shifting the column window five times) has not been done by this task; the server responses are well under the target, so remaining risk is client-side rendering.
