import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { API, clockHandler, meHandler } from '../../tests/handlers';
import { renderApp } from '../../tests/render';
import { server } from '../../tests/server';

const ID = '01933b6e-8f2a-7c4e-9d3b-1a2b3c4d5e6f';

describe('Delivery Audit', () => {
  it('filters become query parameters and the empty state is shown', async () => {
    const seen: URLSearchParams[] = [];
    server.use(
      meHandler('SUPPORT_VIEWER'),
      clockHandler('SYSTEM'),
      http.get(API('/console/api/notifications'), ({ request }) => {
        seen.push(new URL(request.url).searchParams);
        return HttpResponse.json({ items: [], total: 0 });
      }),
    );
    renderApp('/notifications');
    expect(await screen.findByText('No notifications match these filters')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Recipient'), '+84901234567');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(seen.some((p) => p.get('recipient_target') === '+84901234567')).toBe(true));
    expect(seen.at(-1)?.get('from')).toBeTruthy();
  });

  it('detail timeline shows retries and provider errors', async () => {
    server.use(
      meHandler('ADMIN'),
      clockHandler('SYSTEM'),
      http.get(API(`/console/api/notifications/${ID}`), () =>
        HttpResponse.json({
          notification_id: ID,
          client_id: 'auth-service',
          client_request_id: 'req-1',
          user_id: 'u1',
          recipient_target: '+84901234567',
          channel_type: 'SMS',
          category: 'OTP',
          status: 'FAILED',
          error_code: 'PROVIDER_UNAVAILABLE',
          content_mode: 'DIRECT',
          content: 'Ma OTP ******',
          retry_count: 3,
          opt_out_overridden: false,
          timeline: [
            {
              at: '2026-11-03T10:00:00Z',
              event_type: 'STATUS_CHANGED',
              from_status: null,
              to_status: 'RECEIVED',
            },
            {
              at: '2026-11-03T10:00:01Z',
              event_type: 'ATTEMPT_FAILED',
              attempt: 1,
              provider_key: 'GENERIC_HTTP_SMS',
              error_code: 'PROVIDER_UNAVAILABLE',
              error_message: 'SMS gateway HTTP 503',
            },
            {
              at: '2026-11-03T10:00:02Z',
              event_type: 'ATTEMPT_FAILED',
              attempt: 2,
              provider_key: 'GENERIC_HTTP_SMS',
              error_code: 'PROVIDER_UNAVAILABLE',
              error_message: 'SMS gateway HTTP 503',
            },
            {
              at: '2026-11-03T10:00:05Z',
              event_type: 'STATUS_CHANGED',
              from_status: 'SENDING',
              to_status: 'FAILED',
              error_code: 'PROVIDER_UNAVAILABLE',
            },
          ],
        }),
      ),
    );
    renderApp(`/notifications/${ID}`);
    expect(await screen.findByText('Attempt 1 failed')).toBeInTheDocument();
    expect(screen.getByText('Attempt 2 failed')).toBeInTheDocument();
    expect(screen.getAllByText(/SMS gateway HTTP 503/).length).toBe(2);
    expect(screen.getByText('SENDING → FAILED')).toBeInTheDocument();
    expect(screen.getByTestId('content')).toHaveTextContent('Ma OTP ******');
  });
});
