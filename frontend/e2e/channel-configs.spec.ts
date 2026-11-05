import { expect, test } from '@playwright/test';
import { login } from './helpers';

test('SMTP secret is masked, can be replaced, and the connection test runs', async ({ page }) => {
  await login(page, 'ADMIN');
  await page.goto('/channel-configs');
  const smtp = page.getByTestId('config-SMTP');
  await expect(smtp).toBeVisible();
  await expect(smtp).not.toContainText('mailpit-secret');

  await smtp.getByRole('button', { name: /Password/ }).click();
  await smtp.getByLabel('Password (new value)').fill('mailpit-secret');
  await smtp.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('SMTP saved')).toBeVisible();
  await expect(page.getByTestId('config-SMTP')).toContainText('********');

  await page.getByTestId('config-SMTP').getByRole('button', { name: 'Test connection' }).click();
  await expect(page.getByTestId('config-SMTP').getByTestId('test-result')).toBeVisible();
});
