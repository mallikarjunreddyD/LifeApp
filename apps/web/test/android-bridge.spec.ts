import { commitJournalFixture } from './journal-fixture.ts';
import { expect, test } from '@playwright/test';

test('browser exchanges task changes with the Android HTTPS client', async ({ page }) => {
  test.setTimeout(120_000);
  await page.goto('/');
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: 'Account & Security' }).click();
  await page.getByLabel('Username').fill('android-bridge-owner');
  await page.getByLabel('Password').fill('disposable browser test password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('button', { name: 'Back to Settings' }).click();
  await page.getByRole('button', { name: 'Synchronization' }).click();
  page.once('dialog', dialog => void dialog.accept());
  await page.getByRole('button', { name: 'Connect To-do' }).click();

  async function syncUntil(title: string) {
    await expect.poll(async () => {
      await page.getByRole('button', { name: 'Settings', exact: true }).click();
      if (!await page.getByRole('heading', { name: 'Synchronization' }).isVisible()) {
        await page.getByRole('button', { name: 'Synchronization' }).click();
      }
      await page.getByRole('button', { name: 'Sync now' }).click();
      await expect(page.getByRole('button', { name: 'Sync now' })).toBeEnabled();
      await page.getByRole('button', { name: 'To-do' }).click();
      return await page.getByText(title, { exact: true }).count();
    }, { timeout: 85_000, intervals: [500, 1500, 3000] }).toBe(1);
  }

  await syncUntil('Android offline import');
  await page.getByRole('button', { name: 'Journal', exact: true }).click();
  await page.getByText('Server record versions', { exact: true }).click();
  await page.getByRole('button', { name: /Notebook ·/ }).click();
  await expect(page.getByRole('heading', { name: 'Existing Android notebook' })).toBeVisible();
  await expect(page.getByRole('img', { name: 'Handwriting for Page 1' })).toBeVisible();
  await page.getByRole('button', { name: 'To-do', exact: true }).click();
  await commitJournalFixture(page.request, new URL(page.url()).origin, '2026-11-09');
  await page.getByRole('button', { name: 'Add task' }).click();
  await page.getByLabel('Task title').fill('Browser created task');
  await page.getByLabel('Deadline').fill('2030-11-12');
  await page.getByRole('button', { name: 'Save task' }).click();
  await expect(page.getByText('Browser created task', { exact: true })).toBeVisible();
  await expect(page.getByText('0 pending')).toBeVisible();
  await syncUntil('Android edited browser task');
});
