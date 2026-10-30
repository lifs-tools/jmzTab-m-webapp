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

import {
  MZTAB_20,
  MZTAB_21,
  chooseFile,
  chooseNoProfile,
  submitAndAwaitResult,
  validateButton,
} from './helpers';

/**
 * Runs with the mzTab-M 2.1 engine stopped and the 2.0 engine running: CI stops the 2.1 engine
 * after the other e2e tests and runs these with E2E_ENGINE_DOWN=true (see playwright.config.ts);
 * locally, `kill "$(cat target/dev-stack/engine-2.1.pid)"` after scripts/run-dev-stack.sh.
 */

interface Engine {
  line: string;
  available: boolean;
}

test('a 2.1 file fails with the unavailable message while 2.0 still validates @engine-down', async ({
  page,
  request,
}) => {
  // the health check asks every engine again, so app info knows the 2.1 engine is gone
  await request.get('actuator/health');
  await expect
    .poll(async () => {
      const info = (await (await request.get('rest/v2/app-info')).json()) as { engines: Engine[] };
      return info.engines.find((e) => e.line === '2.1')?.available;
    })
    .toBe(false);

  await page.goto('');
  await chooseFile(page, MZTAB_21);
  await expect(page.getByTestId('version-badge')).toHaveText('mzTab-M 2.1');
  await expect(page.getByTestId('version-callout')).toHaveText(
    'The mzTab-M 2.1 validator is temporarily unavailable. Please try again later.',
  );
  await expect(page.getByTestId('version-callout').getByRole('link')).toHaveCount(0);
  await expect(validateButton(page)).toBeEnabled();
  const failed = await submitAndAwaitResult(page);
  await expect(
    page
      .getByText('The mzTab-M 2.1 validator is temporarily unavailable. Please try again later.')
      .first(),
  ).toBeVisible();
  await request.delete(`rest/v2/validations/${failed}`);

  await page.goto('');
  await chooseFile(page, MZTAB_20);
  await expect(page.getByTestId('version-callout')).toHaveCount(0);
  await chooseNoProfile(page);
  const passed = await submitAndAwaitResult(page);
  await expect(page.getByTestId('version-badge')).toHaveText('mzTab-M 2.0.0-M');
  await expect(
    page.getByRole('heading', { level: 1, name: 'lipidomics-example.mzTab' }),
  ).toBeVisible();
  const record = await (await request.get(`rest/v2/validations/${passed}`)).json();
  expect(record.status).toBe('FINISHED');
  await request.delete(`rest/v2/validations/${passed}`);
});
