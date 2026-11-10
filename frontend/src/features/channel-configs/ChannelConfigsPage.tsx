import {
  Badge,
  Button,
  Card,
  FileInput,
  Group,
  JsonInput,
  Modal,
  NumberInput,
  Paper,
  PasswordInput,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconDeviceMobileMessage,
  IconMail,
  IconMessage,
  IconPlus,
  IconSearch,
  type Icon,
} from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { api, ApiError, unwrap, type Schemas } from '../../api/client';
import { FilterPanel, FilterSpan2 } from '../../components/FilterPanel';
import { PageHeader } from '../../components/PageHeader';
import { ProblemAlert } from '../../components/ProblemAlert';
import { formatDateTime } from '../../utils/format';
import { PROVIDERS, type FieldSpec } from './providerForms';

type ChannelConfig = Schemas['ChannelConfig'];

const CHANNELS = ['EMAIL', 'SMS', 'PUSH'];
const CHANNEL_ICONS: Record<string, Icon> = {
  EMAIL: IconMail,
  SMS: IconMessage,
  PUSH: IconDeviceMobileMessage,
};
type Settings = Record<string, unknown>;

function SettingField({
  spec,
  value,
  onChange,
}: {
  spec: FieldSpec;
  value: unknown;
  onChange: (v: unknown) => void;
}) {
  switch (spec.type) {
    case 'number':
      return (
        <NumberInput
          label={spec.label}
          withAsterisk={spec.required}
          value={(value as number) ?? ''}
          onChange={(v) => onChange(v === '' ? undefined : v)}
        />
      );
    case 'boolean':
      return (
        <Switch
          label={spec.label}
          checked={!!value}
          onChange={(e) => onChange(e.currentTarget.checked)}
          mt="lg"
        />
      );
    case 'select':
      return (
        <Select
          label={spec.label}
          withAsterisk={spec.required}
          data={spec.options ?? []}
          value={(value as string) ?? null}
          onChange={(v) => onChange(v ?? undefined)}
        />
      );
    default:
      return (
        <TextInput
          label={spec.label}
          withAsterisk={spec.required}
          value={(value as string) ?? ''}
          onChange={(e) => onChange(e.currentTarget.value)}
        />
      );
  }
}

interface EditorProps {
  config?: ChannelConfig;
  channel: string;
  providerKey: string;
  onSaved?: () => void;
}

/** Editor of one provider configuration: settings, write-only secrets, active switch, connection test. */
export function ConfigEditor({ config, channel, providerKey, onSaved }: EditorProps) {
  const qc = useQueryClient();
  const spec = PROVIDERS[providerKey];
  const [settings, setSettings] = useState<Settings>((config?.settings as Settings) ?? {});
  const [rawSettings, setRawSettings] = useState(JSON.stringify(config?.settings ?? {}, null, 2));
  const [active, setActive] = useState<boolean>(config?.is_active ?? false);
  const [replacing, setReplacing] = useState<Record<string, string>>({});
  const stored = config?.secrets ?? {};

  const save = useMutation({
    mutationFn: () => {
      const body = {
        channel_type: channel,
        provider_key: providerKey,
        is_active: active,
        // free-form JSON object in the contract (provider-specific schema)
        settings: (spec ? settings : JSON.parse(rawSettings)) as Record<string, never>,
        secrets: Object.keys(replacing).length ? replacing : undefined,
      };
      return config
        ? unwrap(
            api.PUT('/console/api/channel-configs/{id}', {
              params: { path: { id: config.id! } },
              body: { ...body, version: config.version ?? 0 },
            }),
          )
        : unwrap(api.POST('/console/api/channel-configs', { body }));
    },
    onSuccess: () => {
      notifications.show({ color: 'green', message: `${providerKey} saved` });
      setReplacing({});
      qc.invalidateQueries({ queryKey: ['channel-configs'] });
      onSaved?.();
    },
  });
  const test = useMutation({
    mutationFn: () =>
      unwrap(api.POST('/console/api/channel-configs/{id}/test', { params: { path: { id: config!.id! } } })),
    onSettled: () => qc.invalidateQueries({ queryKey: ['channel-configs'] }),
  });

  const secretKeys = Array.from(
    new Set([...(spec?.secrets.map((s) => s.key) ?? []), ...Object.keys(stored)]),
  );
  const conflict = save.error instanceof ApiError && save.error.code === 'VERSION_CONFLICT';

  return (
    <Card withBorder data-testid={`config-${providerKey}`}>
      <Stack gap="sm">
        <Group justify="space-between">
          <Text fw={700}>{providerKey}</Text>
          <Switch
            label="Active"
            checked={active}
            onChange={(e) => setActive(e.currentTarget.checked)}
            aria-label={`Active ${providerKey}`}
          />
        </Group>
        {spec ? (
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            {spec.settings.map((f) => (
              <SettingField
                key={f.key}
                spec={f}
                value={settings[f.key]}
                onChange={(v) => setSettings((s) => ({ ...s, [f.key]: v }))}
              />
            ))}
          </SimpleGrid>
        ) : (
          <JsonInput
            label="Settings (JSON)"
            value={rawSettings}
            onChange={setRawSettings}
            autosize
            minRows={3}
            formatOnBlur
          />
        )}
        <Text size="sm" fw={600}>
          Secrets (write-only)
        </Text>
        {secretKeys.map((key) => {
          const secretSpec = spec?.secrets.find((s) => s.key === key);
          const label = secretSpec?.label ?? key;
          const has = stored[key]?.has_value;
          if (key in replacing) {
            return secretSpec?.file ? (
              <FileInput
                key={key}
                label={`${label} (new file)`}
                accept="application/json"
                onChange={async (file) => {
                  const text = file ? await file.text() : '';
                  setReplacing((r) => ({ ...r, [key]: text }));
                }}
              />
            ) : (
              <PasswordInput
                key={key}
                label={`${label} (new value)`}
                value={replacing[key]}
                onChange={(e) => {
                  const v = e.currentTarget.value;
                  setReplacing((r) => ({ ...r, [key]: v }));
                }}
              />
            );
          }
          return (
            <Group key={key} justify="space-between">
              <Text size="sm">
                {label}:{' '}
                <Text span ff="monospace">
                  {has ? (stored[key]?.masked ?? '********') : 'not set'}
                </Text>{' '}
                {has && (
                  <Badge size="xs" variant="light">
                    set
                  </Badge>
                )}
              </Text>
              <Button size="xs" variant="default" onClick={() => setReplacing((r) => ({ ...r, [key]: '' }))}>
                {has ? `Replace ${label}` : `Set ${label}`}
              </Button>
            </Group>
          );
        })}
        <ProblemAlert
          error={save.error}
          onReload={conflict ? () => qc.invalidateQueries({ queryKey: ['channel-configs'] }) : undefined}
        />
        <Group>
          <Button onClick={() => save.mutate()} loading={save.isPending}>
            Save
          </Button>
          {config && (
            <Button variant="default" onClick={() => test.mutate()} loading={test.isPending}>
              Test connection
            </Button>
          )}
          {test.data && (
            <Badge color={test.data.success ? 'green' : 'red'} data-testid="test-result">
              {test.data.success ? 'Connection OK' : `Failed: ${test.data.provider_error}`}
            </Badge>
          )}
        </Group>
        {config?.last_test_at && (
          <Text size="xs" c="dimmed">
            Last test {formatDateTime(config.last_test_at)}: {config.last_test_result}
          </Text>
        )}
        {config && (
          <Text size="xs" c="dimmed">
            v{config.version} · updated {formatDateTime(config.updated_at)} by {config.updated_by}
          </Text>
        )}
      </Stack>
    </Card>
  );
}

/** Channel provider configuration (T173, US10) — ADMIN only. */
export function ChannelConfigsPage() {
  const { data, error } = useQuery({
    queryKey: ['channel-configs'],
    queryFn: () => unwrap(api.GET('/console/api/channel-configs')),
  });
  const [newProvider, setNewProvider] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [channelFilter, setChannelFilter] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const existing = new Set((data ?? []).map((c) => c.provider_key));
  const available = Object.keys(PROVIDERS).filter((k) => !existing.has(k));
  const { hash } = useLocation();
  // Deep link from the command palette: /channel-configs#provider-<key>.
  useEffect(() => {
    if (!data || !hash) return;
    document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView({ block: 'start' });
  }, [data, hash]);

  const needle = search.trim().toLowerCase();
  const visible = (data ?? []).filter(
    (c) =>
      (!channelFilter || c.channel_type === channelFilter) &&
      (!statusFilter || (statusFilter === 'ACTIVE') === !!c.is_active) &&
      (!needle || (c.provider_key ?? '').toLowerCase().includes(needle)),
  );
  const activeCount = [channelFilter, statusFilter, needle].filter(Boolean).length;
  const closeAdd = () => {
    setAdding(false);
    setNewProvider(null);
  };

  return (
    <Stack>
      <PageHeader
        title="Channel configurations"
        description="Delivery providers per channel. Secrets are write-only and never displayed."
        total={data ? visible.length : undefined}
        actions={
          <Button
            leftSection={<IconPlus size={16} aria-hidden />}
            onClick={() => setAdding(true)}
            disabled={!!data && available.length === 0}
          >
            Add provider
          </Button>
        }
      />
      <FilterPanel
        activeCount={activeCount}
        onReset={() => {
          setChannelFilter(null);
          setStatusFilter(null);
          setSearch('');
        }}
        footerStart={
          <Text size="xs" c="dimmed">
            Filters apply instantly
          </Text>
        }
      >
        <FilterSpan2>
          <TextInput
            label="Provider"
            placeholder="e.g. SMTP, FCM"
            leftSection={<IconSearch size={16} aria-hidden />}
            value={search}
            onChange={(e) => setSearch(e.currentTarget.value)}
          />
        </FilterSpan2>
        <Select
          label="Channel"
          placeholder="All channels"
          data={CHANNELS}
          value={channelFilter}
          onChange={setChannelFilter}
          clearable
        />
        <Select
          label="Status"
          placeholder="All statuses"
          data={[
            { value: 'ACTIVE', label: 'Active' },
            { value: 'INACTIVE', label: 'Inactive' },
          ]}
          value={statusFilter}
          onChange={setStatusFilter}
          clearable
        />
      </FilterPanel>
      <ProblemAlert error={error} />
      {CHANNELS.filter((ch) => !channelFilter || ch === channelFilter).map((channel) => {
        const configs = visible.filter((c) => c.channel_type === channel);
        const Icon = CHANNEL_ICONS[channel];
        return (
          <Stack key={channel} gap="xs" component="section" aria-labelledby={`channel-${channel}`}>
            <Group gap={8}>
              <Icon size={18} stroke={1.75} aria-hidden />
              <Title order={5} id={`channel-${channel}`}>
                {channel}
              </Title>
              <Badge size="sm" variant="light" color="gray">
                {configs.length}
              </Badge>
            </Group>
            {configs.length === 0 ? (
              <Paper withBorder radius="md" p="md" bg="var(--nh-surface-sunken)">
                <Text size="sm" c="dimmed">
                  {activeCount > 0
                    ? 'No provider matches these filters.'
                    : `No ${channel} provider configured yet.`}
                </Text>
              </Paper>
            ) : (
              configs.map((c) => (
                <div
                  key={`${c.id}-${c.version}`}
                  id={`provider-${c.provider_key}`}
                  style={{ scrollMarginTop: 72 }}
                >
                  <ConfigEditor config={c} channel={channel} providerKey={c.provider_key!} />
                </div>
              ))
            )}
          </Stack>
        );
      })}
      <Modal opened={adding} onClose={closeAdd} title="Add provider configuration" size="lg">
        <Stack>
          <Select
            label="Provider"
            placeholder="Choose a provider"
            data={available.map((k) => ({ value: k, label: `${k} · ${PROVIDERS[k].channel}` }))}
            value={newProvider}
            onChange={setNewProvider}
            data-autofocus
          />
          {newProvider && (
            <ConfigEditor
              key={newProvider}
              channel={PROVIDERS[newProvider].channel}
              providerKey={newProvider}
              onSaved={closeAdd}
            />
          )}
        </Stack>
      </Modal>
    </Stack>
  );
}
