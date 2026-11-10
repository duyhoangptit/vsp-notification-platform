import { CodeHighlightAdapterProvider } from '@mantine/code-highlight';
import { MantineProvider } from '@mantine/core';
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { highlightJsAdapter } from '../../app/codeHighlight';
import { cssVariablesResolver, theme } from '../../app/theme';
import { ORIGIN, newQueryClient } from '../../tests/render';
import { server } from '../../tests/server';
import { DeliveryLogDrawer } from './DeliveryLogDrawer';

const ID = '7f1c2a10-0000-4000-8000-000000000001';

describe('DeliveryLogDrawer', () => {
  it('shows journey latency, failure reason and provider response for a failed send', async () => {
    server.use(
      http.get(`${ORIGIN}/console/api/notifications/:id`, () =>
        HttpResponse.json({
          notification_id: ID,
          status: 'FAILED',
          channel_type: 'SMS',
          recipient_target: '+84901234567',
          created_at: '2026-11-10T10:00:00.000Z',
          expires_at: '2099-01-01T00:00:00Z',
          error_code: 'PROVIDER_REJECTED',
          error_message: 'Invalid sender',
          retry_count: 2,
          content: 'OTP ******',
          timeline: [
            { at: '2026-11-10T10:00:00.000Z', event_type: 'STATUS_CHANGED', to_status: 'RECEIVED' },
            {
              at: '2026-11-10T10:00:00.180Z',
              event_type: 'STATUS_CHANGED',
              from_status: 'RECEIVED',
              to_status: 'PROCESSED',
            },
            {
              at: '2026-11-10T10:00:04.000Z',
              event_type: 'ATTEMPT_FAILED',
              attempt: 1,
              provider_key: 'sms-gw',
              error_code: 'E42',
              provider_response: { code: 42, msg: 'Invalid sender' },
            },
            {
              at: '2026-11-10T10:00:45.000Z',
              event_type: 'STATUS_CHANGED',
              from_status: 'SENDING',
              to_status: 'FAILED',
            },
          ],
        }),
      ),
    );
    render(
      <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver}>
        <CodeHighlightAdapterProvider adapter={highlightJsAdapter}>
          <QueryClientProvider client={newQueryClient()}>
            <DeliveryLogDrawer
              notificationId={ID}
              onClose={() => {}}
              onReplay={vi.fn().mockResolvedValue(undefined)}
            />
          </QueryClientProvider>
        </CodeHighlightAdapterProvider>
      </MantineProvider>,
    );
    expect(await screen.findByText('Invalid sender')).toBeInTheDocument();
    expect(screen.getByText('Attempt 1 failed, retry scheduled')).toBeInTheDocument();
    expect(screen.getByText('+180 ms')).toBeInTheDocument();
    expect(screen.getByText('+41.0 s')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Attempt 1/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('button', { name: /Replay notification/ })).toBeEnabled();
  });
});
