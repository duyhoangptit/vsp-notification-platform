import { createBrowserRouter, Navigate, type RouteObject } from 'react-router';
import { AlertsPage } from '../features/alerts/AlertsPage';
import { ClockPage } from '../features/admin/ClockPage';
import { LoginPage } from '../features/auth/LoginPage';
import { ChannelConfigsPage } from '../features/channel-configs/ChannelConfigsPage';
import { ConfigChangeLogPage } from '../features/config-change-log/ConfigChangeLogPage';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { NotificationDetailPage } from '../features/notifications/NotificationDetailPage';
import { NotificationSearchPage } from '../features/notifications/NotificationSearchPage';
import { TemplateEditorPage } from '../features/templates/TemplateEditorPage';
import { TemplateListPage } from '../features/templates/TemplateListPage';
import { AppLayout } from './AppLayout';
import { RequireRole } from './RequireRole';

export const routes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <RequireRole>
        <AppLayout />
      </RequireRole>
    ),
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <DashboardPage /> },
      { path: 'templates', element: <RequireRole roles={['ADMIN', 'TEMPLATE_EDITOR']}><TemplateListPage /></RequireRole> },
      { path: 'templates/new', element: <RequireRole roles={['ADMIN', 'TEMPLATE_EDITOR']}><TemplateEditorPage /></RequireRole> },
      { path: 'templates/:code', element: <RequireRole roles={['ADMIN', 'TEMPLATE_EDITOR']}><TemplateEditorPage /></RequireRole> },
      { path: 'channel-configs', element: <RequireRole roles={['ADMIN']}><ChannelConfigsPage /></RequireRole> },
      { path: 'notifications', element: <RequireRole roles={['ADMIN', 'SUPPORT_VIEWER']}><NotificationSearchPage /></RequireRole> },
      { path: 'notifications/:id', element: <RequireRole roles={['ADMIN', 'SUPPORT_VIEWER']}><NotificationDetailPage /></RequireRole> },
      { path: 'config-change-log', element: <RequireRole roles={['ADMIN']}><ConfigChangeLogPage /></RequireRole> },
      { path: 'alerts', element: <RequireRole roles={['ADMIN']}><AlertsPage /></RequireRole> },
      { path: 'admin/clock', element: <RequireRole roles={['ADMIN']}><ClockPage /></RequireRole> },
    ],
  },
  { path: '*', element: <Navigate to="/dashboard" replace /> },
];

export const createRouter = () => createBrowserRouter(routes);
