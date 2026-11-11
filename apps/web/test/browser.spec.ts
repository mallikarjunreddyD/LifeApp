import { createHash, randomUUID } from 'node:crypto';
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
  await page.getByRole('button', { name: 'Finish workout' }).click();
  await page.getByLabel('Workout duration (minutes)').fill('72');
  await page.getByLabel('Average heart rate (BPM)').fill('136');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Completed/ })).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: 'History', exact: true }).click();
  await page.getByRole('button', { name: /4320 seconds · 1 exercises/ }).click();
  await expect(page.getByRole('button', { name: /105.5 LB · 8 reps · 45s rest/ })).toBeVisible();
  await page.getByRole('button', { name: 'Edit workout details' }).click();
  await expect(page.getByLabel('Workout duration (minutes)')).toHaveValue('72');
  await expect(page.getByLabel('Average heart rate (BPM)')).toHaveValue('136');
  const month = (await page.getByLabel('Date', { exact: true }).inputValue()).slice(0, 7);
  await page.getByRole('button', { name: 'Back to workouts' }).click();
  await page.getByRole('button', { name: 'Export monthly PDF' }).click();
  await page.getByLabel('Month', { exact: true }).fill(month);
  await expect(page.getByRole('heading', { name: 'LifeApp Workout Report' })).toBeVisible();
  await expect(page.getByText('136 BPM · 1 measured sessions')).toBeVisible();
  await expect(page.getByText(/105.5 LB · 8 reps/)).toBeVisible();
  await page.getByLabel('Compact landscape').check();
  await expect(page.getByRole('columnheader', { name: 'Recorded sets' })).toBeVisible();
  const pdf = await page.pdf({ preferCSSPageSize: true });
  expect(pdf.subarray(0, 5).toString()).toBe('%PDF-');
  await page.getByLabel('Month', { exact: true }).fill('2001-01');
  await expect(page.getByText('No completed workouts this month.')).toBeVisible();
});

test('journal preview draws committed LJPT vectors and hides them offline', async ({ context, page }) => {
  await page.goto('/');
  await signIn(page);
  const request = context.request;
  const login = await request.get('/v1/session');
  expect(login.status()).toBe(200);
  const session = await login.json();
  const headers = { 'X-CSRF-Token': session.csrfToken, Origin: 'https://127.0.0.1:4186' };
  const entryId = randomUUID();
  const pageId = randomUUID();
  const strokeId = randomUUID();
  const uploadId = randomUUID();
  const date = '2026-11-09';
  const time = '2026-11-09T12:00:00Z';
  const metadata = (id: string) => ({ id, createdAt: time, updatedAt: time, schemaVersion: 1, revision: 1 });
  const points = Buffer.alloc(52);
  points.write('LJPT');
  points.writeUInt32LE(1, 4);
  points.writeUInt32LE(2, 8);
  for (const [index, x] of [100, 300].entries()) {
    const offset = 12 + index * 20;
    points.writeFloatLE(x, offset);
    points.writeFloatLE(200, offset + 4);
    points.writeFloatLE(-1, offset + 8);
    points.writeBigInt64LE(BigInt(index), offset + 12);
  }
  const sha256 = createHash('sha256').update(points).digest('hex');
  expect((await request.put('/v1/ink/staged/' + uploadId, { data: points, headers: {
    ...headers, 'X-LifeApp-Dataset': session.scope.dataset, 'X-LifeApp-Epoch': session.scope.epoch,
    'X-Content-SHA256': sha256, 'Content-Type': 'application/octet-stream',
  } })).status()).toBe(204);

  // Kotlin's portable format encodes integral Float fields with a decimal point.
  const commit = JSON.stringify({
    scope: session.scope, uploadId, sha256,
    entry: { metadata: metadata(entryId), date, pages: [{
      metadata: metadata(pageId), entryId, order: 0, width: 1000, height: 1000, paper: 'BLANK', archived: false,
    }] },
    stroke: { metadata: metadata(strokeId), surfaceId: pageId, order: 0,
      brush: 'PEN', colorArgb: 0xff000000, width: 10, pointFormatVersion: 1 },
  }).replace(/("(?:width|height)":\d+)(?=[,}])/g, '$1.0');
  expect((await request.post('/v1/journal/ink/commit', {
    data: commit, headers: { ...headers, 'Content-Type': 'application/json' },
  })).status()).toBe(204);

  await page.getByRole('button', { name: 'Journal' }).click();
  await page.getByRole('button', { name: date }).click();
  const canvas = page.getByRole('img', { name: 'Handwriting for ' + date });
  await expect(canvas).toBeVisible();
  const pixel = await canvas.evaluate(element => {
    const source = element as HTMLCanvasElement;
    return Array.from(source.getContext('2d')!.getImageData(307, 307, 1, 1).data);
  });
  expect(pixel).toEqual([0, 0, 0, 255]);
  const cachedPaths = await page.evaluate(async () => (await Promise.all((await caches.keys()).map(async name =>
    (await (await caches.open(name)).keys()).map(item => new URL(item.url).pathname)))).flat());
  expect(cachedPaths.some(path => path.startsWith('/v1/journal/'))).toBe(false);
  await context.setOffline(true);
  await expect(canvas).toHaveCount(0);
  await expect(page.getByText('Connect to view committed journal pages.')).toBeVisible();
});
