import { Alert } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { api, ApiError, unwrap } from '../api/client';
import { formatDateTime } from '../utils/format';

export const CLOCK_KEY = ['clock'] as const;

export function useClock() {
  return useQuery({
    queryKey: CLOCK_KEY,
    queryFn: async () => {
      try {
        return await unwrap(api.GET('/console/api/admin/clock'));
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null; // override disabled (production)
        throw e;
      }
    },
    refetchInterval: 30_000,
    retry: false,
  });
}

/** Shown on every page while the system time is overridden (R29). */
export function ClockOverrideBanner() {
  const { data } = useClock();
  if (!data || !data.mode || data.mode === 'SYSTEM') return null;
  return (
    <Alert color="yellow" variant="filled" radius={0} py={6} data-testid="clock-banner">
      System time overridden: {formatDateTime(data.now)} ({data.mode}
      {data.mode === 'OFFSET' && data.offset ? ` ${data.offset}` : ''})
    </Alert>
  );
}
