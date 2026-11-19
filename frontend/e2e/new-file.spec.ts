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
import { APIRequestContext, expect, Page, test } from '@playwright/test';
import { join } from 'node:path';

import { UUID } from './helpers';

/** The vocabulary instance of the v3 example: two datasets, no study.lcs_id. */
const INSTANCE = join(__dirname, '..', '..', 'submission-vocabulary', 'examples', 'v3-example', 'instance.json');
const MZTAB_ID = 'LCS-99999-01';

/** Waits until the editor's latest changes have been checked. */
async function awaitCheck(page: Page): Promise<void> {
  await expect(page.getByTestId('save-state')).toHaveText('Saved');
  await expect(page.getByTestId('run-status')).not.toContainText('Checking your latest changes…', { timeout: 60_000 });
}

async function fileText(request: APIRequestContext, id: string): Promise<string> {
  const response = await request.get(`rest/v2/working-copies/${id}/file`);
  expect(response.status()).toBe(200);
  return response.text();
}

async function errorMessages(request: APIRequestContext, id: string): Promise<string[]> {
  const info = await (await request.get(`rest/v2/working-copies/${id}`)).json();
  const messages = (info.validation?.messages ?? []) as { message?: { message_type?: string; message?: string; code?: string } }[];
  return messages.filter((m) => m.message?.message_type === 'error').map((m) => `${m.message?.code ?? ''} ${m.message?.message ?? ''}`.trim());
}

test('startANewFileAndAddFromAVocabularyFile', async ({ page, request }) => {
  test.setTimeout(240_000);
  await page.setViewportSize({ width: 1280, height: 1000 });

  // 1. New file: mzTab-M 2.0, the default rules, an id
  await page.goto('new');
  await expect(page.getByRole('heading', { level: 1, name: 'New file' })).toBeVisible();
  await expect(page.getByTestId('version')).toHaveValue('2.0.0-M');
  await expect(page.getByTestId('rules')).toHaveValue(/^mztab-m-2\.0-default@/);
  await page.getByTestId('mztab-id').fill(MZTAB_ID);
  await page.getByRole('button', { name: 'Create file' }).click();

  // 2. the editor opens and the check runs; a fresh skeleton cannot be read into a model, so the rules are not yet checked
  await expect(page).toHaveURL(new RegExp(`/mztabvalidator/edit/${UUID.source}$`));
  const id = UUID.exec(page.url())![0];
  await awaitCheck(page);
  await expect(page.getByTestId('version-badge')).toContainText('2.0');
  const missing = page.locator('mztabm-still-missing');
  await expect(missing.getByRole('heading', { level: 4, name: 'Not yet checked' })).toBeVisible({ timeout: 30_000 });
  await expect(missing).toContainText('The file cannot be read into a model yet');
  await expect(missing.getByRole('heading', { level: 4, name: 'Required' })).toHaveCount(0);
  const skeleton = await fileText(request, id);
  expect(skeleton).toContain('mzTab-version');
  expect(skeleton).toContain(MZTAB_ID);
  expect(skeleton).not.toContain('study_variable');

  // 3. Add from a vocabulary file, dataset 1, study id LCS-99999
  const panel = page.locator('mztabm-add-from-panel');
  await panel.getByTestId('import-kind').filter({ hasText: 'A vocabulary file' }).click();
  await panel.locator('input[type=file]').setInputFiles(INSTANCE);
  const datasets = panel.getByTestId('dataset-choice');
  await expect(datasets).toBeVisible();
  await datasets.locator('input[type=radio]').first().check();
  const study = panel.getByTestId('study-id');
  await study.fill('LCS-99999');
  await study.blur();
  await expect(panel.getByTestId('keep-existing')).toBeChecked();

  // 4. the preview shows new lines
  const rows = panel.locator('tbody tr.state--new');
  await expect(rows.first()).toBeVisible({ timeout: 60_000 });
  expect(await rows.count()).toBeGreaterThan(0);
  await expect(panel.locator('tbody tr.state--new .state').first()).toHaveText('new');

  // 5. Apply; the check after it finds no error, and nothing Required is left
  await panel.getByRole('button', { name: 'Apply', exact: true }).click();
  await expect(page.getByTestId('run-status')).toContainText('Checking your latest changes…');
  await awaitCheck(page);
  const applied = await fileText(request, id);
  expect(applied).not.toBe(skeleton);
  expect(applied.split('\n').length).toBeGreaterThan(skeleton.split('\n').length);
  // the rules of the profile (the CV terms) are checked on request
  await page.getByTestId('check-cv').click();
  await expect(page.getByTestId('cv-status')).toContainText('CV rules checked at revision', { timeout: 120_000 });
  await expect(page.getByTestId('cv-not-checked')).toHaveCount(0);
  await expect.poll(() => errorMessages(request, id), { timeout: 30_000 }).toEqual([]);
  await expect(page.getByTestId('download-json')).toBeEnabled();
  await expect(missing.getByRole('heading', { level: 4, name: 'Not yet checked' })).toHaveCount(0);
  // no Required rule is left (a rule of a table the file does not have is not missing, as in validation)
  await expect(missing.getByRole('heading', { level: 4, name: 'Required' })).toHaveCount(0);

  // 6. one Undo brings back the skeleton text
  await page.getByTestId('undo').click();
  await expect(page.getByTestId('save-state')).toHaveText('Saved');
  await expect.poll(() => fileText(request, id)).toBe(skeleton);
  await awaitCheck(page);

  // 7. reload edit/<id>: the copy opens
  await page.goto(`edit/${id}`);
  await expect(page.getByRole('heading', { level: 1, name: new RegExp(MZTAB_ID) })).toBeVisible();
  await expect(page.getByTestId('version-badge')).toContainText('2.0');
  await expect(page.getByTestId('open-failed')).toHaveCount(0);
  expect(await fileText(request, id)).toBe(skeleton);
});
