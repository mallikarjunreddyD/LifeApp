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
  await page.getByRole('button', { name: 'Settings' }).click();
  await page.getByLabel('Username').fill('browser-test-owner');
  await page.getByLabel('Password').fill('disposable browser test password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText('Signed in to this server.')).toBeVisible();
}

async function bind(page: Page) {
  page.once('dialog', dialog => void dialog.accept());
  await page.getByRole('button', { name: 'Use this dataset' }).click();
  await expect(page.getByRole('button', { name: 'Sync now' })).toBeEnabled();
}

async function sync(page: Page) {
  await page.getByRole('button', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Sync now' }).click();
  await expect(page.getByRole('button', { name: 'Sync now' })).toBeEnabled();
  await page.getByRole('button', { name: 'To-do' }).click();
}

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
    await sync(page);
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
  await page.getByRole('button', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Sync now' }).click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
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

test('workout and original weight unit survive offline reload', async ({ page, context }) => {
  await page.goto('/');
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
  await page.reload();
  await context.setOffline(true);
  await page.getByRole('button', { name: 'Workouts', exact: true }).click();
  await page.getByRole('button', { name: 'New exercise' }).click();
  await page.getByLabel('Name', { exact: true }).fill('Bench Press');
  await page.getByLabel('Category or body part').fill('Chest');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Bench Press' })).toBeVisible();
  await page.getByRole('button', { name: 'New routine' }).click();
  await page.getByLabel('Routine name').fill('Push');
  await page.getByRole('button', { name: 'Add Bench Press' }).click();
  await page.getByLabel('Planned sets').fill('1');
  await page.getByRole('button', { name: 'Save routine' }).click();
  await page.getByRole('button', { name: 'Start routine' }).click();
  await page.getByRole('button', { name: 'Set 1 · Open' }).click();
  await page.getByLabel('Weight', { exact: true }).fill('105.5');
  await page.getByLabel('Weight unit').selectOption('LB');
  await page.getByLabel('Repetitions').fill('8');
  await page.getByLabel('Rest (seconds)').fill('45');
  await page.getByLabel('Set completed').check();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('button', { name: /105.5 LB · 8 reps · 45s rest/ })).toBeVisible();
  await page.getByRole('button', { name: 'Complete workout' }).click();
  await expect(page.getByRole('heading', { name: /Completed/ })).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: 'History', exact: true }).click();
  await page.getByRole('button', { name: /seconds · 1 exercises/ }).click();
  await expect(page.getByRole('button', { name: /105.5 LB · 8 reps · 45s rest/ })).toBeVisible();
});
