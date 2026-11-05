import { expect, test } from '@playwright/test';
import { login, uid } from './helpers';

test('create an EMAIL template with a placeholder, preview it, hit a version conflict, deactivate', async ({ page, browser }) => {
  const code = uid('E2E').toUpperCase().replace(/-/g, '_');
  await login(page, 'TEMPLATE_EDITOR');
  await page.goto('/templates/new');
  await page.getByLabel('Code').fill(code);
  await page.getByLabel('Name').fill('E2E welcome');
  await page.getByLabel('Subject').fill('Hello ${name}');
  const editor = page.locator('.ProseMirror');
  await editor.click();
  await editor.pressSequentially('Hi ${name}, welcome!');
  await expect(page.locator('.pnh-placeholder').first()).toHaveText('${name}');
  await expect(page.getByTestId('required-variables')).toContainText('name');

  await page.getByRole('button', { name: 'Preview' }).click();
  const dialog = page.getByRole('dialog', { name: 'Preview' });
  await dialog.getByLabel('name', { exact: true }).fill('An');
  await dialog.getByRole('button', { name: 'Render preview' }).click();
  await expect(page.getByRole('heading', { name: 'Hello An' })).toBeVisible();
  await page.keyboard.press('Escape');

  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL(new RegExp(`/templates/${code}$`));

  // second tab saves first → this tab gets 409
  const other = await (await browser.newContext()).newPage();
  await login(other, 'TEMPLATE_EDITOR');
  await other.goto(`/templates/${code}`);
  await expect(other.getByLabel('Name')).toHaveValue('E2E welcome');
  await other.getByLabel('Name').fill('Changed elsewhere');
  await other.getByRole('button', { name: 'Save' }).click();
  await expect(other.getByText(/saved/)).toBeVisible();

  await page.getByLabel('Name').fill('My change');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Modified by someone else')).toBeVisible();
  await page.getByRole('button', { name: 'Reload latest version' }).click();
  await expect(page.getByLabel('Name')).toHaveValue('Changed elsewhere');

  await page.getByRole('button', { name: 'Deactivate' }).click();
  await expect(page.getByText('INACTIVE')).toBeVisible();
});
