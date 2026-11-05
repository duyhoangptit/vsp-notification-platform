import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router';
import { routes } from '../app/router';

export function newQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
}

export function renderWithProviders(ui: ReactElement) {
  return render(
    <MantineProvider>
      <QueryClientProvider client={newQueryClient()}>{ui}</QueryClientProvider>
    </MantineProvider>,
  );
}

/** Renders the real route tree at {@code path}. */
export function renderApp(path: string, customRoutes: RouteObject[] = routes) {
  const router = createMemoryRouter(customRoutes, { initialEntries: [path] });
  const result = render(
    <MantineProvider>
      <QueryClientProvider client={newQueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>,
  );
  return { ...result, router };
}

export const ORIGIN = 'http://localhost:3000';
