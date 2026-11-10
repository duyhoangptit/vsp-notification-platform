import { Code, Select, Stack, Table, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconHash, IconUser } from '@tabler/icons-react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { DataTable } from 'mantine-datatable';
import { useState } from 'react';
import { api, unwrap } from '../../api/client';
import { FilterPanel } from '../../components/FilterPanel';
import { PageHeader } from '../../components/PageHeader';
import { ProblemAlert } from '../../components/ProblemAlert';
import { formatDateTime } from '../../utils/format';

const ENTITY_TYPES = [
  'CLIENT',
  'TEMPLATE',
  'USER_PREFERENCE',
  'CHANNEL_CONFIG',
  'OPERATOR',
  'ALERT_RECIPIENT',
  'ALERT_RULE',
  'CLOCK_OVERRIDE',
] as const;
type EntityType = (typeof ENTITY_TYPES)[number];

function show(v: unknown): string {
  if (v === undefined || v === null) return '—';
  return typeof v === 'object' ? JSON.stringify(v) : String(v);
}

/** Field-level diff: `{field: {old, new}}`, secrets as `***changed***`. */
export function Diff({ fields }: { fields: Record<string, unknown> }) {
  const entries = Object.entries(fields ?? {});
  if (entries.length === 0) return <Code>no field changes</Code>;
  return (
    <Table withRowBorders={false} data-testid="diff">
      <Table.Tbody>
        {entries.map(([field, change]) => {
          const c = change as { old?: unknown; new?: unknown } | string;
          return (
            <Table.Tr key={field}>
              <Table.Td>
                <Code>{field}</Code>
              </Table.Td>
              <Table.Td>{typeof c === 'string' ? c : `${show(c?.old)} → ${show(c?.new)}`}</Table.Td>
            </Table.Tr>
          );
        })}
      </Table.Tbody>
    </Table>
  );
}

/** Configuration change log (T174, FR-032) — ADMIN only. */
export function ConfigChangeLogPage() {
  const [entityType, setEntityType] = useState<EntityType | null>(null);
  const [entityId, setEntityId] = useState('');
  const [actor, setActor] = useState('');
  const [page, setPage] = useState(1);
  const [debouncedId] = useDebouncedValue(entityId, 300);
  const [debouncedActor] = useDebouncedValue(actor, 300);
  const query = {
    entity_type: entityType ?? undefined,
    entity_id: debouncedId || undefined,
    actor: debouncedActor || undefined,
    page: page - 1,
    size: 50,
  };
  const { data, error, isFetching } = useQuery({
    queryKey: ['config-change-log', query],
    queryFn: () => unwrap(api.GET('/console/api/config-change-log', { params: { query } })),
    placeholderData: keepPreviousData,
  });

  const activeCount = [entityType, entityId.trim(), actor.trim()].filter(Boolean).length;
  const reset = () => {
    setEntityType(null);
    setEntityId('');
    setActor('');
    setPage(1);
  };

  return (
    <Stack>
      <PageHeader
        title="Configuration change log"
        description="Every configuration change with its author and field-level diff. Secrets are never shown."
        total={data?.total}
      />
      <FilterPanel activeCount={activeCount} onReset={reset}>
        <Select
          label="Entity type"
          placeholder="All entity types"
          data={[...ENTITY_TYPES]}
          value={entityType}
          onChange={(v) => {
            setEntityType(v as EntityType | null);
            setPage(1);
          }}
          clearable
        />
        <TextInput
          label="Entity id"
          placeholder="e.g. OTP_LOGIN"
          leftSection={<IconHash size={16} aria-hidden />}
          value={entityId}
          onChange={(e) => {
            setEntityId(e.currentTarget.value);
            setPage(1);
          }}
        />
        <TextInput
          label="Actor"
          placeholder="Username"
          leftSection={<IconUser size={16} aria-hidden />}
          value={actor}
          onChange={(e) => {
            setActor(e.currentTarget.value);
            setPage(1);
          }}
        />
      </FilterPanel>
      <ProblemAlert error={error} />
      <DataTable
        withTableBorder
        minHeight={180}
        fetching={isFetching}
        records={data?.items ?? []}
        idAccessor="id"
        totalRecords={data?.total ?? 0}
        recordsPerPage={50}
        page={page}
        onPageChange={setPage}
        noRecordsText="No changes match these filters"
        columns={[
          { accessor: 'occurred_at', title: 'When', render: (r) => formatDateTime(r.occurred_at) },
          { accessor: 'entity_type', title: 'Entity type' },
          { accessor: 'entity_id', title: 'Entity id' },
          { accessor: 'action' },
          { accessor: 'version' },
          { accessor: 'actor' },
          {
            accessor: 'changed_fields',
            title: 'Changes',
            render: (r) => <Diff fields={(r.changed_fields ?? {}) as Record<string, unknown>} />,
          },
        ]}
      />
    </Stack>
  );
}
