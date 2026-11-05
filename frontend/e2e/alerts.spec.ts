import { expect, test } from '@playwright/test';
import { login, uid } from './helpers';

test('manage recipients, edit a rule, send a test alert and see history', async ({ page }) => {
  const email = `${uid('oncall')}@example.com`;
  await login(page, 'ADMIN');
  await page.goto('/alerts');
  await page.getByLabel('Email').fill(email);
  await page.getByRole('button', { name: 'Add recipient' }).click();
  await expect(page.getByText(email)).toBeVisible();
  await page.getByLabel(`Active ${email}`).dispatchEvent('click'); // Mantine Switch: the track covers the input
  await expect(page.getByLabel(`Active ${email}`)).not.toBeChecked();
  await page.getByLabel(`Active ${email}`).dispatchEvent('click'); // Mantine Switch: the track covers the input

  await page.getByRole('button', { name: 'Send test alert' }).click();
  await expect(page.getByText(/Test alert: (SENT|SEND_FAILED|NO_RECIPIENTS)/)).toBeVisible();

  await page.getByRole('tab', { name: 'Rules' }).click();
  const cooldown = page.getByLabel('Cooldown INGEST_REJECTED_SPIKE');
  await cooldown.fill('45');
  await page.getByRole('row', { name: /INGEST_REJECTED_SPIKE/ }).getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('INGEST_REJECTED_SPIKE saved')).toBeVisible();

  await page.getByRole('tab', { name: 'History' }).click();
  await expect(page.getByRole('columnheader', { name: 'Rule' })).toBeVisible();

  await page.getByRole('tab', { name: 'Recipients' }).click();
  await page.getByLabel(`Remove ${email}`).click();
  await expect(page.getByText(email)).toHaveCount(0);
});
