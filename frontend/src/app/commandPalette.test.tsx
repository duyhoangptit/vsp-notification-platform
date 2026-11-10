import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { API, clockHandler, meHandler } from '../tests/handlers';
import { renderApp } from '../tests/render';
import { server } from '../tests/server';
import { extractNotificationId } from './CommandPalette';

const ID = '7f1c2a10-0000-4000-8000-000000000001';

async function openPalette() {
  const user = userEvent.setup();
  await screen.findByRole('navigation', { name: 'Main navigation' });
  await user.click(screen.getAllByRole('button', { name: 'Open command palette' })[0]);
  const input = await screen.findByLabelText('Command palette search');
  return { user, input };
}

describe('extractNotificationId', () => {
  it('accepts a raw id, a URL or a log line', () => {
    expect(extractNotificationId(` ${ID.toUpperCase()} `)).toBe(ID);
    expect(extractNotificationId(`https://hub/notifications/${ID}?x=1`)).toBe(ID);
    expect(extractNotificationId('order-123')).toBeNull();
  });
});

describe('CommandPalette', () => {
  it('navigates to a page by name', async () => {
    server.use(
      meHandler('ADMIN'),
      clockHandler('SYSTEM'),
      http.get(API('/console/api/channel-configs'), () => HttpResponse.json([])),
      http.get(API('/console/api/alerts/*'), () => HttpResponse.json([])),
      http.get(API('/console/api/alerts'), () => HttpResponse.json([])),
    );
    const { router } = renderApp('/dashboard');
    const { user, input } = await openPalette();
    await user.type(input, 'alerts');
    await user.click(await screen.findByRole('button', { name: /Alerts/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/alerts'));
  });

  it('pasting a notification id opens the delivery log drawer via ?inspect=', async () => {
    server.use(
      meHandler('SUPPORT_VIEWER'),
      clockHandler('SYSTEM'),
      http.get(API('/console/api/notifications/:id'), () =>
        HttpResponse.json({ notification_id: ID, status: 'SENT', channel_type: 'SMS', timeline: [] }),
      ),
    );
    const { router } = renderApp('/dashboard');
    const { user, input } = await openPalette();
    await user.click(input);
    await user.paste(ID);
    await user.click(await screen.findByRole('button', { name: /Inspect delivery log/ }));
    await waitFor(() => expect(router.state.location.search).toBe(`?inspect=${ID}`));
    expect(await screen.findByRole('dialog', { name: /Delivery inspector/ })).toBeInTheDocument();
  });

  it('searches templates on the server and opens the editor', async () => {
    server.use(
      meHandler('TEMPLATE_EDITOR'),
      clockHandler('SYSTEM'),
      // The editor loads the template after navigation; its content is irrelevant here.
      http.get(API('/console/api/templates/:code'), () =>
        HttpResponse.json({ template_code: 'OTP_LOGIN', template_name: 'Login OTP', channel_type: 'SMS' }),
      ),
      http.get(API('/console/api/templates'), ({ request }) => {
        const q = new URL(request.url).searchParams.get('q');
        return HttpResponse.json({
          items:
            q === 'otp'
              ? [
                  {
                    template_code: 'OTP_LOGIN',
                    template_name: 'Login OTP',
                    channel_type: 'SMS',
                    status: 'ACTIVE',
                  },
                ]
              : [],
          total: q === 'otp' ? 1 : 0,
        });
      }),
    );
    const { router } = renderApp('/dashboard');
    const { user, input } = await openPalette();
    await user.type(input, 'otp');
    // The label is split by query highlighting, so target the template code (action description).
    await user.click(await screen.findByText('OTP_LOGIN'));
    await waitFor(() => expect(router.state.location.pathname).toBe('/templates/OTP_LOGIN'));
  });
});
