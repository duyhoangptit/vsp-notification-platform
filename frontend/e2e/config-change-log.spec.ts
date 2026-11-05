import { expect, test } from '@playwright/test';
import { login } from './helpers';

test('change log filters by entity type and shows masked secret diffs', async ({ page }) => {
  await login(page, 'ADMIN');
  await page.goto('/config-change-log');
  await page.getByRole('textbox', { name: 'Entity type' }).click();
  const request = page.waitForRequest((r) => r.url().includes('entity_type=CHANNEL_CONFIG'));
  await page.getByRole('option', { name: 'CHANNEL_CONFIG' }).click();
  await request;
  // run after channel-configs.spec (secret replaced) — the diff never shows the value
  await expect(page.getByText('***changed***').first()).toBeVisible();
  await expect(page.getByText('mailpit-secret')).toHaveCount(0);
});
