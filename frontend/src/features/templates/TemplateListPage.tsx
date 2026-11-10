import { Badge, Button, Select, Stack, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import { DataTable } from 'mantine-datatable';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { FilterPanel, FilterSpan2 } from '../../components/FilterPanel';
import { PageHeader } from '../../components/PageHeader';
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

  const activeCount = [q.trim(), channel, status].filter(Boolean).length;
  const reset = () => {
    setQ('');
    setChannel(null);
    setStatus(null);
    setPage(1);
  };

  return (
    <Stack>
      <PageHeader
        title="Templates"
        description="Message templates per channel. Click a row to edit it."
        total={data?.total}
        actions={
          <Button leftSection={<IconPlus size={16} aria-hidden />} onClick={() => navigate('/templates/new')}>
            New template
          </Button>
        }
      />
      <FilterPanel activeCount={activeCount} onReset={reset}>
        <FilterSpan2>
          <TextInput
            label="Search"
            placeholder="Code or name"
            leftSection={<IconSearch size={16} aria-hidden />}
            value={q}
            onChange={(e) => {
              setQ(e.currentTarget.value);
              setPage(1);
            }}
          />
        </FilterSpan2>
        <Select
          label="Channel"
          placeholder="All channels"
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
          placeholder="All statuses"
          data={['ACTIVE', 'INACTIVE']}
          value={status}
          onChange={(v) => {
            setStatus(v);
            setPage(1);
          }}
          clearable
        />
      </FilterPanel>
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
