import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { API, clockHandler, meHandler } from '../../tests/handlers';
import { renderApp } from '../../tests/render';
import { server } from '../../tests/server';

const recipient = { id: 7, email: 'ops@example.com', display_name: 'Ops', is_active: true, version: 2 };
const rule = {
  rule_code: 'STUCK_DELIVERY_SPIKE',
  description: 'Stuck',
  enabled: true,
  threshold: 10,
  threshold_unit: 'COUNT',
  window_minutes: 10,
  min_sample: null,
  cooldown_minutes: 30,
  version: 1,
};

function base() {
  server.use(
    meHandler('ADMIN'),
    clockHandler('SYSTEM'),
    http.get(API('/console/api/alerts/recipients'), () => HttpResponse.json([recipient])),
    http.get(API('/console/api/alerts/rules'), () => HttpResponse.json([rule])),
    http.get(API('/console/api/alerts/events'), () =>
      HttpResponse.json({
        items: [
          {
            id: 1,
            rule_code: 'STUCK_DELIVERY_SPIKE',
            channel_type: 'SMS',
            kind: 'FIRED',
            measured_value: 12,
            threshold: 10,
            occurred_at: '2026-11-03T10:00:00Z',
            recipients: ['ops@example.com'],
            send_status: 'SENT',
          },
        ],
        total: 1,
      }),
    ),
  );
}

describe('Alerts page', () => {
  it('validates new recipient emails and posts valid ones', async () => {
    base();
    const posted: unknown[] = [];
    server.use(
      http.post(API('/console/api/alerts/recipients'), async ({ request }) => {
        posted.push(await request.json());
        return HttpResponse.json({ ...recipient, id: 8, email: 'new@example.com' }, { status: 201 });
      }),
    );
    renderApp('/alerts');
    expect(await screen.findByText('ops@example.com')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Email'), 'not-an-email');
    await userEvent.click(screen.getByRole('button', { name: 'Add recipient' }));
    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument();
    expect(posted).toHaveLength(0);
    await userEvent.clear(screen.getByLabelText('Email'));
    await userEvent.type(screen.getByLabelText('Email'), 'new@example.com');
    await userEvent.click(screen.getByRole('button', { name: 'Add recipient' }));
    await waitFor(() => expect(posted).toEqual([{ email: 'new@example.com' }]));
  });

  it('deactivate toggle sends version', async () => {
    base();
    const updates: unknown[] = [];
    server.use(
      http.put(API('/console/api/alerts/recipients/7'), async ({ request }) => {
        updates.push(await request.json());
        return HttpResponse.json({ ...recipient, is_active: false, version: 3 });
      }),
    );
    renderApp('/alerts');
    await userEvent.click(await screen.findByLabelText('Active ops@example.com'));
    await waitFor(() => expect(updates).toEqual([{ is_active: false, version: 2 }]));
  });

  it('rule edits respect bounds', async () => {
    base();
    const updates: unknown[] = [];
    server.use(
      http.put(API('/console/api/alerts/rules/STUCK_DELIVERY_SPIKE'), async ({ request }) => {
        updates.push(await request.json());
        return HttpResponse.json({ ...rule, version: 2 });
      }),
    );
    renderApp('/alerts');
    await userEvent.click(await screen.findByRole('tab', { name: 'Rules' }));
    const window = await screen.findByLabelText('Window STUCK_DELIVERY_SPIKE');
    await userEvent.clear(window);
    await userEvent.type(window, '2000');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('1..1440')).toBeInTheDocument();
    expect(updates).toHaveLength(0);
    await userEvent.clear(window);
    await userEvent.type(window, '15');
    const cooldown = screen.getByLabelText('Cooldown STUCK_DELIVERY_SPIKE');
    await userEvent.clear(cooldown);
    await userEvent.type(cooldown, '3');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('5..1440')).toBeInTheDocument();
    await userEvent.clear(cooldown);
    await userEvent.type(cooldown, '45');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updates).toHaveLength(1));
    expect(updates[0]).toMatchObject({ window_minutes: 15, cooldown_minutes: 45, version: 1 });
  });

  it('history table lists alert events', async () => {
    base();
    renderApp('/alerts');
    await userEvent.click(await screen.findByRole('tab', { name: 'History' }));
    expect(await screen.findByText('FIRED')).toBeInTheDocument();
    expect(screen.getByText('SENT')).toBeInTheDocument();
  });
});
