import {
  IconAlertTriangle,
  IconClockHour4,
  IconHistory,
  IconLayoutDashboard,
  IconPlugConnected,
  IconSend,
  IconTemplate,
  type Icon,
} from '@tabler/icons-react';
import type { Role } from '../api/client';

export type NavGroup = 'Overview' | 'Messaging' | 'Configuration' | 'Administration';

export interface NavItem {
  path: string;
  label: string;
  roles: Role[];
  icon: Icon;
  group: NavGroup;
}

/** Display order of nav sections. */
export const NAV_GROUPS: NavGroup[] = ['Overview', 'Messaging', 'Configuration', 'Administration'];

/** Navigation per role (T066): ADMIN all; TEMPLATE_EDITOR dashboard+templates; SUPPORT_VIEWER dashboard+notifications. */
export const NAV_ITEMS: NavItem[] = [
  {
    path: '/dashboard',
    label: 'Dashboard',
    roles: ['ADMIN', 'TEMPLATE_EDITOR', 'SUPPORT_VIEWER'],
    icon: IconLayoutDashboard,
    group: 'Overview',
  },
  {
    path: '/templates',
    label: 'Templates',
    roles: ['ADMIN', 'TEMPLATE_EDITOR'],
    icon: IconTemplate,
    group: 'Messaging',
  },
  {
    path: '/notifications',
    label: 'Delivery Audit',
    roles: ['ADMIN', 'SUPPORT_VIEWER'],
    icon: IconSend,
    group: 'Messaging',
  },
  {
    path: '/channel-configs',
    label: 'Channel configs',
    roles: ['ADMIN'],
    icon: IconPlugConnected,
    group: 'Configuration',
  },
  { path: '/alerts', label: 'Alerts', roles: ['ADMIN'], icon: IconAlertTriangle, group: 'Configuration' },
  {
    path: '/config-change-log',
    label: 'Config change log',
    roles: ['ADMIN'],
    icon: IconHistory,
    group: 'Administration',
  },
  {
    path: '/admin/clock',
    label: 'System time',
    roles: ['ADMIN'],
    icon: IconClockHour4,
    group: 'Administration',
  },
];

export function navFor(role?: Role | null): NavItem[] {
  if (!role) return [];
  return NAV_ITEMS.filter((i) => i.roles.includes(role));
}

/** Nav item whose path is the longest prefix of {@code pathname}. */
export function activeNavItem(pathname: string): NavItem | undefined {
  return NAV_ITEMS.filter((i) => pathname === i.path || pathname.startsWith(`${i.path}/`)).sort(
    (a, b) => b.path.length - a.path.length,
  )[0];
}
