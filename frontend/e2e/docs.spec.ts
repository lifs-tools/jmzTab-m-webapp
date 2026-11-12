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
import { expect, Page, test } from '@playwright/test';

import { DOCS_TREE } from '../projects/webapp/src/app/pages/docs/docs-tree';
import { seriousAxeViolations } from './helpers';

/** The documentation under the context path: deep links, the old addresses, the menu, and axe checks. */

const docsHeading = (page: Page, name: string) => page.getByRole('heading', { level: 2, name, exact: true });

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
});

test('opens a deep link to a docs section under the context path', async ({ page }) => {
  await page.goto('docs/validating#about-levels');
  await expect(page).toHaveURL(/\/mztabvalidator\/docs\/validating#about-levels$/);
  await expect(docsHeading(page, 'Validation levels')).toBeInViewport();

  // a section far down a long page: the first load scrolls to it
  await page.goto('docs/versions#tools-21');
  await expect(page).toHaveURL(/\/mztabvalidator\/docs\/versions#tools-21$/);
  await expect(docsHeading(page, 'Which tools write 2.1')).toBeInViewport();
  await expect(docsHeading(page, 'Versions and engines')).not.toBeInViewport();
});

test('old addresses redirect', async ({ page }) => {
  await page.goto('guide#guide-fixing');
  await expect(page).toHaveURL(/\/mztabvalidator\/docs\/fixing#guide-fixing$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Fixing a file' })).toBeVisible();

  await page.goto('problems');
  await expect(page).toHaveURL(/\/mztabvalidator\/docs\/problems$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Common problems' })).toBeVisible();

  await page.goto('about#about-cite');
  await expect(page).toHaveURL(/\/mztabvalidator\/docs\/about#about-cite$/);
  await expect(docsHeading(page, 'How to cite')).toBeInViewport();
});

test('Documentation menu reaches every chapter', async ({ page }) => {
  await page.goto('');
  const menu = page.getByRole('navigation', { name: 'Main' }).getByRole('button', { name: 'Documentation' });
  const entries = [
    { path: '/docs', label: 'All documentation', heading: 'Documentation' },
    ...DOCS_TREE.map((n) => ({ path: n.path, label: n.title, heading: n.title })),
  ];
  for (const entry of entries) {
    await menu.click();
    await page.getByRole('menuitem', { name: entry.label, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/mztabvalidator${entry.path}$`));
    await expect(page.getByRole('heading', { level: 1, name: entry.heading, exact: true })).toBeVisible();
  }
});

for (const path of ['', 'docs/getting-started', 'docs/format/metadata', 'docs/versions', 'docs/integration']) {
  test(`has no serious accessibility violations on /${path}`, async ({ page }) => {
    await page.goto(path);
    await expect(page.locator('h1')).toBeVisible();
    if (path === 'docs/format/metadata' || path === 'docs/versions') {
      // the excerpts of the teaching file are in the page
      await expect(page.locator('app-excerpt').first()).toBeVisible();
    }
    await expect.poll(() => seriousAxeViolations(page), { message: `axe on /${path}` }).toEqual([]);
  });
}
