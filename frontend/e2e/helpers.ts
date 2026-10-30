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
import { APIRequestContext, expect, Page } from '@playwright/test';
import { join } from 'node:path';

/** Helpers shared by the mzTab-M version specs (versions.spec.ts, engine-down.spec.ts). */

export const FIXTURES = join(__dirname, 'fixtures');
/** mzTab-M 2.0.0-M. */
export const MZTAB_20 = join(FIXTURES, 'lipidomics-example.mzTab');
/** mzTab-M 2.1.0-M: jmzTab-m's official-profile-mf.mztab. */
export const MZTAB_21 = join(FIXTURES, 'profile-mf-2.1.mzTab');
/** lipidomics-example.mzTab without its mzTab-version line. */
export const NO_VERSION = join(FIXTURES, 'no-version.mzTab');
export const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/;
export const NONE = 'None — structural validation only';

export function validateButton(page: Page) {
  return page.locator('form').getByRole('button', { name: 'Validate', exact: true });
}

/** Chooses a file in the form and waits until its version has been read. */
export async function chooseFile(page: Page, file: string): Promise<void> {
  await page.getByTestId('file-input').setInputFiles(file);
  await expect(page.getByTestId('version-badge')).toBeVisible();
}

/**
 * Semantic validation needs EBI OLS; structural validation stays offline. Choose it after the
 * file, since a file of another line resets the profile.
 */
export async function chooseNoProfile(page: Page): Promise<void> {
  await page.getByTestId('profile-select').click();
  await page.getByRole('option', { name: NONE }).click();
}

/** Submits the form and waits for the result banner; returns the validation id. */
export async function submitAndAwaitResult(page: Page): Promise<string> {
  await validateButton(page).click();
  await expect(page).toHaveURL(new RegExp(`/mztabvalidator/result/${UUID.source}$`));
  await expect(page.locator('app-result-banner')).toBeVisible({ timeout: 45_000 });
  return UUID.exec(page.url())![0];
}

export interface Record {
  id: string;
  status: string;
  mzTabVersion?: string;
  engine?: string | null;
  defaultVersionApplied?: boolean;
  messages?: { message?: string }[];
  failureReason?: string;
}

/** Polls a validation until it has ended. */
export async function awaitEnd(request: APIRequestContext, id: string): Promise<Record> {
  let record: Record | undefined;
  await expect
    .poll(
      async () => {
        record = (await (await request.get(`rest/v2/validations/${id}`)).json()) as Record;
        return record.status;
      },
      { timeout: 90_000, intervals: [500, 1000, 2000] },
    )
    .toMatch(/^(FINISHED|FAILED)$/);
  return record!;
}
