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
import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';

import {
  MZTAB_21,
  NO_VERSION,
  awaitEnd,
  chooseFile,
  chooseNoProfile,
  submitAndAwaitResult,
} from './helpers';

/**
 * mzTab-M 2.0 and 2.1 side by side: both engines must be running (scripts/run-dev-stack.sh, or
 * the e2e job in .github/workflows/maven.yml).
 */

test('a 2.1 file is validated by the 2.1 engine', async ({ page, request }) => {
  await page.goto('');
  await chooseFile(page, MZTAB_21);
  await expect(page.getByTestId('version-badge')).toHaveText('mzTab-M 2.1');
  await expect(page.getByTestId('profile-select')).toContainText('mzTab-M 2.1 default rules');
  await expect(page.getByTestId('version-callout')).toHaveCount(0);
  await chooseNoProfile(page);
  const id = await submitAndAwaitResult(page);

  const badge = page.getByTestId('version-badge');
  await expect(badge).toHaveText('mzTab-M 2.1.0-M');
  await badge.hover();
  // MatTooltip has no tooltip role; screen readers get the label through aria-describedby
  await expect(page.locator('.mat-mdc-tooltip')).toContainText('jmzTab-m 2.1');
  await expect(page.getByTestId('default-version-badge')).toHaveCount(0);
  await request.delete(`rest/v2/validations/${id}`);
});

test('a 2.0 example and a 2.1 example validate', async ({ page, request }) => {
  await page.goto('examples');
  const filter = page.getByRole('radiogroup', { name: 'mzTab-M version' });
  await filter.getByRole('radio', { name: '2.1', exact: true }).click();
  const files = page.getByTestId('example-files');
  await expect(files.getByTestId('version').first()).toBeVisible();
  for (const text of await files.getByTestId('version').allTextContents()) {
    expect(text.trim()).toBe('mzTab-M 2.1.0-M');
  }

  // Through the API with structural validation only, so no OLS lookups; the files come from GitHub.
  const examples = (await (await request.get('rest/v2/examples')).json()) as {
    id: string;
    name: string;
    section: string;
    mzTabVersion?: string;
  }[];
  const expected: [string, RegExp][] = [
    ['2.0.0-M', /jmzTab-m 1\.0/],
    ['2.1.0-M', /jmzTab-m 2\.1/],
  ];
  for (const [version, engine] of expected) {
    const example = examples.find((e) => e.section === 'example' && e.mzTabVersion === version);
    expect(example, `an example of mzTab-M ${version}`).toBeTruthy();
    const response = await request.post(`rest/v2/examples/${example!.id}/validations`, {
      data: { level: 'INFO', maxErrors: 100, validationProfile: null },
    });
    expect(response.status()).toBe(202);
    const record = await awaitEnd(request, (await response.json()).id);
    expect(record.status, `${example!.name}: ${record.failureReason ?? ''}`).toBe('FINISHED');
    expect(record.mzTabVersion).toBe(version);
    expect(record.engine).toMatch(engine);
    await request.delete(`rest/v2/validations/${record.id}`);
  }
});

test('a catalog profile of the other version is rejected', async ({ request }) => {
  const response = await request.post('rest/v2/validations', {
    multipart: {
      file: {
        name: 'profile-mf-2.1.mzTab',
        mimeType: 'text/plain',
        buffer: readFileSync(MZTAB_21),
      },
      level: 'INFO',
      validationProfile: 'mztab-m-2.0-default',
    },
  });
  expect(response.status()).toBe(400);
  expect((await response.json()).message).toMatch(
    /^The validation profile mztab-m-2\.0-default \S+ applies to mzTab-M 2\.0, but the file is validated as mzTab-M 2\.1\.$/,
  );
});

test('a file without version shows the default-version badge', async ({ page, request }) => {
  const info = (await (await request.get('rest/v2/app-info')).json()) as { defaultLine: string };
  await page.goto('');
  await chooseFile(page, NO_VERSION);
  await expect(page.getByTestId('version-badge')).toHaveText(
    `No version declared · validated as mzTab-M ${info.defaultLine}`,
  );
  await chooseNoProfile(page);
  const id = await submitAndAwaitResult(page);

  await expect(page.getByTestId('default-version-badge')).toHaveText('default version');
  await request.delete(`rest/v2/validations/${id}`);
});
