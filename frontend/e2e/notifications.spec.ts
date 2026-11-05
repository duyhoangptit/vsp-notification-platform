import { expect, test } from '@playwright/test';
import { API, CLIENT_AUTH, login, uid } from './helpers';

test('search by recipient and open the timeline', async ({ page, request }) => {
  const res = await request.post(`${API}/api/v1/notifications/send`, {
    headers: { Authorization: CLIENT_AUTH },
    data: {
      client_request_id: uid('e2e'),
      user_id: 'user_e2e',
      channel_type: 'SMS',
      recipient_target: '+84901000777',
      content: { body: 'E2E audit message' },
    },
  });
  expect(res.status()).toBe(202);
  const { notification_id } = await res.json();

  await login(page, 'SUPPORT_VIEWER');
  await page.goto('/notifications');
  await page.getByLabel('Recipient').fill('+84901000777');
  await page.getByRole('button', { name: 'Search' }).click();
  await expect(page.getByText('+84*****777').first()).toBeVisible();
  await page.goto(`/notifications/${notification_id}`);
  await expect(page.getByRole('heading', { name: `Notification ${notification_id}` })).toBeVisible();
  await expect(page.getByText('∅ → RECEIVED')).toBeVisible();
  await expect(page.getByTestId('content')).toHaveText('E2E audit message');
});
