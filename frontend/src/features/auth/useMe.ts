import { useQuery } from '@tanstack/react-query';
import { api, ApiError, unwrap, type Me } from '../../api/client';

export const ME_KEY = ['me'] as const;

/** Current operator; `null` when not signed in (401). */
export function useMe() {
  return useQuery<Me | null>({
    queryKey: ME_KEY,
    queryFn: async () => {
      try {
        return await unwrap(api.GET('/console/api/auth/me'));
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }
    },
    staleTime: 60_000,
    retry: false,
  });
}
