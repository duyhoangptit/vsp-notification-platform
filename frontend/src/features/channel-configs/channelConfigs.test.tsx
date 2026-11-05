import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { API, clockHandler, meHandler } from '../../tests/handlers';
import { renderApp } from '../../tests/render';
import { server } from '../../tests/server';

const smtp = {
  id: 1,
  channel_type: 'EMAIL',
  provider_key: 'SMTP',
  is_active: true,
  settings: { host: 'smtp.mailtrap.io', port: 2525, tls: true, from: 'PNH <no-reply@x>' },
  secrets: { password: { has_value: true, masked: '********' } },
  version: 3,
  updated_by: 'admin',
};

describe('Channel configurations', () => {
  it('masks secrets and sends only replaced secrets with the version', async () => {
    const puts: Record<string, unknown>[] = [];
    server.use(
      meHandler('ADMIN'),
      clockHandler('SYSTEM'),
      http.get(API('/console/api/channel-configs'), () => HttpResponse.json([smtp])),
      http.put(API('/console/api/channel-configs/1'), async ({ request }) => {
        puts.push((await request.json()) as Record<string, unknown>);
        return HttpResponse.json({ ...smtp, version: 4 });
      }),
    );
    renderApp('/channel-configs');
    const card = await screen.findByTestId('config-SMTP');
    expect(within(card).getByText('********')).toBeInTheDocument();
    expect(within(card).getByDisplayValue('smtp.mailtrap.io')).toBeInTheDocument();

    await userEvent.click(within(card).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].secrets).toBeUndefined();
    expect(puts[0].version).toBe(3);

    await userEvent.click(within(card).getByRole('button', { name: 'Replace Password' }));
    await userEvent.type(within(card).getByLabelText('Password (new value)'), 'n3w-pass');
    await userEvent.click(within(card).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(puts).toHaveLength(2));
    expect(puts[1].secrets).toEqual({ password: 'n3w-pass' });
  });

  it('shows the connection test result', async () => {
    server.use(
      meHandler('ADMIN'),
      clockHandler('SYSTEM'),
      http.get(API('/console/api/channel-configs'), () => HttpResponse.json([smtp])),
      http.post(API('/console/api/channel-configs/1/test'), () =>
        HttpResponse.json({
          success: false,
          provider_error: 'AuthenticationFailedException: 535',
          tested_at: '2026-11-03T10:00:00Z',
        }),
      ),
    );
    renderApp('/channel-configs');
    const card = await screen.findByTestId('config-SMTP');
    await userEvent.click(within(card).getByRole('button', { name: 'Test connection' }));
    expect(await within(card).findByTestId('test-result')).toHaveTextContent(
      'Failed: AuthenticationFailedException: 535',
    );
  });
});
