import {
  Badge,
  Group,
  Kbd,
  Loader,
  Text,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { Spotlight, type SpotlightActionData } from '@mantine/spotlight';
import {
  IconExternalLink,
  IconMoon,
  IconPlug,
  IconPlus,
  IconSearch,
  IconSun,
  IconTemplate,
  IconZoomScan,
} from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { api, unwrap, type Role } from '../api/client';
import { useTemplates } from '../features/templates/api';
import { navFor } from './roles';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ICON = { size: 18, stroke: 1.75, 'aria-hidden': true } as const;

const TEMPLATE_ROLES: Role[] = ['ADMIN', 'TEMPLATE_EDITOR'];
const INSPECT_ROLES: Role[] = ['ADMIN', 'SUPPORT_VIEWER'];

/** Query-string key that opens the global DeliveryLogDrawer (see AppLayout). */
export const INSPECT_PARAM = 'inspect';

interface PaletteAction extends SpotlightActionData {
  id: string;
}

interface PaletteGroup {
  group: string;
  actions: PaletteAction[];
}

function matches(query: string, ...fields: (string | undefined | null)[]): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return fields.some((f) => f?.toLowerCase().includes(q));
}

/** Extracts a notification UUID from a pasted value (raw id, or a URL/log line containing one). */
export function extractNotificationId(input: string): string | null {
  const trimmed = input.trim();
  if (UUID_RE.test(trimmed)) return trimmed.toLowerCase();
  const found = trimmed.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
  return found ? found[0].toLowerCase() : null;
}

interface CommandPaletteProps {
  role?: Role | null;
}

/** Global Cmd/Ctrl+K palette: pages, templates, providers, notification-id lookup, preferences. */
export function CommandPalette({ role }: CommandPaletteProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const [query, setQuery] = useState('');
  const [opened, setOpened] = useState(false);
  const [debounced] = useDebouncedValue(query.trim(), 250);
  const { setColorScheme } = useMantineColorScheme();
  const scheme = useComputedColorScheme('light');

  const canTemplates = !!role && TEMPLATE_ROLES.includes(role);
  const canInspect = !!role && INSPECT_ROLES.includes(role);
  const isAdmin = role === 'ADMIN';
  const notificationId = extractNotificationId(query);

  const templatesEnabled = opened && canTemplates && debounced.length >= 2 && !notificationId;
  const templates = useTemplates({ q: debounced, page: 0, size: 6 }, { enabled: templatesEnabled });
  const providers = useQuery({
    queryKey: ['channel-configs'],
    queryFn: () => unwrap(api.GET('/console/api/channel-configs')),
    enabled: opened && isAdmin,
    staleTime: 60_000,
  });

  const inspect = (id: string) => {
    const params = new URLSearchParams(location.search);
    params.set(INSPECT_PARAM, id);
    navigate({ pathname: location.pathname, search: params.toString() });
  };

  const groups: PaletteGroup[] = [];

  if (notificationId && canInspect) {
    groups.push({
      group: 'Notification',
      actions: [
        {
          id: 'inspect',
          label: 'Inspect delivery log',
          description: notificationId,
          leftSection: <IconZoomScan {...ICON} />,
          rightSection: <Kbd size="xs">↵</Kbd>,
          onClick: () => inspect(notificationId),
        },
        {
          id: 'open-detail',
          label: 'Open notification detail page',
          description: notificationId,
          leftSection: <IconExternalLink {...ICON} />,
          onClick: () => navigate(`/notifications/${notificationId}`),
        },
      ],
    });
  }

  const pages = navFor(role)
    .filter((i) => matches(query, i.label, i.group))
    .map<PaletteAction>((i) => {
      const Icon = i.icon;
      return {
        id: `nav:${i.path}`,
        label: i.label,
        description: i.group,
        leftSection: <Icon {...ICON} />,
        onClick: () => navigate(i.path),
      };
    });
  if (canTemplates && matches(query, 'new template', 'create template')) {
    pages.push({
      id: 'new-template',
      label: 'Create new template',
      description: 'Messaging',
      leftSection: <IconPlus {...ICON} />,
      onClick: () => navigate('/templates/new'),
    });
  }
  if (pages.length) groups.push({ group: 'Go to', actions: pages });

  if (templatesEnabled) {
    const items = templates.data?.items ?? [];
    if (items.length) {
      groups.push({
        group: 'Templates',
        actions: items.map((t) => ({
          id: `tpl:${t.template_code}`,
          label: t.template_name ?? t.template_code,
          description: t.template_code,
          leftSection: <IconTemplate {...ICON} />,
          rightSection: (
            <Group gap={4} wrap="nowrap">
              <Badge size="xs" variant="light" color="gray">
                {t.channel_type}
              </Badge>
              {t.status === 'INACTIVE' && (
                <Badge size="xs" variant="outline" color="gray">
                  Inactive
                </Badge>
              )}
            </Group>
          ),
          onClick: () => navigate(`/templates/${encodeURIComponent(t.template_code)}`),
        })),
      });
    }
  }

  if (isAdmin && query.trim() && !notificationId) {
    const list = (providers.data ?? []).filter((c) =>
      matches(query, c.provider_key, c.channel_type, 'provider'),
    );
    if (list.length) {
      groups.push({
        group: 'Providers',
        actions: list.slice(0, 6).map((c) => ({
          id: `provider:${c.provider_key}`,
          label: `Configure ${c.provider_key}`,
          description: `${c.channel_type} provider`,
          leftSection: <IconPlug {...ICON} />,
          onClick: () => navigate(`/channel-configs#provider-${c.provider_key}`),
        })),
      });
    }
  }

  const nextScheme = scheme === 'dark' ? 'light' : 'dark';
  if (matches(query, 'theme', 'dark', 'light', 'mode', 'color scheme')) {
    groups.push({
      group: 'Preferences',
      actions: [
        {
          id: 'scheme',
          label: `Switch to ${nextScheme} mode`,
          leftSection: nextScheme === 'dark' ? <IconMoon {...ICON} /> : <IconSun {...ICON} />,
          onClick: () => setColorScheme(nextScheme),
        },
      ],
    });
  }

  const loadingTemplates = templatesEnabled && templates.isFetching && !templates.data;
  let empty: ReactNode = 'Nothing found';
  if (notificationId && !canInspect) empty = 'Your role cannot inspect notifications';
  else if (loadingTemplates) empty = <Loader size="sm" />;

  return (
    <Spotlight.Root
      query={query}
      onQueryChange={setQuery}
      shortcut={['mod + K', '/']}
      onSpotlightOpen={() => setOpened(true)}
      onSpotlightClose={() => setOpened(false)}
      scrollable
      maxHeight={440}
      size="lg"
    >
      <Spotlight.Search
        placeholder="Search pages, templates, providers — or paste a notification ID"
        leftSection={<IconSearch {...ICON} />}
        rightSection={loadingTemplates ? <Loader size="xs" /> : null}
        aria-label="Command palette search"
      />
      <Spotlight.ActionsList>
        {groups.length > 0 ? (
          groups.map((g) => (
            <Spotlight.ActionsGroup key={g.group} label={g.group}>
              {g.actions.map(({ id, ...action }) => (
                <Spotlight.Action key={id} highlightQuery {...action} />
              ))}
            </Spotlight.ActionsGroup>
          ))
        ) : (
          <Spotlight.Empty>{empty}</Spotlight.Empty>
        )}
      </Spotlight.ActionsList>
      <Spotlight.Footer>
        <Group gap="md" justify="space-between">
          <Group gap={6}>
            <Kbd size="xs">↑</Kbd>
            <Kbd size="xs">↓</Kbd>
            <Text size="xs" c="dimmed">
              navigate
            </Text>
            <Kbd size="xs">↵</Kbd>
            <Text size="xs" c="dimmed">
              open
            </Text>
            <Kbd size="xs">esc</Kbd>
            <Text size="xs" c="dimmed">
              close
            </Text>
          </Group>
          <Text size="xs" c="dimmed" visibleFrom="xs">
            Tip: paste a notification ID to inspect it
          </Text>
        </Group>
      </Spotlight.Footer>
    </Spotlight.Root>
  );
}
