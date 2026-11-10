import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { clockHandler, meHandler } from '../../tests/handlers';
import { renderApp } from '../../tests/render';
import { server } from '../../tests/server';

describe('auth and role navigation', () => {
  it('redirects to /login on 401', async () => {
    server.use(meHandler(null), clockHandler('SYSTEM'));
    const { router } = renderApp('/dashboard');
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('TEMPLATE_EDITOR sees dashboard and templates only', async () => {
    server.use(meHandler('TEMPLATE_EDITOR'), clockHandler('SYSTEM'));
    renderApp('/dashboard');
    const nav = await screen.findByRole('navigation', { name: 'Main navigation' });
    expect(within(nav).getByText('Dashboard')).toBeInTheDocument();
    expect(within(nav).getByText('Templates')).toBeInTheDocument();
    expect(within(nav).queryByText('Delivery Audit')).toBeNull();
    expect(within(nav).queryByText('Channel configs')).toBeNull();
    expect(within(nav).queryByText('Alerts')).toBeNull();
  });

  it('SUPPORT_VIEWER sees dashboard and delivery audit, and a 403 page for templates', async () => {
    server.use(meHandler('SUPPORT_VIEWER'), clockHandler('SYSTEM'));
    renderApp('/templates');
    const nav = await screen.findByRole('navigation', { name: 'Main navigation' });
    expect(within(nav).getByText('Delivery Audit')).toBeInTheDocument();
    expect(within(nav).queryByText('Templates')).toBeNull();
    expect(await screen.findByText(/403/)).toBeInTheDocument();
  });

  it('ADMIN sees every item', async () => {
    server.use(meHandler('ADMIN'), clockHandler('SYSTEM'));
    renderApp('/dashboard');
    const nav = await screen.findByRole('navigation', { name: 'Main navigation' });
    for (const label of [
      'Dashboard',
      'Templates',
      'Delivery Audit',
      'Channel configs',
      'Config change log',
      'Alerts',
    ]) {
      expect(within(nav).getByText(label)).toBeInTheDocument();
    }
  });
});
