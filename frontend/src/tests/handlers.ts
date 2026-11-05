import { http, HttpResponse } from 'msw';

export const API = (path: string) => `*${path}`;

export function meHandler(role: 'ADMIN' | 'TEMPLATE_EDITOR' | 'SUPPORT_VIEWER' | null) {
  return http.get(API('/console/api/auth/me'), () =>
    role
      ? HttpResponse.json({ username: role.toLowerCase(), display_name: `${role} user`, role })
      : HttpResponse.json({ type: 'x', title: 'Authentication required', status: 401, code: 'UNAUTHORIZED' }, { status: 401 }),
  );
}

export function clockHandler(mode: 'SYSTEM' | 'FIXED' | 'OFFSET' | 'DISABLED') {
  return http.get(API('/console/api/admin/clock'), () =>
    mode === 'DISABLED'
      ? HttpResponse.json({ code: 'NOT_FOUND', status: 404, title: 'Not found' }, { status: 404 })
      : HttpResponse.json({
          mode,
          now: '2027-01-01T08:00:00Z',
          real_now: '2026-11-03T10:00:00Z',
          fixed_instant: mode === 'FIXED' ? '2027-01-01T08:00:00Z' : null,
          offset: mode === 'OFFSET' ? 'P30D' : null,
          override_enabled: true,
        }),
  );
}
