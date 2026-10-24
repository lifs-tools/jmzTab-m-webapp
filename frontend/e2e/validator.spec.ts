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
const LINKED_MZTAB = join(FIXTURES, 'linked-messages.mzTab');
const LINKED_MAPPING = join(FIXTURES, 'linked-messages-mapping.xml');
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

/** Starts a validation of the linked-messages fixture with its custom mapping (no network needed). */
async function createLinkedValidation(request: APIRequestContext): Promise<string> {
  const response = await request.post('rest/v2/validations', {
    multipart: {
      file: { name: 'linked-messages.mzTab', mimeType: 'text/plain', buffer: readFileSync(LINKED_MZTAB) },
      mappingFile: { name: 'linked-messages-mapping.xml', mimeType: 'application/xml', buffer: readFileSync(LINKED_MAPPING) },
      level: 'INFO',
    },
  });
  expect(response.status()).toBe(202);
  return (await response.json()).id as string;
}

/** The group card whose headline starts with the given fix text. */
function groupCard(page: Page, headline: string) {
  return page.locator('.grp').filter({ has: page.locator('.fix', { hasText: headline }) });
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

test('linked messages open their place in the document', async ({ page, request }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('');
  await page.getByTestId('file-input').setInputFiles(LINKED_MZTAB);
  await page.getByTestId('mapping-input').setInputFiles(LINKED_MAPPING);
  await validateButton(page).click();
  await expect(page).toHaveURL(new RegExp(`/mztabvalidator/result/${UUID.source}$`));
  const id = UUID.exec(page.url())![0];
  await awaitBanner(page);

  // An undefined CV label, reported once where the parameter is defined: the chip opens the metadata entry.
  const cvGroup = groupCard(page, 'Add a cv[n] entry for "XMS"');
  await expect(cvGroup).toBeVisible();
  await cvGroup.getByRole('button', { name: 'instrument[1]-name', exact: true }).click();
  await expect(page.getByRole('tab', { name: /Metadata/ })).toHaveAttribute('aria-selected', 'true');
  const entry = page.locator('.entry--hl').filter({ has: page.locator('dt', { hasText: /^instrument\[1\]-name$/ }) });
  await expect(entry).toBeVisible();
  await expect(entry).toBeFocused();
  await expect(page.getByText(/Showing occurrence 1 of 1/)).toBeAttached();

  // An empty optional rule: the chip highlights the whole metadata group.
  const sampleGroup = groupCard(page, 'Add a value for sample[n]-species');
  await sampleGroup.getByRole('button', { name: 'metadata group: sample', exact: true }).click();
  await expect(page.getByRole('tab', { name: /Metadata/ })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('.panel--hl')).toHaveCount(1);
  await expect(page.locator('.panel--hl')).toContainText('sample');

  await request.delete(`rest/v2/validations/${id}`);
});

test('linked messages jump and return on a narrow screen', async ({ page, request }) => {
  await page.setViewportSize({ width: 800, height: 900 });
  const id = await createLinkedValidation(request);
  await page.goto(`result/${id}`);
  await awaitBanner(page);

  await groupCard(page, 'Add a cv[n] entry for "XMS"')
    .getByRole('button', { name: 'instrument[1]-name', exact: true }).click();
  await expect(page.getByRole('tab', { name: /Metadata/ })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('.entry--hl')).toBeVisible();

  await page.getByRole('button', { name: /Back to message/ }).click();
  await expect(page.getByRole('tab', { name: /Messages/ })).toHaveAttribute('aria-selected', 'true');
  await expect(groupCard(page, 'Add a cv[n] entry for "XMS"')).toBeVisible();
  await request.delete(`rest/v2/validations/${id}`);
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
 * Known contrast shortfalls of the approved LIFS colours (ruling R28, waiting for a decision by the project lead):
 * the lifs-tools.org brand bar, white on nav blue #3D84A8 (4.14:1, R21) including its "LIFS Tools" subtitle
 * #D8E6EE (3.25:1), and the button colour site-blue #5897BF with white (3.18:1, filled and outlined buttons).
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

test('landing, result, about and examples pages have no serious accessibility violations', async ({ page, request }) => {
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

  const linkedId = await createLinkedValidation(request);
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto(`result/${linkedId}`);
  await awaitBanner(page);
  await groupCard(page, 'Add a cv[n] entry for "XMS"')
    .getByRole('button', { name: 'instrument[1]-name', exact: true }).click();
  await expect(page.locator('.entry--hl')).toBeVisible();
  expect(await seriousViolations(), 'result page with a highlighted occurrence').toEqual([]);
  await request.delete(`rest/v2/validations/${linkedId}`);

  await page.goto('about');
  await expect(page.getByRole('heading', { level: 2, name: 'How to cite' })).toBeVisible();
  expect(await seriousViolations(), 'about page').toEqual([]);

  await page.goto('examples');
  await expect(page.getByRole('heading', { level: 1, name: 'Examples' })).toBeVisible();
  await expect(page.locator('.item').first()).toBeVisible();
  expect(await seriousViolations(), 'examples page').toEqual([]);
});
