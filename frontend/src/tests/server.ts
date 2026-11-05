import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

/** Shared MSW server; default handlers keep incidental requests (e.g. the dashboard after login) quiet. */
export const server = setupServer(
  http.get('*/console/api/dashboard/volume', () => HttpResponse.json({ buckets: [] })),
  http.get('*/console/api/dashboard/channel-rates', () => HttpResponse.json([])),
);
