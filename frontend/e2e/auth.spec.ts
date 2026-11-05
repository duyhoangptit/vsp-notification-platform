import { expect, test } from '@playwright/test';
import { login } from './helpers';

test('each role sees only its navigation and gets a 403 page elsewhere', async ({ page }) => {
  await login(page, 'TEMPLATE_EDITOR');
  const nav = page.getByRole('navigation', { name: 'Main navigation' });
  await expect(nav.getByText('Templates')).toBeVisible();
  await expect(nav.getByText('Delivery Audit')).toHaveCount(0);
  await page.goto('/channel-configs');
  await expect(page.getByText('403 — Not allowed')).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();

  await login(page, 'SUPPORT_VIEWER');
  await expect(page.getByRole('navigation', { name: 'Main navigation' }).getByText('Delivery Audit')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Main navigation' }).getByText('Templates')).toHaveCount(0);
  await page.getByRole('button', { name: 'Sign out' }).click();

  await login(page, 'ADMIN');
  for (const item of ['Dashboard', 'Templates', 'Delivery Audit', 'Channel configs', 'Config change log', 'Alerts']) {
    await expect(page.getByRole('navigation', { name: 'Main navigation' }).getByText(item)).toBeVisible();
  }
});

test('wrong password shows an error', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Username').fill('admin');
  await page.getByLabel('Password').fill('wrong');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toContainText('UNAUTHORIZED');
});
