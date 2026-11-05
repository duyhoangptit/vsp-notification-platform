import { expect, test } from '@playwright/test';
import { login } from './helpers';

test('dashboard renders per-channel rates and switches HOUR/DAY', async ({ page }) => {
  await login(page, 'SUPPORT_VIEWER');
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  const dayRequest = page.waitForRequest((r) => r.url().includes('/dashboard/volume') && r.url().includes('granularity=DAY'));
  await page.reload();
  await dayRequest;
  const hourRequest = page.waitForRequest((r) => r.url().includes('/dashboard/volume') && r.url().includes('granularity=HOUR'));
  await page.getByText('Hour', { exact: true }).click();
  await hourRequest;
  await expect(page.getByText(/Volume per hour/)).toBeVisible();
});
