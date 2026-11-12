import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { commitJournalFixture } from './journal-fixture.ts';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { readFile, writeFile } from 'node:fs/promises';

async function add(page: Page, title: string) {
  await page.getByRole('button', { name: 'Add task' }).click();
  await page.getByLabel('Task title').fill(title);
  await page.getByLabel('Deadline').fill('2030-11-12');
  await page.getByRole('button', { name: 'Save task' }).click();
  await expect(page.getByText(title, { exact: true })).toBeVisible();
}

async function signIn(page: Page) {
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: 'Account & Security' }).click();
  await page.getByLabel('Username').fill('browser-test-owner');
  await page.getByLabel('Password').fill('disposable browser test password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText('Signed in to this server.')).toBeVisible();
  await expect(page.getByText('Recovery epoch', { exact: true })).toHaveCount(0);
}

async function bind(page: Page) {
  await page.getByRole('button', { name: 'Back to Settings' }).click();
  await page.getByRole('button', { name: 'Synchronization' }).click();
  page.once('dialog', dialog => void dialog.accept());
  await page.getByRole('button', { name: 'Connect To-do' }).click();
  await expect(page.getByRole('button', { name: 'Sync now' })).toBeEnabled();
}

async function sync(page: Page) {
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: 'Synchronization' }).click();
  await page.getByRole('button', { name: 'Sync now' }).click();
  await expect(page.getByRole('button', { name: 'Sync now' })).toBeEnabled();
  await page.getByRole('button', { name: 'To-do' }).click();
}

test('Settings separates account controls and advanced diagnostics', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Backup & Recovery' })).toBeVisible();
  await expect(page.getByLabel('Username')).toHaveCount(0);
  await page.getByRole('button', { name: 'Account & Security' }).click();
  await expect(page.getByLabel('Username')).toBeVisible();
  await page.getByRole('button', { name: 'Back to Settings' }).click();
  await page.getByRole('button', { name: 'About LifeApp' }).click();
  await expect(page.getByText('Recovery epoch', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Advanced diagnostics' }).click();
  await expect(page.getByText('Server origin', { exact: true })).toBeVisible();
});

test('offline Download My Data verifies hashes and describes local coverage', async ({ page, context }) => {
  await page.goto('/');
  await page.waitForFunction(() => !!navigator.serviceWorker.controller);
  await context.setOffline(true);
  await add(page, 'Exported offline task');
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: 'Backup & Recovery' }).click();
  const downloading = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download My Data' }).click();
  const download = await downloading;
  const path = await download.path();
  expect(path).toBeTruthy();
  expect(download.suggestedFilename()).toBe('LifeApp-MyData.zip');
  const unpack = (entry: string) => execFileSync('unzip', ['-p', path!, entry]);
  const manifest = JSON.parse(unpack('manifest.json').toString());
  expect(manifest.completeAcrossDevices).toBe(false);
  expect(manifest.knownQueuedChanges).toBe(1);
  expect(manifest.recordCounts.todos).toBe(1);
  for (const file of manifest.files) {
    const bytes = unpack(file.path);
    expect(bytes.length).toBe(file.bytes);
    expect(createHash('sha256').update(bytes).digest('hex')).toBe(file.sha256);
  }
  expect(unpack('data/todos.json').toString()).toContain('Exported offline task');
});

test('iPhone-sized offline shell reopens queued changes and survives worker update', async ({ context, page }) => {
  await page.goto('/');
  await page.waitForFunction(() => !!navigator.serviceWorker.controller);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  const manifest = await (await context.request.get('/manifest.webmanifest')).json();
  expect(manifest.display).toBe('standalone');
  expect(manifest.id).toBe('/');
  await context.setOffline(true);
  await add(page, 'Offline browser task');
  await page.reload();
  await expect(page.getByText('Offline browser task')).toBeVisible();
  await page.getByRole('checkbox', { name: 'Complete Offline browser task' }).click();
  await expect(page.getByRole('heading', { name: 'Completed' })).toBeVisible();
  await page.getByRole('checkbox', { name: 'Reopen Offline browser task' }).click();
  await expect(page.getByRole('heading', { name: 'Pending' })).toBeVisible();
  await add(page, 'Delete locally');
  page.once('dialog', dialog => void dialog.accept());
  await page.getByRole('button', { name: 'Delete Delete locally' }).click();
  await expect(page.getByText('Delete locally', { exact: true })).toHaveCount(0);
  await expect(page.getByText('5 pending')).toBeVisible();
  await context.setOffline(false);
  const file = 'dist/sw.js';
  const original = await readFile(file, 'utf8');
  try {
    await writeFile(file, original.replace(/lifeapp-shell-[a-f0-9]{24}/, 'lifeapp-shell-browser-update-test'));
    await page.evaluate(async () => { await (await navigator.serviceWorker.getRegistration())!.update(); });
    await expect(page.getByRole('button', { name: 'Reload app' })).toBeVisible();
    await Promise.all([
      page.waitForEvent('framenavigated', { predicate: frame => frame === page.mainFrame(), timeout: 10_000 }),
      page.getByRole('button', { name: 'Reload app' }).click(),
    ]);
    await expect(page.getByText('Offline browser task')).toBeVisible();
    await expect(page.getByText('5 pending')).toBeVisible();
    const cached = await page.evaluate(async () => (await Promise.all((await caches.keys()).map(async name =>
      (await (await caches.open(name)).keys()).map(request => new URL(request.url).pathname)))).flat());
    expect(await page.evaluate(() => caches.keys())).toContain('lifeapp-shell-browser-update-test');
    expect(cached).toContain('/index.html');
    expect(cached.some(path => path.startsWith('/v1/'))).toBe(false);
  } finally {
    await writeFile(file, original);
  }
});

test('two browser stores exchange tasks and resolve offline conflicts against Ktor', async ({ browser, page, context }) => {
  const other = await browser.newContext({ ignoreHTTPSErrors: true });
  try {
    const second = await other.newPage();
    await page.clock.install();
    await page.goto('/');
    await signIn(page);
    await bind(page);
    await page.getByRole('button', { name: 'To-do' }).click();
    await add(page, 'Cross-device task');
    await expect(page.getByText('0 pending')).toBeVisible();

    await second.goto('/');
    await signIn(second);
    await bind(second);
    await second.getByRole('button', { name: 'To-do' }).click();
    await expect(second.getByText('Cross-device task', { exact: true })).toBeVisible();
    await add(second, 'Created on second browser');
    await expect(second.getByText('0 pending')).toBeVisible();
    await page.clock.fastForward(5 * 60 * 1000);
    await expect(page.getByText('Created on second browser', { exact: true })).toBeVisible();

    await context.setOffline(true);
    await other.setOffline(true);
    for (const [device, title] of [[page, 'First offline edit'], [second, 'Second offline edit']] as const) {
      await device.getByRole('button', { name: 'Edit Cross-device task' }).click();
      await device.getByLabel('Task title').fill(title);
      await device.getByRole('button', { name: 'Save task' }).click();
      await expect(device.getByText(title, { exact: true })).toBeVisible();
    }
    await context.setOffline(false);
    await expect(page.getByText('0 pending')).toBeVisible();
    await other.setOffline(false);
    await expect(second.getByRole('heading', { name: 'Conflicts to review' })).toBeVisible();
    await expect(second.getByText(/First offline edit ·/)).toBeVisible();
    await expect(second.getByText(/Second offline edit ·/)).toBeVisible();
    await second.locator('.variant').filter({ hasText: 'Second offline edit' }).getByRole('button', { name: 'Keep this version' }).click();
    await expect(second.getByText('Second offline edit', { exact: true })).toBeVisible();
    await expect(second.getByText('0 pending')).toBeVisible();
    await sync(page);
    await expect(page.getByText('Second offline edit', { exact: true })).toBeVisible();
  } finally { await other.close(); }
});

test('lost acknowledgment and expired browser session retain the operation', async ({ context, page }) => {
  await page.goto('/');
  await signIn(page);
  await bind(page);
  await page.getByRole('button', { name: 'To-do' }).click();
  await page.route('**/v1/sync/push', async route => {
    const result = await route.fetch();
    expect(result.status()).toBe(200);
    await route.abort();
  });
  await add(page, 'Retry after lost receipt');
  await expect(page.getByRole('alert')).toContainText('Local changes were kept');
  await expect(page.getByText('1 pending')).toBeVisible();
  await page.unroute('**/v1/sync/push');
  await context.clearCookies();
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: 'Synchronization' }).click();
  await page.getByRole('button', { name: 'Sync now' }).click();
  await expect(page.getByRole('button', { name: 'Sign in again' })).toBeVisible();
  await page.getByRole('button', { name: 'Back to Settings' }).click();
  await expect(page.getByText('1 pending')).toBeVisible();
  await signIn(page);
  await expect(page.getByText('0 pending')).toBeVisible();
  await page.getByRole('button', { name: 'To-do' }).click();
  await expect(page.getByText('Retry after lost receipt', { exact: true })).toHaveCount(1);
  const feed = await page.evaluate(async () => {
    const session = await (await fetch('/v1/session')).json();
    return await (await fetch('/v1/sync/pull?after=0&epoch=' + session.scope.epoch)).json();
  });
  expect(feed.entries.filter((entry: { operation: { record: { title: string } } }) =>
    entry.operation.record.title === 'Retry after lost receipt')).toHaveLength(1);
});

test('workout logbook drafts, deletion and PDF work offline', async ({ page, context }, testInfo) => {
  await page.goto('/');
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
  await page.reload();
  await context.setOffline(true);
  await page.getByRole('button', { name: 'Workouts', exact: true }).click();
  await page.getByText('Exercise library', { exact: true }).click();
  await page.getByRole('button', { name: 'New exercise' }).click();
  await page.getByLabel('Exercise name').fill('Bench Press');
  await page.getByLabel('Category', { exact: true }).fill('Chest');
  await page.getByRole('button', { name: 'Save exercise' }).click();
  await expect(page.getByRole('heading', { name: 'Bench Press' })).toBeVisible();
  await page.getByText('Manage routines', { exact: true }).click();
  await page.getByRole('button', { name: 'New routine' }).click();
  await page.getByLabel('Routine name').fill('Push');
  await page.getByRole('button', { name: 'Add Bench Press' }).click();
  await page.getByLabel('Planned sets').fill('1');
  await page.getByRole('button', { name: 'Save routine' }).click();
  await expect(page.getByRole('heading', { name: 'Push', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Add workout' }).click();
  await page.getByLabel('Workout date').fill('2026-11-12');
  await page.getByRole('combobox', { name: 'Routine', exact: true }).selectOption({ label: 'Push' });
  await page.getByLabel('Weight', { exact: true }).fill('105.5');
  await page.getByRole('combobox', { name: 'Unit', exact: true }).selectOption('LB');
  await page.getByLabel('Repetitions').fill('8');
  await page.getByRole('button', { name: 'Copy previous set' }).click();
  await page.getByLabel('Repetitions').nth(1).fill('7');
  await page.getByLabel('Exercise remarks').fill('Try a slower descent');
  await page.getByLabel('Workout minutes').fill('72');
  await page.getByLabel('Average heart rate (BPM)').fill('136');
  await page.getByRole('button', { name: 'History', exact: true }).click();
  await expect(page.getByLabel('Workout minutes')).toHaveValue('72');
  await page.reload();
  await page.getByRole('button', { name: 'Workouts', exact: true }).click();
  await expect(page.getByLabel('Exercise remarks')).toHaveValue('Try a slower descent');
  await page.screenshot({ path: testInfo.outputPath('workout-entry.png'), fullPage: true });
  await page.getByRole('button', { name: 'Save workout' }).click();
  await page.getByRole('combobox', { name: 'History view', exact: true }).selectOption('table');
  await expect(page.getByText('105.5 LB × 8 reps, 105.5 LB × 7 reps')).toBeVisible();
  await page.getByRole('button', { name: 'Edit workout' }).click();
  await page.getByLabel('Exercise remarks').fill('Edited remarks');
  await page.getByRole('button', { name: 'Save workout' }).click();
  await expect(page.getByText('Edited remarks', { exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('workout-history.png'), fullPage: true });
  await page.getByRole('button', { name: 'Delete workout' }).click();
  await expect(page.getByRole('alertdialog')).toContainText('Push · 2026-11-12');
  await page.getByRole('button', { name: 'Confirm delete' }).click();
  await page.getByRole('button', { name: 'Export monthly PDF' }).click();
  await page.getByLabel('Month', { exact: true }).fill('2026-11');
  await expect(page.getByText('No recorded workouts this month.')).toBeVisible();
  await page.getByRole('button', { name: 'Back to workouts' }).click();
  await page.getByText('Recently deleted', { exact: true }).click();
  await page.getByRole('button', { name: 'Restore workout' }).click();
  await expect(page.getByText('Edited remarks', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Export monthly PDF' }).click();
  await page.getByLabel('Month', { exact: true }).fill('2026-11');
  await expect(page.getByText('136 BPM · 1 measured sessions')).toBeVisible();
  await expect(page.getByText('Edited remarks', { exact: true })).toBeVisible();
  await page.getByLabel('Compact landscape').check();
  await expect(page.getByRole('columnheader', { name: 'Recorded sets' })).toBeVisible();
  const pdf = await page.pdf({ preferCSSPageSize: true });
  expect(pdf.subarray(0, 5).toString()).toBe('%PDF-');
});

test('record previews preserve vectors and support per-day habit edits', async ({ context, page }) => {
  await page.goto('/');
  await signIn(page);
  const date = '2026-11-09';
  await commitJournalFixture(context.request, new URL(page.url()).origin, date);
  await page.getByRole('button', { name: 'Journal' }).click();
  await page.getByRole('button', { name: date }).click();
  const canvas = page.getByRole('img', { name: 'Handwriting for ' + date });
  await expect(canvas).toBeVisible();
  const pixel = await canvas.evaluate(element => {
    const source = element as HTMLCanvasElement;
    return Array.from(source.getContext('2d')!.getImageData(307, 307, 1, 1).data);
  });
  expect(pixel).toEqual([0, 0, 0, 255]);
  const session = await (await context.request.get('/v1/session')).json();
  const headers = { Origin: new URL(page.url()).origin, 'X-CSRF-Token': session.csrfToken,
    'X-LifeApp-Dataset': session.scope.dataset, 'X-LifeApp-Epoch': session.scope.epoch };
  async function commitArchive(kind: 'planner' | 'notebook' | 'habits', alternative = false) {
    const archive = execFileSync('python3', ['../../contracts/fixtures/editable-archive.py', kind, ...(alternative ? ['alternative'] : [])]);
    const version = randomUUID();
    const sha256 = createHash('sha256').update(archive).digest('hex');
    expect((await context.request.put('/v1/editable/staged/' + version, {
      headers: { ...headers, 'Content-Type': 'application/octet-stream', 'X-Content-SHA256': sha256 }, data: archive,
    })).status()).toBe(204);
    expect((await context.request.post('/v1/editable/versions/' + version + '/commit', {
      headers, data: { sha256, parents: [] },
    })).status()).toBe(204);
    return version;
  }
  page.once('dialog', dialog => void dialog.accept());
  await page.getByRole('button', { name: 'Move to server record versions' }).click();
  await expect(page.getByText('Journal moved to Server record versions.', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: date, exact: true })).toHaveCount(0);

  const initialPlanner = await commitArchive('planner');
  await commitArchive('notebook');
  await commitArchive('habits');

  await page.getByText('Server record versions', { exact: true }).click();
  await page.getByRole('button', { name: 'Journal · ' + date }).click();
  const migrated = page.getByRole('img', { name: 'Handwriting for Page 1' });
  await expect(migrated).toBeVisible();
  expect(await migrated.evaluate(element => Array.from((element as HTMLCanvasElement).getContext('2d')!
    .getImageData(307, 307, 1, 1).data))).toEqual([0, 0, 0, 255]);
  await page.getByRole('button', { name: 'Planner · 2026-11' }).click();
  const planner = page.getByRole('img', { name: 'Handwriting for 2026-11', exact: true });
  await expect(planner).toBeVisible();
  expect(await planner.evaluate(element => Array.from((element as HTMLCanvasElement).getContext('2d')!
    .getImageData(137, 241, 1, 1).data))).toEqual([0, 0, 0, 255]);
  await page.getByRole('button', { name: /Notebook ·/ }).click();
  const notebook = page.getByRole('img', { name: 'Handwriting for Field notes' });
  await expect(notebook).toBeVisible();
  expect(await notebook.evaluate(element => Array.from((element as HTMLCanvasElement).getContext('2d')!
    .getImageData(192, 338, 1, 1).data))).toEqual([0, 0, 0, 255]);
  const alternativePlanner = await commitArchive('planner', true);
  await page.getByRole('button', { name: 'Planner · 2026-11' }).click();
  await page.getByRole('button', { name: 'Review current versions' }).click();
  const { 'X-CSRF-Token': csrfToken, ...noCsrf } = headers;
  expect(csrfToken).toBeTruthy();
  expect((await context.request.post('/v1/editable/versions/' + alternativePlanner + '/resolve', {
    headers: noCsrf, data: { id: randomUUID(), reviewed: [initialPlanner, alternativePlanner] },
  })).status()).toBe(403);
  const variants = page.getByRole('button', { name: /Preview version / });
  await expect(variants).toHaveCount(2);
  await variants.last().click();
  await expect(planner).toBeVisible();
  expect(await planner.evaluate(element => Array.from((element as HTMLCanvasElement).getContext('2d')!
    .getImageData(137, 274, 1, 1).data))).toEqual([0, 0, 0, 255]);

  let resolutionId: string | undefined;
  await page.route('**/v1/editable/versions/*/resolve', async route => {
    const { id } = route.request().postDataJSON() as { id: string };
    if (resolutionId) {
      expect(id).toBe(resolutionId);
      await route.continue();
    } else {
      resolutionId = id;
      expect((await route.fetch()).status()).toBe(204);
      await route.abort();
    }
  });
  page.once('dialog', dialog => void dialog.accept());
  await page.getByRole('button', { name: 'Keep selected version' }).click();
  await expect(page.getByRole('alert')).toContainText('Retry to check the same request');
  page.once('dialog', dialog => void dialog.accept());
  await page.getByRole('button', { name: 'Retry resolution' }).click();
  await expect(page.getByText('The selected version is current.', { exact: false })).toBeVisible();
  const feed = await (await context.request.get('/v1/editable/versions', { headers })).json();
  const planners = feed.items.filter((row: { kind: string }) => row.kind === 'planner');
  expect(planners.filter((row: { current: boolean }) => row.current)).toMatchObject([
    { id: resolutionId, parents: [initialPlanner, alternativePlanner].sort() },
  ]);
  await page.getByRole('button', { name: /Notebook ·/ }).click();
  await expect(notebook).toBeVisible();
  await page.getByRole('button', { name: 'Habits · 2026-11', exact: true }).click();
  const reading = page.getByRole('checkbox', { name: 'Reading' });
  await expect(reading).toBeVisible();
  await page.getByRole('combobox', { name: 'Day', exact: true }).selectOption('2');
  await reading.click();
  await expect(page.getByText('Habit completion saved.')).toBeVisible();
  await expect(reading).toBeChecked();
  await page.getByRole('combobox', { name: 'Day', exact: true }).selectOption('1');
  await expect(reading).not.toBeChecked();
  await page.getByRole('button', { name: /Notebook ·/ }).click();
  await expect(notebook).toBeVisible();
  const cachedPaths = await page.evaluate(async () => (await Promise.all((await caches.keys()).map(async name =>
    (await (await caches.open(name)).keys()).map(item => new URL(item.url).pathname)))).flat());
  expect(cachedPaths.some(path => path.startsWith('/v1/journal/') || path.startsWith('/v1/editable/'))).toBe(false);
  await context.setOffline(true);
  await expect(canvas).toHaveCount(0);
  await expect(notebook).toHaveCount(0);
  await expect(page.getByText('Connect to view committed journal pages.')).toBeVisible();
});

test('expenses create labels, edit, delete and restore across offline reloads', async ({ page, context }, testInfo) => {
  await page.goto('/');
  await page.waitForFunction(() => !!navigator.serviceWorker.controller);
  await context.setOffline(true);
  const expenses = page.getByRole('button', { name: 'Expenses', exact: true });
  await expenses.click();
  await page.getByRole('button', { name: 'Add Expense' }).click();
  await page.getByLabel('Amount (₹)').fill('1,250.75');
  await page.getByLabel('Expense name').fill('Training shoes');
  await expect(page.getByLabel('Date', { exact: true })).toHaveValue(/^\d{4}-\d{2}-\d{2}$/);
  const date = '2024-02-29';
  await page.getByLabel('Date', { exact: true }).fill(date);
  await page.getByRole('button', { name: 'Category · Choose' }).click();
  await page.getByLabel('Search Category').fill('Gym');
  await page.getByRole('button', { name: 'Create "Gym"' }).click();
  await expect(page.getByRole('button', { name: 'Category · Gym' })).toBeVisible();
  await page.getByRole('button', { name: 'Paid From · Choose' }).click();
  await page.getByLabel('Search Paid From').fill('Gift Card');
  await page.getByRole('button', { name: 'Create "Gift Card"' }).click();
  await expect(page.getByRole('button', { name: 'Paid From · Gift Card' })).toBeVisible();
  await page.getByText('Remarks (optional)', { exact: true }).click();
  await page.getByLabel('Expense remarks').fill('Winter training');
  await page.screenshot({ path: testInfo.outputPath('expense-entry.png') });
  await page.getByRole('button', { name: 'Save Expense' }).click();
  await expect(page.getByRole('heading', { name: 'Training shoes' })).toBeVisible();
  await page.reload();
  await expenses.click();
  await page.getByText('Filter Expenses', { exact: true }).click();
  await page.getByLabel('Month', { exact: true }).fill(date.slice(0, 7));
  await expect(page.getByRole('heading', { name: 'Training shoes' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.getByLabel('Date', { exact: true })).toHaveValue(date);
  await expect(page.getByLabel('Amount (₹)')).toHaveValue('1250.75');
  await expect(page.getByLabel('Expense remarks')).toHaveValue('Winter training');
  await page.getByLabel('Amount (₹)').fill('0.01');
  await page.getByRole('button', { name: 'Save Expense' }).click();
  await expect(page.locator('.expense-summary strong')).toHaveText('₹0.01');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: testInfo.outputPath('expense-history.png') });
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.getByRole('alertdialog')).toContainText('Training shoes · ₹0.01');
  await page.getByRole('button', { name: 'Confirm Delete' }).click();
  await expect(page.locator('.expense-summary strong')).toHaveText('₹0.00');
  await page.getByRole('button', { name: 'Recently Deleted' }).click();
  await page.getByRole('button', { name: 'Restore Expense' }).click();
  await page.getByRole('button', { name: 'Show History' }).click();
  await expect(page.locator('.expense-summary strong')).toHaveText('₹0.01');
  if (!await page.getByLabel('Search expense names').isVisible()) {
    await page.getByText('Filter Expenses', { exact: true }).click();
  }
  await page.getByLabel('Search expense names').fill('Other');
  await expect(page.getByRole('heading', { name: 'Training shoes' })).toHaveCount(0);
});
