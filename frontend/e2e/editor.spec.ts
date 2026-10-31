/*
 * Copyright 2026 LIFS Tools Consortium.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { APIRequestContext, Download, expect, Page, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { awaitEnd, chooseFile, chooseNoProfile, FIXTURES, MZTAB_20, submitAndAwaitResult, UUID } from './helpers';

/** lipidomics-example.mzTab with an empty optional parameter "[, , , ]" (line 24) and a DOI written as a URL (line 5). */
const FIX_ME = join(FIXTURES, 'fix-me.mzTab');
/** A valid mzTab-M 2.0 file whose line 11 is broken in two: line 12 has no prefix. */
const BROKEN_LINE = join(FIXTURES, 'broken-line.mzTab');
/** lipidomics-example.mzTab with opt_global_lipid_category twice in SMH (cells 17 and 20); the row disagrees. */
const DUPLICATE_COLUMN = join(FIXTURES, 'duplicate-column.mzTab');

/** Structural validation through the API, as a script would start one. */
async function validate(request: APIRequestContext, file: string, name: string): Promise<string> {
  const response = await request.post('rest/v2/validations', {
    multipart: { file: { name, mimeType: 'text/plain', buffer: readFileSync(file) }, level: 'INFO' },
  });
  expect(response.status()).toBe(202);
  const id = (await response.json()).id as string;
  await awaitEnd(request, id);
  return id;
}

/** Result page → "Fix this file" → the editor, once its first check has ended. */
async function openEditor(page: Page, id: string): Promise<void> {
  await page.goto(`result/${id}`);
  await page.getByTestId('fix-this-file').click();
  await expect(page).toHaveURL(new RegExp(`/mztabvalidator/edit/${id}$`));
  await awaitCheck(page);
}

/** Waits until the editor's latest changes have been checked. */
async function awaitCheck(page: Page): Promise<void> {
  await expect(page.getByTestId('save-state')).toHaveText('Saved');
  await expect(page.getByTestId('run-status')).not.toContainText('Checking your latest changes…', { timeout: 60_000 });
}

/** Opens every "How to fix this" panel, clicks the quick fix and applies the preview. */
async function applyQuickFix(page: Page, fixId: string): Promise<void> {
  const button = page.locator(`button.quick-fix[data-fix="${fixId}"]`);
  const card = page.locator('.grp').filter({ has: button });
  await card.locator('summary', { hasText: 'How to fix this' }).click();
  await expect(button).toBeEnabled();
  await button.click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByTestId('fix-count')).toBeVisible();
  await dialog.getByRole('button', { name: 'Apply' }).click();
  await expect(dialog).toBeHidden();
  // the check starts after the pause that follows the fix
  await expect(page.getByTestId('run-status')).toContainText('Checking your latest changes…');
  await awaitCheck(page);
}

async function text(download: Download): Promise<string> {
  return readFileSync(await download.path()).toString('utf-8');
}

test('fixThisFileWithQuickFixes', async ({ page, request }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const id = await validate(request, FIX_ME, 'fix-me.mzTab');
  await openEditor(page, id);
  const messages = page.getByRole('region', { name: 'Messages' });
  await expect(messages.locator('.grp--error')).not.toHaveCount(0);

  await applyQuickFix(page, 'remove-empty-parameter');
  await expect(page.getByTestId('fixed').locator('summary')).toHaveText(/Fixed since the last check \(\d+\)/);
  await applyQuickFix(page, 'normalise-doi');

  await expect(messages.locator('.grp--error')).toHaveCount(0);
  await expect(page.getByTestId('download-json')).toBeEnabled();
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('download-tsv').click()]);
  expect(download.suggestedFilename()).toBe('fix-me.mzTab');
  const fixed = await text(download);
  expect(fixed).not.toContain('[, , , ]');
  expect(fixed).not.toContain('doi:https://');
  expect(fixed).toContain('doi:10.1021/acs.analchem.7b03576');

  // undo brings the DOI back
  await page.getByTestId('undo').click();
  await awaitCheck(page);
  await expect(messages.locator('.grp--error')).not.toHaveCount(0);
  await request.delete(`rest/v2/validations/${id}`);
});

test('brokenFileOpensAndShowsReadUpTo', async ({ page, request }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const id = await validate(request, BROKEN_LINE, 'broken-line.mzTab');
  await openEditor(page, id);
  await expect(page.getByTestId('read-up-to')).toHaveText(
    'jmzTab-m stopped reading at line 12; problems further down are not reported yet.',
  );

  // the line cannot be shown as metadata: its message opens the raw line editor
  await page.locator('.chip', { hasText: /^\s*line 12\s*$/ }).first().click();
  const raw = page.locator('mztabm-raw-line-editor');
  await expect(raw).toContainText('Line 12');
  await expect(raw.getByRole('textbox', { name: 'Text of line 12' })).toHaveValue(/^Initiative Ontology \(MSIO\)→/);
  await raw.getByRole('button', { name: 'Cancel' }).click();

  await applyQuickFix(page, 'join-broken-line');
  await expect(page.getByTestId('read-up-to')).toBeHidden();
  await expect(page.getByRole('region', { name: 'Messages' }).locator('.grp--error')).toHaveCount(0);
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('download-tsv').click()]);
  expect(await text(download)).toContain('MTD\tcv[2]-full_name\tMetabolomics Standards Initiative Ontology (MSIO)\t');
  await request.delete(`rest/v2/validations/${id}`);
});

test('openAFileToFixFromLanding', async ({ page, request }) => {
  await page.goto('');
  await chooseNoProfile(page);
  await chooseFile(page, MZTAB_20);
  await page.getByTestId('open-to-fix').click();
  await expect(page).toHaveURL(new RegExp(`/mztabvalidator/edit/${UUID.source}$`), { timeout: 60_000 });
  const id = UUID.exec(page.url())![0];
  await expect(page.getByRole('heading', { level: 1, name: 'lipidomics-example.mzTab' })).toBeVisible();
  await expect(page.getByTestId('retention')).toHaveText(/^Kept for \d+ days after your last change$/);
  await awaitCheck(page);
  await expect(page.getByTestId('download-json')).toBeEnabled();
  await request.delete(`rest/v2/validations/${id}`);
});

test('unchangedDownloadIsIdentical', async ({ page, request }) => {
  await page.goto('');
  await chooseNoProfile(page);
  await chooseFile(page, MZTAB_20);
  const id = await submitAndAwaitResult(page);
  await page.getByTestId('fix-this-file').click();
  await expect(page).toHaveURL(new RegExp(`/mztabvalidator/edit/${id}$`));
  await awaitCheck(page);
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('download-tsv').click()]);
  expect(readFileSync(await download.path()).equals(readFileSync(MZTAB_20))).toBe(true);
  await request.delete(`rest/v2/validations/${id}`);
});

test('duplicateColumnIsMergedAndItsMessageGoes', async ({ page, request }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const id = await validate(request, DUPLICATE_COLUMN, 'duplicate-column.mzTab');
  await openEditor(page, id);
  const messages = page.getByRole('region', { name: 'Messages' });
  await expect(messages.locator('.grp--error')).toHaveCount(1);
  await expect(messages).toContainText('Column name used more than once');

  await page.getByRole('tab', { name: /Summary/ }).click();
  const duplicates = page.locator('mztabm-table-view th.col--dup');
  await expect(duplicates).toHaveCount(2);
  await expect(duplicates.first()).toContainText('Duplicate column name');
  await page.getByRole('button', { name: 'Resolve duplicate column opt_global_lipid_category' }).click();

  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('appears 2 times in this table: column 17 and column 20');
  await dialog.getByLabel('Merge them into one column').check();
  await expect(dialog.getByTestId('conflicts')).toContainText('In 1 row the columns hold different values');
  await expect(dialog.locator('.samples tbody tr')).toHaveText(/71\s*Sphingolipids\s*SP/);
  await dialog.getByRole('button', { name: 'Merge the columns' }).click();
  await expect(dialog).toBeHidden();

  await expect(page.getByTestId('run-status')).toContainText('Checking your latest changes…');
  await awaitCheck(page);
  await expect(messages.locator('.grp--error')).toHaveCount(0);
  await expect(duplicates).toHaveCount(0);
  await expect(page.getByTestId('fixed').locator('summary')).toHaveText('Fixed since the last check (1)');
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('download-tsv').click()]);
  // the first column won, so the file is the example again, with the column once
  expect(readFileSync(await download.path()).equals(readFileSync(MZTAB_20))).toBe(true);
  await request.delete(`rest/v2/validations/${id}`);
});
