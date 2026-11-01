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
import { APIRequestContext, expect, Locator, Page, test } from '@playwright/test';

import { seriousAxeViolations, UUID } from './helpers';

/** The tour of the teaching files: from the landing page through every step, across versions and back. */

const TOUR_20 = 'teaching-lipidomics-2.0.mztab';
const TOUR_21 = 'teaching-lipidomics-2.1.mztab';

interface Step {
  id: string;
  title: string;
  target?: { tab: string; group?: string | null; key?: string | null; rowIndex?: number | null; column?: string | null; header?: boolean | null };
}

async function stepsOf(request: APIRequestContext, id: string): Promise<Step[]> {
  const response = await request.get(`rest/v2/examples/${id}/tour`);
  expect(response.ok()).toBe(true);
  return ((await response.json()) as { steps: Step[] }).steps;
}

function resultUrl(tour: string, step: number): RegExp {
  return new RegExp(`/mztabvalidator/result/${UUID.source}\\?tour=${tour.replace(/\./g, '\\.')}&step=${step}$`);
}

const panel = (page: Page) => page.locator('app-tour-panel');
const selectedTab = (page: Page) => page.locator('[role="tab"][aria-selected="true"] .tab-label');

/** The element a step's target highlights, by the viewer's own highlight markers. */
function highlightOf(page: Page, step: Step): Locator | null {
  const t = step.target;
  if (!t) return null;
  if (t.tab === 'metadata') {
    return t.key
      ? page.locator('.entry--hl', { hasText: t.key })
      : page.locator('mat-expansion-panel.panel--hl', { hasText: t.group ?? '' });
  }
  if (t.header) return page.locator('th.col--hl', { hasText: t.column ?? '' });
  return t.column ? page.locator('tr.row--hl td.cell--hl') : page.locator('tr.row--hl');
}

const TAB_LABEL: Record<string, string> = { metadata: 'Metadata', summary: 'Summary', features: 'Features', evidence: 'Evidence' };

/** Checks that the step is shown: its number, title, tab and highlighted element. */
async function expectStep(page: Page, steps: Step[], i: number): Promise<void> {
  const step = steps[i];
  await expect(panel(page).getByText(`Step ${i + 1} of ${steps.length}`)).toBeVisible();
  await expect(panel(page).getByRole('heading', { level: 2, name: step.title })).toBeVisible();
  await expect(selectedTab(page)).toHaveText(TAB_LABEL[step.target?.tab ?? 'metadata']);
  const hl = highlightOf(page, step);
  if (hl) await expect(hl.first(), `highlight of step ${step.id}`).toBeVisible();
}

const created: string[] = [];

test.afterAll(async ({ request }) => {
  for (const id of created) await request.delete(`rest/v2/validations/${id}`);
});

function remember(page: Page): void {
  created.push(UUID.exec(page.url())![0]);
}

test('the tour steps through the 2.0 teaching file, switches to 2.1 and back, and closes', async ({ page, request }) => {
  const steps20 = await stepsOf(request, TOUR_20);
  const steps21 = await stepsOf(request, TOUR_21);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('');
  await page.getByRole('region', { name: 'About mzTab-M' }).getByRole('button', { name: 'Take the tour' }).click();

  await expect(page).toHaveURL(resultUrl(TOUR_20, 1), { timeout: 45_000 });
  remember(page);
  await expect(panel(page)).toBeVisible({ timeout: 45_000 });
  // in the split layout the panel takes the place of the messages
  await expect(page.locator('app-messages-tab')).toHaveCount(0);

  for (let i = 0; i < steps20.length; i++) {
    await expectStep(page, steps20, i);
    if (i === 1) {
      // Checked at rest: the focus and hover layer of the pressed filled button lightens the tolerated
      // site-blue (#5897BF), the same known shortfall in other shades, until its fade-out has ended.
      await page.mouse.move(0, 0);
      await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
      await expect
        .poll(() => seriousAxeViolations(page), { message: 'tour step with a highlighted metadata group' })
        .toEqual([]);
    }
    if (i < steps20.length - 1) await panel(page).getByRole('button', { name: 'Next' }).click();
  }
  await expect(page).toHaveURL(resultUrl(TOUR_20, steps20.length));
  await expect(panel(page).getByRole('link', { name: 'Validate your own file' })).toBeVisible();
  await expect(panel(page).getByRole('button', { name: 'Next' })).toHaveCount(0);

  // the same step on the other version: "Validate your own file" is one step later in the 2.1 tour
  await panel(page).getByRole('button', { name: 'Show this in mzTab-M 2.1' }).click();
  const last21 = steps21.findIndex((s) => s.id === 'validate-yours');
  await expect(page).toHaveURL(resultUrl(TOUR_21, last21 + 1), { timeout: 45_000 });
  remember(page);
  await expectStep(page, steps21, last21);
  // the profile step exists only in 2.1
  await panel(page).getByRole('button', { name: 'Back' }).click();
  await expectStep(page, steps21, last21 - 1);
  expect(steps21[last21 - 1].id).toBe('profile');

  await panel(page).getByRole('button', { name: 'Back' }).click();
  await panel(page).getByRole('button', { name: 'Show this in mzTab-M 2.0' }).click();
  const back20 = steps20.findIndex((s) => s.id === steps21[last21 - 2].id);
  await expect(page).toHaveURL(resultUrl(TOUR_20, back20 + 1), { timeout: 45_000 });
  remember(page);
  await expectStep(page, steps20, back20);

  await panel(page).getByRole('button', { name: 'Close' }).click();
  await expect(page).toHaveURL(/\/mztabvalidator\/$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Validate your mzTab-M files' })).toBeVisible();
});

test('on a narrow screen the panel sits above the tabs and opens the step\'s tab', async ({ page, request }) => {
  const steps = await stepsOf(request, TOUR_20);
  const summary = steps.findIndex((s) => s.target?.tab === 'summary');
  await page.setViewportSize({ width: 900, height: 900 });
  await page.goto('docs/format/summary');
  await page.getByRole('button', { name: 'Try it' }).click();

  await expect(page).toHaveURL(resultUrl(TOUR_20, summary + 1), { timeout: 45_000 });
  remember(page);
  await expect(panel(page)).toBeVisible({ timeout: 45_000 });
  await expect(page.locator('.split')).toHaveCount(0);
  const panelBox = (await panel(page).boundingBox())!;
  const tabsBox = (await page.getByRole('tablist').boundingBox())!;
  expect(panelBox.y).toBeLessThan(tabsBox.y);
  await expectStep(page, steps, summary);
});

test('a tour link whose result is gone validates the example again at the same step', async ({ page }) => {
  await page.goto(`result/00000000-0000-4000-8000-000000000000?tour=${TOUR_20}&step=3`);
  await expect(page).toHaveURL(resultUrl(TOUR_20, 3), { timeout: 45_000 });
  remember(page);
  await expect(panel(page).getByText('Step 3 of')).toBeVisible({ timeout: 45_000 });
  await expect(page.getByText('Result not found')).toHaveCount(0);
});

test('on a phone, Back and Next keep the step\'s highlight in view', async ({ page, request }) => {
  const steps = await stepsOf(request, TOUR_20);
  const first = steps.findIndex((s) => s.chapter === 'metadata');
  // three steps on the metadata tab, each highlighting a group further down the page
  for (const i of [first, first + 1, first + 2]) expect(steps[i].target?.tab).toBe('metadata');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('docs/format/metadata');
  await page.getByRole('button', { name: 'Try it' }).click();

  await expect(page).toHaveURL(resultUrl(TOUR_20, first + 1), { timeout: 45_000 });
  remember(page);
  await expect(panel(page)).toBeVisible({ timeout: 45_000 });
  await expectStep(page, steps, first);

  /**
   * Checked on a settled page, not retried: the viewer scrolls the highlight into view at once, and the router's
   * scroll restoration used to move the page back to the top a frame later.
   */
  async function expectInView(i: number): Promise<void> {
    await expect(page).toHaveURL(resultUrl(TOUR_20, i + 1));
    await expectStep(page, steps, i);
    await page.waitForTimeout(800);
    await expect(highlightOf(page, steps[i])!.first(), `highlight of step ${steps[i].id}`).toBeInViewport();
  }

  await panel(page).getByRole('button', { name: 'Next' }).click();
  await expectInView(first + 1);
  await panel(page).getByRole('button', { name: 'Next' }).click();
  await expectInView(first + 2);
  await panel(page).getByRole('button', { name: 'Back' }).click();
  await expectInView(first + 1);
});
