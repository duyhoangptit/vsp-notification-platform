import type { Role } from '../api/client';

export interface NavItem {
  path: string;
  label: string;
  roles: Role[];
}

/** Navigation per role (T066): ADMIN all; TEMPLATE_EDITOR dashboard+templates; SUPPORT_VIEWER dashboard+notifications. */
export const NAV_ITEMS: NavItem[] = [
  { path: '/dashboard', label: 'Dashboard', roles: ['ADMIN', 'TEMPLATE_EDITOR', 'SUPPORT_VIEWER'] },
  { path: '/templates', label: 'Templates', roles: ['ADMIN', 'TEMPLATE_EDITOR'] },
  { path: '/notifications', label: 'Delivery Audit', roles: ['ADMIN', 'SUPPORT_VIEWER'] },
  { path: '/channel-configs', label: 'Channel configs', roles: ['ADMIN'] },
  { path: '/config-change-log', label: 'Config change log', roles: ['ADMIN'] },
  { path: '/alerts', label: 'Alerts', roles: ['ADMIN'] },
  { path: '/admin/clock', label: 'System time', roles: ['ADMIN'] },
];

export function navFor(role?: Role | null): NavItem[] {
  if (!role) return [];
  return NAV_ITEMS.filter((i) => i.roles.includes(role));
}
