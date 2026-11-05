import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { server } from '../tests/server';
import { clockHandler } from '../tests/handlers';
import { renderWithProviders } from '../tests/render';
import { ClockOverrideBanner } from './ClockOverrideBanner';

describe('ClockOverrideBanner', () => {
  it('shows the overridden time when mode is not SYSTEM', async () => {
    server.use(clockHandler('FIXED'));
    renderWithProviders(<ClockOverrideBanner />);
    expect(await screen.findByTestId('clock-banner')).toHaveTextContent('System time overridden');
    expect(screen.getByTestId('clock-banner')).toHaveTextContent('FIXED');
  });

  it('includes the offset in OFFSET mode', async () => {
    server.use(clockHandler('OFFSET'));
    renderWithProviders(<ClockOverrideBanner />);
    expect(await screen.findByTestId('clock-banner')).toHaveTextContent('OFFSET P30D');
  });

  it('is hidden in SYSTEM mode', async () => {
    server.use(clockHandler('SYSTEM'));
    const { container } = renderWithProviders(<ClockOverrideBanner />);
    await waitFor(() => expect(container.querySelector('[data-testid="clock-banner"]')).toBeNull());
  });

  it('is hidden when the override API is disabled (404)', async () => {
    server.use(clockHandler('DISABLED'));
    renderWithProviders(<ClockOverrideBanner />);
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByTestId('clock-banner')).toBeNull();
  });
});
