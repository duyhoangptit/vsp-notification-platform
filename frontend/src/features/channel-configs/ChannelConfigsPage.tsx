import {
  Badge,
  Button,
  Card,
  FileInput,
  Group,
  JsonInput,
  NumberInput,
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
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, ApiError, unwrap, type Schemas } from '../../api/client';
import { ProblemAlert } from '../../components/ProblemAlert';
import { formatDateTime } from '../../utils/format';
import { PROVIDERS, type FieldSpec } from './providerForms';

type ChannelConfig = Schemas['ChannelConfig'];
type Settings = Record<string, unknown>;

function SettingField({ spec, value, onChange }: { spec: FieldSpec; value: unknown; onChange: (v: unknown) => void }) {
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
      return <Switch label={spec.label} checked={!!value} onChange={(e) => onChange(e.currentTarget.checked)} mt="lg" />;
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
    mutationFn: () => unwrap(api.POST('/console/api/channel-configs/{id}/test', { params: { path: { id: config!.id! } } })),
    onSettled: () => qc.invalidateQueries({ queryKey: ['channel-configs'] }),
  });

  const secretKeys = Array.from(new Set([...(spec?.secrets.map((s) => s.key) ?? []), ...Object.keys(stored)]));
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
          <JsonInput label="Settings (JSON)" value={rawSettings} onChange={setRawSettings} autosize minRows={3} formatOnBlur />
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
  const channels = ['EMAIL', 'SMS', 'PUSH'];
  const existing = new Set((data ?? []).map((c) => c.provider_key));

  return (
    <Stack>
      <Title order={3}>Channel configurations</Title>
      <ProblemAlert error={error} />
      {channels.map((channel) => (
        <Stack key={channel} gap="xs">
          <Title order={5}>{channel}</Title>
          {(data ?? [])
            .filter((c) => c.channel_type === channel)
            .map((c) => (
              <ConfigEditor key={`${c.id}-${c.version}`} config={c} channel={channel} providerKey={c.provider_key!} />
            ))}
        </Stack>
      ))}
      <Card withBorder>
        <Stack>
          <Select
            label="Add provider configuration"
            placeholder="Provider"
            data={Object.keys(PROVIDERS).filter((k) => !existing.has(k))}
            value={newProvider}
            onChange={setNewProvider}
            clearable
          />
          {newProvider && (
            <ConfigEditor
              key={newProvider}
              channel={PROVIDERS[newProvider].channel}
              providerKey={newProvider}
              onSaved={() => setNewProvider(null)}
            />
          )}
        </Stack>
      </Card>
    </Stack>
  );
}
