import { Badge, Button, Group, Select, Stack, TextInput, Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { DataTable } from 'mantine-datatable';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { ProblemAlert } from '../../components/ProblemAlert';
import { formatDateTime } from '../../utils/format';
import { useTemplates } from './api';

const PAGE_SIZE = 20;

/** Template list with filters (T137). */
export function TemplateListPage() {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [channel, setChannel] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [debouncedQ] = useDebouncedValue(q, 300);
  const { data, isFetching, error } = useTemplates({
    q: debouncedQ,
    channel_type: channel ?? undefined,
    status: (status as 'ACTIVE' | 'INACTIVE' | null) ?? undefined,
    page: page - 1,
    size: PAGE_SIZE,
  });

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>Templates</Title>
        <Button onClick={() => navigate('/templates/new')}>New template</Button>
      </Group>
      <Group>
        <TextInput
          label="Search"
          placeholder="Code or name"
          value={q}
          onChange={(e) => {
            setQ(e.currentTarget.value);
            setPage(1);
          }}
        />
        <Select
          label="Channel"
          data={['EMAIL', 'SMS', 'PUSH']}
          value={channel}
          onChange={(v) => {
            setChannel(v);
            setPage(1);
          }}
          clearable
        />
        <Select
          label="Status"
          data={['ACTIVE', 'INACTIVE']}
          value={status}
          onChange={(v) => {
            setStatus(v);
            setPage(1);
          }}
          clearable
        />
      </Group>
      <ProblemAlert error={error} />
      <DataTable
        withTableBorder
        highlightOnHover
        minHeight={180}
        fetching={isFetching}
        records={data?.items ?? []}
        idAccessor="template_code"
        totalRecords={data?.total ?? 0}
        recordsPerPage={PAGE_SIZE}
        page={page}
        onPageChange={setPage}
        noRecordsText="No templates match these filters"
        onRowClick={({ record }) => navigate(`/templates/${record.template_code}`)}
        columns={[
          { accessor: 'template_code', title: 'Code' },
          { accessor: 'template_name', title: 'Name' },
          { accessor: 'channel_type', title: 'Channel' },
          { accessor: 'category', title: 'Category' },
          { accessor: 'is_critical', title: 'Critical', render: (r) => (r.is_critical ? 'Yes' : '') },
          {
            accessor: 'status',
            render: (r) => <Badge color={r.status === 'ACTIVE' ? 'green' : 'gray'}>{r.status}</Badge>,
          },
          { accessor: 'updated_by', title: 'Updated by' },
          { accessor: 'updated_at', title: 'Updated', render: (r) => formatDateTime(r.updated_at) },
        ]}
      />
    </Stack>
  );
}
