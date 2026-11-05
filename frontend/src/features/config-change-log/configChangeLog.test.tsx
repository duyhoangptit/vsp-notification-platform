import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { API, clockHandler, meHandler } from '../../tests/handlers';
import { renderApp } from '../../tests/render';
import { server } from '../../tests/server';

describe('Config change log', () => {
  it('filters by entity type and shows the diff with masked secrets', async () => {
    const seen: URLSearchParams[] = [];
    server.use(
      meHandler('ADMIN'),
      clockHandler('SYSTEM'),
      http.get(API('/console/api/config-change-log'), ({ request }) => {
        seen.push(new URL(request.url).searchParams);
        return HttpResponse.json({
          items: [
            {
              id: 9,
              entity_type: 'CHANNEL_CONFIG',
              entity_id: '1',
              action: 'UPDATE',
              version: 4,
              actor: 'admin',
              occurred_at: '2026-11-03T10:00:00Z',
              changed_fields: { password: '***changed***', is_active: { old: false, new: true } },
            },
          ],
          total: 1,
        });
      }),
    );
    renderApp('/config-change-log');
    expect(await screen.findByText('***changed***')).toBeInTheDocument();
    expect(screen.getByText('false → true')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('textbox', { name: 'Entity type' }));
    await userEvent.click(await screen.findByRole('option', { name: 'ALERT_RULE', hidden: true }));
    await waitFor(() => expect(seen.some((p) => p.get('entity_type') === 'ALERT_RULE')).toBe(true));
  });
});
