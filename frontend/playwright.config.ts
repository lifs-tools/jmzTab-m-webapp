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
import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against the packaged server jar, which must already be running, e.g.
 *
 *   java -jar server/target/mztab-validator-webapp-<version>.jar --server.port=8083 \
 *     --server.servlet.contextPath=/mztabvalidator --storage.location=/tmp/mztab-e2e
 *
 * They are not part of `./mvnw verify`; run them with `npm --prefix frontend run e2e`.
 * E2E_BASE_URL overrides the address; it must end with a slash.
 *
 * Tests tagged `@engine-down` need the mzTab-M 2.1 engine stopped, so the default run leaves
 * them out. Run them alone, after stopping that engine, with
 *
 *   E2E_ENGINE_DOWN=true npm --prefix frontend run e2e -- --grep @engine-down
 *
 * (a `--grep` on the command line cannot undo the project's `grepInvert`, hence the variable).
 */
const engineDown = process.env['E2E_ENGINE_DOWN'] === 'true';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  workers: process.env['CI'] ? 2 : undefined,
  reporter: [['list'], ['html', { open: 'never' }]],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:8083/mztabvalidator/',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
      ...(engineDown ? { grep: /@engine-down/ } : { grepInvert: /@engine-down/ }),
    },
  ],
});
