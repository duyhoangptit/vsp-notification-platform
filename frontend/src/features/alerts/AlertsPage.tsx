import {
  ActionIcon,
  Badge,
  Button,
  Divider,
  Group,
  NumberInput,
  Paper,
  SimpleGrid,
  Stack,
  Switch,
  Table,
  Tabs,
  Text,
  TextInput,
  Tooltip,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import {
  IconAdjustments,
  IconAt,
  IconHistory,
  IconPlus,
  IconSend,
  IconTrash,
  IconUser,
  IconUsers,
} from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { DataTable } from 'mantine-datatable';
import { useState } from 'react';
import { api, unwrap, type Schemas } from '../../api/client';
import { PageHeader } from '../../components/PageHeader';
import { ProblemAlert } from '../../components/ProblemAlert';
import { formatDateTime } from '../../utils/format';

type Rule = Schemas['AlertRule'];
const EMAIL = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

function Recipients() {
  const qc = useQueryClient();
  const { data, error } = useQuery({
    queryKey: ['alert-recipients'],
    queryFn: () => unwrap(api.GET('/console/api/alerts/recipients')),
  });
  const form = useForm({
    initialValues: { email: '', display_name: '' },
    validate: {
      email: (v) => (EMAIL.test(v.trim()) && v.length <= 254 ? null : 'Enter a valid email address'),
    },
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ['alert-recipients'] });
  const add = useMutation({
    mutationFn: (v: { email: string; display_name: string }) =>
      unwrap(
        api.POST('/console/api/alerts/recipients', {
          body: { email: v.email.trim(), display_name: v.display_name || undefined },
        }),
      ),
    onSuccess: () => {
      form.reset();
      refresh();
    },
  });
  const toggle = useMutation({
    mutationFn: (r: { id: number; is_active: boolean; version: number }) =>
      unwrap(
        api.PUT('/console/api/alerts/recipients/{id}', {
          params: { path: { id: r.id } },
          body: { is_active: r.is_active, version: r.version },
        }),
      ),
    onSettled: refresh,
  });
  const remove = useMutation({
    mutationFn: (r: { id: number; version: number }) =>
      unwrap(
        api.DELETE('/console/api/alerts/recipients/{id}', {
          params: { path: { id: r.id }, query: { version: r.version } },
        }),
      ),
    onSettled: refresh,
  });
  const test = useMutation({
    mutationFn: () => unwrap(api.POST('/console/api/alerts/recipients/test')),
    onSuccess: (r) =>
      notifications.show({
        color: r.send_status === 'SENT' ? 'green' : 'red',
        message: `Test alert: ${r.send_status}${r.error ? ' — ' + r.error : ''}`,
      }),
  });

  return (
    <Stack>
      <Paper
        withBorder
        radius="md"
        p="md"
        component="form"
        aria-label="Add recipient"
        onSubmit={form.onSubmit((v) => add.mutate(v))}
      >
        <Stack gap="sm">
          <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="sm">
            <TextInput
              label="Email"
              inputMode="email"
              placeholder="ops@example.com"
              leftSection={<IconAt size={16} aria-hidden />}
              {...form.getInputProps('email')}
            />
            <TextInput
              label="Display name"
              placeholder="Optional"
              leftSection={<IconUser size={16} aria-hidden />}
              {...form.getInputProps('display_name')}
            />
          </SimpleGrid>
          <Divider />
          <Group justify="space-between" gap="sm">
            <Text size="xs" c="dimmed">
              Alerts are emailed to every active recipient.
            </Text>
            <Group gap="xs">
              <Button
                variant="default"
                leftSection={<IconSend size={16} aria-hidden />}
                onClick={() => test.mutate()}
                loading={test.isPending}
              >
                Send test alert
              </Button>
              <Button type="submit" leftSection={<IconPlus size={16} aria-hidden />} loading={add.isPending}>
                Add recipient
              </Button>
            </Group>
          </Group>
        </Stack>
      </Paper>
      <ProblemAlert error={error ?? add.error ?? toggle.error ?? remove.error} />
      <Table striped withTableBorder>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Email</Table.Th>
            <Table.Th>Name</Table.Th>
            <Table.Th>Active</Table.Th>
            <Table.Th>Updated</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(data ?? []).map((r) => (
            <Table.Tr key={r.id}>
              <Table.Td>{r.email}</Table.Td>
              <Table.Td>{r.display_name ?? '—'}</Table.Td>
              <Table.Td>
                <Switch
                  aria-label={`Active ${r.email}`}
                  checked={!!r.is_active}
                  onChange={(e) =>
                    toggle.mutate({ id: r.id!, is_active: e.currentTarget.checked, version: r.version ?? 0 })
                  }
                />
              </Table.Td>
              <Table.Td>
                {formatDateTime(r.updated_at)} · {r.updated_by}
              </Table.Td>
              <Table.Td>
                <Tooltip label="Remove">
                  <ActionIcon
                    variant="subtle"
                    color="red"
                    aria-label={`Remove ${r.email}`}
                    onClick={() => remove.mutate({ id: r.id!, version: r.version ?? 0 })}
                  >
                    <IconTrash size={16} />
                  </ActionIcon>
                </Tooltip>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

function RuleRow({ rule }: { rule: Rule }) {
  const qc = useQueryClient();
  const form = useForm({
    initialValues: {
      enabled: !!rule.enabled,
      threshold: rule.threshold ?? 0,
      window_minutes: rule.window_minutes ?? 10,
      min_sample: rule.min_sample ?? null,
      cooldown_minutes: rule.cooldown_minutes ?? 30,
    },
    validate: {
      threshold: (v) => (v >= 0 ? null : '≥ 0'),
      window_minutes: (v) => (v >= 1 && v <= 1440 ? null : '1..1440'),
      cooldown_minutes: (v) => (v >= 5 && v <= 1440 ? null : '5..1440'),
      min_sample: (v) => (v === null || v === undefined || v >= 1 ? null : '≥ 1'),
    },
  });
  const save = useMutation({
    mutationFn: (v: typeof form.values) =>
      unwrap(
        api.PUT('/console/api/alerts/rules/{rule_code}', {
          params: { path: { rule_code: rule.rule_code! } },
          body: { ...v, min_sample: v.min_sample ?? null, version: rule.version ?? 0 },
        }),
      ),
    onSuccess: () => {
      notifications.show({ color: 'green', message: `${rule.rule_code} saved` });
      qc.invalidateQueries({ queryKey: ['alert-rules'] });
    },
  });
  const unit = rule.threshold_unit === 'PERCENT' ? '%' : rule.threshold_unit === 'MINUTES' ? 'min' : '#';
  return (
    <Table.Tr>
      <Table.Td>
        <Text fw={600} size="sm">
          {rule.rule_code}
        </Text>
        <Text size="xs" c="dimmed">
          {rule.description}
        </Text>
        <ProblemAlert
          error={save.error}
          onReload={() => qc.invalidateQueries({ queryKey: ['alert-rules'] })}
        />
      </Table.Td>
      <Table.Td>
        <Switch
          aria-label={`Enabled ${rule.rule_code}`}
          {...form.getInputProps('enabled', { type: 'checkbox' })}
        />
      </Table.Td>
      <Table.Td>
        <NumberInput
          aria-label={`Threshold ${rule.rule_code}`}
          rightSection={unit}
          min={0}
          w={110}
          {...form.getInputProps('threshold')}
        />
      </Table.Td>
      <Table.Td>
        <NumberInput
          aria-label={`Window ${rule.rule_code}`}
          clampBehavior="none"
          min={1}
          max={1440}
          w={100}
          {...form.getInputProps('window_minutes')}
        />
      </Table.Td>
      <Table.Td>
        {rule.rule_code === 'CHANNEL_FAILURE_RATE' ? (
          <NumberInput
            aria-label={`Min sample ${rule.rule_code}`}
            min={1}
            w={100}
            {...form.getInputProps('min_sample')}
          />
        ) : (
          '—'
        )}
      </Table.Td>
      <Table.Td>
        <NumberInput
          aria-label={`Cooldown ${rule.rule_code}`}
          clampBehavior="none"
          min={5}
          max={1440}
          w={100}
          {...form.getInputProps('cooldown_minutes')}
        />
      </Table.Td>
      <Table.Td>
        <Button size="xs" onClick={() => form.onSubmit((v) => save.mutate(v))()} loading={save.isPending}>
          Save
        </Button>
      </Table.Td>
    </Table.Tr>
  );
}

function Rules() {
  const { data, error } = useQuery({
    queryKey: ['alert-rules'],
    queryFn: () => unwrap(api.GET('/console/api/alerts/rules')),
  });
  return (
    <Stack>
      <ProblemAlert error={error} />
      <Table withTableBorder>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Rule</Table.Th>
            <Table.Th>Enabled</Table.Th>
            <Table.Th>Threshold</Table.Th>
            <Table.Th>Window (min)</Table.Th>
            <Table.Th>Min sample</Table.Th>
            <Table.Th>Cooldown (min)</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(data ?? []).map((r) => (
            <RuleRow key={`${r.rule_code}-${r.version}`} rule={r} />
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

function History() {
  const [page, setPage] = useState(1);
  const { data, isFetching, error } = useQuery({
    queryKey: ['alert-events', page],
    queryFn: () =>
      unwrap(api.GET('/console/api/alerts/events', { params: { query: { page: page - 1, size: 20 } } })),
  });
  return (
    <Stack>
      <ProblemAlert error={error} />
      <DataTable
        withTableBorder
        minHeight={160}
        fetching={isFetching}
        records={data?.items ?? []}
        idAccessor="id"
        totalRecords={data?.total ?? 0}
        recordsPerPage={20}
        page={page}
        onPageChange={setPage}
        noRecordsText="No alerts yet"
        columns={[
          { accessor: 'occurred_at', title: 'When', render: (e) => formatDateTime(e.occurred_at) },
          { accessor: 'rule_code', title: 'Rule' },
          { accessor: 'channel_type', title: 'Channel', render: (e) => e.channel_type ?? 'ALL' },
          {
            accessor: 'kind',
            render: (e) => <Badge color={e.kind === 'FIRED' ? 'red' : 'green'}>{e.kind}</Badge>,
          },
          { accessor: 'measured_value', title: 'Value' },
          { accessor: 'threshold' },
          {
            accessor: 'send_status',
            title: 'Email',
            render: (e) => (
              <Badge color={e.send_status === 'SENT' ? 'teal' : 'orange'}>{e.send_status}</Badge>
            ),
          },
          { accessor: 'recipients', render: (e) => (e.recipients ?? []).length },
        ]}
      />
    </Stack>
  );
}

/** Incident alert settings and history (T155, US11) — ADMIN only. */
export function AlertsPage() {
  return (
    <Stack>
      <PageHeader
        title="Alerts"
        description="Incident emails when delivery health degrades: who receives them, when they fire, and what was sent."
      />
      <Tabs defaultValue="recipients" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="recipients" leftSection={<IconUsers size={16} aria-hidden />}>
            Recipients
          </Tabs.Tab>
          <Tabs.Tab value="rules" leftSection={<IconAdjustments size={16} aria-hidden />}>
            Rules
          </Tabs.Tab>
          <Tabs.Tab value="history" leftSection={<IconHistory size={16} aria-hidden />}>
            History
          </Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="recipients" pt="md">
          <Recipients />
        </Tabs.Panel>
        <Tabs.Panel value="rules" pt="md">
          <Rules />
        </Tabs.Panel>
        <Tabs.Panel value="history" pt="md">
          <History />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
