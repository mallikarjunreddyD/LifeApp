import { commitJournalFixture } from './journal-fixture.ts';
import { expect, test } from '@playwright/test';

test('browser exchanges task changes with the Android HTTPS client', async ({ page }) => {
  test.setTimeout(240_000);
  await page.goto('/');
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('button', { name: 'Account & Security' }).click();
  await page.getByLabel('Username').fill('android-bridge-owner');
  await page.getByLabel('Password').fill('disposable browser test password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('button', { name: 'Back to Settings' }).click();
  await page.getByRole('button', { name: 'Synchronization' }).click();
  page.once('dialog', dialog => void dialog.accept());
  await page.getByRole('button', { name: 'Connect synchronization' }).click();
  await expect(page.getByRole('button', { name: 'Connect synchronization' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Sync now' })).toBeEnabled();

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
  await page.getByRole('button', { name: 'Expenses', exact: true }).click();
  await expect(page.getByText('Expenses checked with the server.')).toBeVisible();
  await page.getByText('Filter Expenses', { exact: true }).click();
  await page.getByLabel('Month', { exact: true }).fill('2026-11');
  const expense = page.locator('.expense-card').filter({ hasText: 'Android offline expense' });
  await expect.poll(async () => {
    await page.getByRole('button', { name: 'Settings', exact: true }).click();
    if (!await page.getByRole('heading', { name: 'Synchronization' }).isVisible()) {
      await page.getByRole('button', { name: 'Synchronization' }).click();
    }
    await page.getByRole('button', { name: 'Sync now', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Sync now', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: 'Expenses', exact: true }).click();
    await page.getByText('Filter Expenses', { exact: true }).click();
    await page.getByLabel('Month', { exact: true }).fill('2026-11');
    return expense.count();
  }, { timeout: 60_000, intervals: [500, 1500, 3000] }).toBe(1);
  await expect(expense).toContainText('₹1.03');
  await expense.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByLabel('Amount (₹)').fill('2.05');
  await page.getByRole('button', { name: 'Save Expense' }).click();
  await expect(expense).toContainText('₹2.05');
  await expect(page.getByText(/expense changes waiting/)).toHaveCount(0);
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  if (!await page.getByRole('heading', { name: 'Synchronization' }).isVisible()) {
    await page.getByRole('button', { name: 'Synchronization' }).click();
  }
  await page.getByRole('button', { name: 'Sync now', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Sync now', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Workouts', exact: true }).click();
  await page.getByRole('button', { name: 'Exercises', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Android bridge press' })).toBeVisible();
  await page.getByRole('button', { name: 'Edit exercise', exact: true }).click();
  await page.getByLabel('Exercise name').fill('Browser bridge press');
  await page.getByRole('button', { name: 'Save exercise' }).click();
  await expect(page.getByRole('heading', { name: 'Browser bridge press' })).toBeVisible();
  await expect(page.getByRole('navigation').getByRole('button', { name: 'Journal', exact: true })).toHaveCount(0);
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
