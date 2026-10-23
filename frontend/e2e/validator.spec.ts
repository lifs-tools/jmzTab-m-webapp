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
import AxeBuilder from '@axe-core/playwright';
import { APIRequestContext, expect, Page, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const FIXTURES = join(__dirname, 'fixtures');
const MZTAB = join(FIXTURES, 'lipidomics-example.mzTab');
const DOCTYPE_MAPPING = join(FIXTURES, 'doctype-mapping.xml');
const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/;

/** Semantic validation needs EBI OLS; these tests stay offline with structural validation only. */
async function chooseNoProfile(page: Page): Promise<void> {
  await page.getByTestId('profile-select').click();
  await page.getByRole('option', { name: 'None — structural validation only' }).click();
}

function validateButton(page: Page) {
  return page.locator('form').getByRole('button', { name: 'Validate', exact: true });
}

/** Creates a structural validation through the API, as a script or an old bookmark would have one. */
async function createValidation(request: APIRequestContext): Promise<string> {
  const response = await request.post('rest/v2/validations', {
    multipart: {
      file: { name: 'lipidomics-example.mzTab', mimeType: 'text/plain', buffer: readFileSync(MZTAB) },
      level: 'INFO',
    },
  });
  expect(response.status()).toBe(202);
  return (await response.json()).id as string;
}

async function awaitBanner(page: Page): Promise<void> {
  await expect(page.locator('app-result-banner')).toBeVisible({ timeout: 30_000 });
}

test('upload, see result tabs, delete', async ({ page, request }) => {
  await page.goto('');
  await chooseNoProfile(page);
  await page.getByTestId('file-input').setInputFiles(MZTAB);
  await expect(page.getByRole('group', { name: 'mzTab-M file' }).getByText('lipidomics-example.mzTab')).toBeVisible();
  await validateButton(page).click();

  await expect(page).toHaveURL(new RegExp(`/mztabvalidator/result/${UUID.source}$`));
  const id = UUID.exec(page.url())![0];
  await awaitBanner(page);
  await expect(page.getByRole('heading', { level: 1, name: 'lipidomics-example.mzTab' })).toBeVisible();

  await page.getByRole('tab', { name: /Summary/ }).click();
  await expect(page.getByRole('columnheader', { name: 'SML_ID' })).toBeVisible();

  await page.getByRole('button', { name: 'Delete now' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('Delete this result now?')).toBeVisible();
  await dialog.getByRole('button', { name: 'Delete', exact: true }).click();

  await expect(page).toHaveURL(/\/mztabvalidator\/$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Validate your mzTab-M files' })).toBeVisible();
  expect((await request.get(`rest/v2/validations/${id}`)).status()).toBe(404);
});

test('custom mapping with DOCTYPE is rejected', async ({ page }) => {
  await page.goto('');
  await page.getByTestId('file-input').setInputFiles(MZTAB);
  await page.getByTestId('mapping-input').setInputFiles(DOCTYPE_MAPPING);
  await expect(page.getByText('doctype-mapping.xml')).toBeVisible();
  await validateButton(page).click();

  const error = page.locator('form').getByRole('alert');
  await expect(error).toContainText('Mapping file rejected');
  await expect(page).toHaveURL(/\/mztabvalidator\/$/);
});

test('old /validate/{id} link lands on the result', async ({ page, request }) => {
  const id = await createValidation(request);

  await page.goto(`validate/${id}?maxErrors=100&level=INFO&checkCvMapping=false`);

  await expect(page).toHaveURL(new RegExp(`/mztabvalidator/result/${id}$`));
  await awaitBanner(page);
  await expect(page.getByRole('heading', { level: 1, name: 'lipidomics-example.mzTab' })).toBeVisible();
  await request.delete(`rest/v2/validations/${id}`);
});

test('old delete link opens the confirmation and deletes nothing', async ({ page, request }) => {
  const id = await createValidation(request);

  await page.goto(`validate/${id}/delete`);

  await expect(page).toHaveURL(new RegExp(`/mztabvalidator/result/${id}$`));
  await expect(page.getByRole('dialog').getByText('Delete this result now?')).toBeVisible();
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
  expect((await request.get(`rest/v2/validations/${id}`)).status()).toBe(200);
  await request.delete(`rest/v2/validations/${id}`);
});

test('unknown route shows the not-found page', async ({ page }) => {
  const response = await page.goto('no/such/page');

  expect(response?.status()).toBe(200);
  await expect(page.getByRole('heading', { level: 1, name: "We couldn't find that page" })).toBeVisible();
});

test('footer shows Forschungszentrum Jülich', async ({ page }) => {
  await page.goto('');

  await expect(page.getByRole('contentinfo').getByRole('img', { name: 'Forschungszentrum Jülich' })).toBeVisible();
});

/**
 * Known contrast shortfalls of the approved LIFS colours, waiting for a decision by the project lead:
 * white on the lifs-tools.org nav blue #3D84A8 (4.14:1, ruling R21; the app bar's "LIFS Tools" line #D8E6EE
 * reaches 3.25:1) and the button colour site-blue #5897BF with white (3.18:1, filled and outlined buttons).
 * Only these exact colour pairs are tolerated; any other serious or critical violation fails the test.
 * Remove an entry once its colours change.
 */
const KNOWN_CONTRAST_SHORTFALLS: ReadonlyArray<{ fg: string; bg: string }> = [
  { fg: '#ffffff', bg: '#3d84a8' },
  { fg: '#d8e6ee', bg: '#3d84a8' },
  { fg: '#ffffff', bg: '#5897bf' },
  { fg: '#5897bf', bg: '#ffffff' },
];

function isKnownShortfall(ruleId: string, data: unknown): boolean {
  if (ruleId !== 'color-contrast' || typeof data !== 'object' || data === null) return false;
  const { fgColor, bgColor } = data as { fgColor?: string; bgColor?: string };
  return KNOWN_CONTRAST_SHORTFALLS.some((k) => k.fg === fgColor?.toLowerCase() && k.bg === bgColor?.toLowerCase());
}

test('landing and result pages have no serious accessibility violations', async ({ page, request }) => {
  const seriousViolations = async () => {
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
    return results.violations
      .filter((v) => v.impact === 'serious' || v.impact === 'critical')
      .map((v) => ({
        id: v.id,
        impact: v.impact,
        help: v.help,
        targets: v.nodes
          .filter((n) => !n.any.some((check) => isKnownShortfall(v.id, check.data)))
          .map((n) => `${n.target.join(' ')} | ${n.failureSummary ?? ''}`),
      }))
      .filter((v) => v.targets.length > 0);
  };

  await page.goto('');
  await expect(page.getByRole('heading', { level: 1, name: 'Validate your mzTab-M files' })).toBeVisible();
  expect(await seriousViolations(), 'landing page').toEqual([]);

  const id = await createValidation(request);
  await page.goto(`result/${id}`);
  await awaitBanner(page);
  expect(await seriousViolations(), 'result page').toEqual([]);
  await request.delete(`rest/v2/validations/${id}`);
});
