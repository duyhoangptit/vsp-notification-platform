import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { API, clockHandler, meHandler } from '../../tests/handlers';
import { renderApp } from '../../tests/render';
import { server } from '../../tests/server';

describe('Dashboard', () => {
  it('shows per-channel rates and switches granularity', async () => {
    const volumeQueries: URLSearchParams[] = [];
    server.use(
      meHandler('SUPPORT_VIEWER'),
      clockHandler('SYSTEM'),
      http.get(API('/console/api/dashboard/volume'), ({ request }) => {
        volumeQueries.push(new URL(request.url).searchParams);
        return HttpResponse.json({ buckets: [{ bucket_start: '2026-11-03T00:00:00Z', counts: { EMAIL: 8, SMS: 4 } }] });
      }),
      http.get(API('/console/api/dashboard/channel-rates'), () =>
        HttpResponse.json([
          { channel_type: 'EMAIL', success_count: 6, failure_count: 2, in_progress_count: 0, success_rate: 0.75, failure_rate: 0.25 },
          { channel_type: 'SMS', success_count: 3, failure_count: 1, in_progress_count: 4, success_rate: 0.75, failure_rate: 0.25 },
        ]),
      ),
    );
    renderApp('/dashboard');
    const email = await screen.findByTestId('rate-EMAIL');
    expect(email).toHaveTextContent('Success 75.0% (6)');
    expect(email).toHaveTextContent('Failure 25.0% (2)');
    expect(screen.getByTestId('rate-SMS')).toHaveTextContent('In progress 4');
    await waitFor(() => expect(volumeQueries.at(-1)?.get('granularity')).toBe('DAY'));
    expect(volumeQueries.at(-1)?.get('from')).toBeTruthy();
    await userEvent.click(screen.getByText('Hour'));
    await waitFor(() => expect(volumeQueries.at(-1)?.get('granularity')).toBe('HOUR'));
  });
});
