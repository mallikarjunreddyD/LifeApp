import { commitJournalFixture } from './journal-fixture.ts';
import { expect, test } from '@playwright/test';

test('browser exchanges task changes with the Android HTTPS client', async ({ page }) => {
  test.setTimeout(120_000);
  await page.goto('/');
  await page.getByRole('button', { name: 'Settings' }).click();
  await page.getByLabel('Username').fill('android-bridge-owner');
  await page.getByLabel('Password').fill('disposable browser test password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Use this dataset' })).toBeEnabled();
  page.once('dialog', dialog => void dialog.accept());
  await page.getByRole('button', { name: 'Use this dataset' }).click();

  async function syncUntil(title: string) {
    await expect.poll(async () => {
      await page.getByRole('button', { name: 'Settings' }).click();
      await page.getByRole('button', { name: 'Sync now' }).click();
      await expect(page.getByRole('button', { name: 'Sync now' })).toBeEnabled();
      await page.getByRole('button', { name: 'To-do' }).click();
      return await page.getByText(title, { exact: true }).count();
    }, { timeout: 85_000, intervals: [500, 1500, 3000] }).toBe(1);
  }

  await syncUntil('Android offline import');
  await commitJournalFixture(page.request, new URL(page.url()).origin, '2026-11-09');
  await page.getByRole('button', { name: 'Add task' }).click();
  await page.getByLabel('Task title').fill('Browser created task');
  await page.getByLabel('Deadline').fill('2030-11-12');
  await page.getByRole('button', { name: 'Save task' }).click();
  await expect(page.getByText('Browser created task', { exact: true })).toBeVisible();
  await expect(page.getByText('0 pending')).toBeVisible();
  await syncUntil('Android edited browser task');
});
