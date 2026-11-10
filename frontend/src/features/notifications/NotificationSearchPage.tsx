import { Badge, Button, Select, Stack, Text, TextInput } from '@mantine/core';
import { DateTimePicker } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { IconApps, IconAt, IconHash, IconSearch, IconUser } from '@tabler/icons-react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { DataTable } from 'mantine-datatable';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { api, unwrap } from '../../api/client';
import { FilterPanel } from '../../components/FilterPanel';
import { PageHeader } from '../../components/PageHeader';
import { ProblemAlert } from '../../components/ProblemAlert';
import { formatDateTime } from '../../utils/format';

const PAGE_SIZE = 20;
export const STATUS_COLORS: Record<string, string> = {
  RECEIVED: 'gray',
  PROCESSED: 'blue',
  SENDING: 'cyan',
  SENT: 'teal',
  DELIVERED: 'green',
  FAILED: 'red',
};

interface Filters {
  client_request_id: string;
  user_id: string;
  recipient_target: string;
  channel_type: string | null;
  status: string | null;
  client_id: string;
  from: string | null;
  to: string | null;
}

function fromParams(p: URLSearchParams, defaultFrom: string): Filters {
  return {
    client_request_id: p.get('client_request_id') ?? '',
    user_id: p.get('user_id') ?? '',
    recipient_target: p.get('recipient_target') ?? '',
    channel_type: p.get('channel_type'),
    status: p.get('status'),
    client_id: p.get('client_id') ?? '',
    from: p.get('from') ?? defaultFrom,
    to: p.get('to'),
  };
}

const DATE_TIME_FORMAT = 'YYYY-MM-DD HH:mm';

const EMPTY_FILTERS: Filters = {
  client_request_id: '',
  user_id: '',
  recipient_target: '',
  channel_type: null,
  status: null,
  client_id: '',
  from: null,
  to: null,
};

interface QuickRange {
  label: string;
  amount: number;
  unit: 'hour' | 'day';
}

const QUICK_RANGES: QuickRange[] = [
  { label: '1h', amount: 1, unit: 'hour' },
  { label: '24h', amount: 1, unit: 'day' },
  { label: '7d', amount: 7, unit: 'day' },
  { label: '30d', amount: 30, unit: 'day' },
];

/** Delivery Audit search (T143, US8): filters ↔ URL query, newest first, masked recipients. */
export function NotificationSearchPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [page, setPage] = useState(1);
  // Computed once per mount: a fresh `now - 1 day` on every render would change the queryKey and refetch forever.
  const [defaultFrom] = useState(() => dayjs().subtract(1, 'day').toISOString());
  const applied = fromParams(params, defaultFrom);
  const form = useForm<Filters>({ initialValues: applied });

  const query = {
    client_request_id: applied.client_request_id || undefined,
    user_id: applied.user_id || undefined,
    recipient_target: applied.recipient_target || undefined,
    channel_type: applied.channel_type ?? undefined,
    status: applied.status ?? undefined,
    client_id: applied.client_id || undefined,
    from: applied.client_request_id ? undefined : (applied.from ?? undefined),
    to: applied.to ?? undefined,
    page: page - 1,
    size: PAGE_SIZE,
  };
  const { data, isFetching, error } = useQuery({
    queryKey: ['notifications', query],
    queryFn: () => unwrap(api.GET('/console/api/notifications', { params: { query } })),
    placeholderData: keepPreviousData,
  });

  const apply = (values: Filters) => {
    const next = new URLSearchParams();
    Object.entries(values).forEach(([k, v]) => {
      if (v) next.set(k, String(v));
    });
    setPage(1);
    setParams(next);
  };

  const resetValues: Filters = { ...EMPTY_FILTERS, from: defaultFrom };
  const activeCount = (Object.keys(EMPTY_FILTERS) as (keyof Filters)[]).filter(
    (k) => k !== 'from' && !!applied[k],
  ).length;
  const applyRange = (range: QuickRange) => {
    const values = {
      ...form.values,
      from: dayjs().subtract(range.amount, range.unit).toISOString(),
      to: null,
    };
    form.setValues(values);
    apply(values);
  };
  const reset = () => {
    form.setValues(resetValues);
    apply(resetValues);
  };
  const dateProps = { placeholder: 'Any time', valueFormat: DATE_TIME_FORMAT, clearable: true } as const;

  return (
    <Stack>
      <PageHeader
        title="Delivery Audit"
        description="Trace every notification by request, recipient or status. Recipients are masked."
        total={data?.total}
      />
      <FilterPanel
        activeCount={activeCount}
        onReset={reset}
        onSubmit={form.onSubmit(apply)}
        footerStart={
          <>
            <Text size="xs" c="dimmed" mr={4}>
              Quick range
            </Text>
            {QUICK_RANGES.map((r) => (
              <Button key={r.label} size="compact-xs" variant="default" onClick={() => applyRange(r)}>
                {r.label}
              </Button>
            ))}
          </>
        }
        actions={
          <Button type="submit" leftSection={<IconSearch size={16} aria-hidden />} loading={isFetching}>
            Search
          </Button>
        }
      >
        <TextInput
          label="Client request ID"
          placeholder="req-…"
          leftSection={<IconHash size={16} aria-hidden />}
          {...form.getInputProps('client_request_id')}
        />
        <TextInput
          label="Recipient"
          placeholder="Email, phone or device token"
          leftSection={<IconAt size={16} aria-hidden />}
          {...form.getInputProps('recipient_target')}
        />
        <TextInput
          label="User ID"
          leftSection={<IconUser size={16} aria-hidden />}
          {...form.getInputProps('user_id')}
        />
        <TextInput
          label="Client"
          placeholder="e.g. auth-service"
          leftSection={<IconApps size={16} aria-hidden />}
          {...form.getInputProps('client_id')}
        />
        <Select
          label="Channel"
          placeholder="All channels"
          data={['EMAIL', 'SMS', 'PUSH']}
          clearable
          {...form.getInputProps('channel_type')}
        />
        <Select
          label="Status"
          placeholder="All statuses"
          data={Object.keys(STATUS_COLORS)}
          clearable
          {...form.getInputProps('status')}
        />
        <DateTimePicker
          label="From"
          {...dateProps}
          description={form.values.client_request_id ? 'Ignored when searching by request ID' : undefined}
          inputWrapperOrder={['label', 'input', 'description', 'error']}
          value={form.values.from ? dayjs(form.values.from).toDate() : null}
          onChange={(v) => form.setFieldValue('from', v ? dayjs(v).toISOString() : null)}
        />
        <DateTimePicker
          label="To"
          {...dateProps}
          value={form.values.to ? dayjs(form.values.to).toDate() : null}
          onChange={(v) => form.setFieldValue('to', v ? dayjs(v).toISOString() : null)}
        />
      </FilterPanel>
      <ProblemAlert error={error} />
      <DataTable
        withTableBorder
        highlightOnHover
        minHeight={200}
        fetching={isFetching}
        records={data?.items ?? []}
        idAccessor="notification_id"
        totalRecords={data?.total ?? 0}
        recordsPerPage={PAGE_SIZE}
        page={page}
        onPageChange={setPage}
        noRecordsText="No notifications match these filters"
        onRowClick={({ record }) => navigate(`/notifications/${record.notification_id}`)}
        columns={[
          { accessor: 'created_at', title: 'Created', render: (r) => formatDateTime(r.created_at) },
          { accessor: 'client_request_id', title: 'Request ID' },
          { accessor: 'client_id', title: 'Client' },
          { accessor: 'user_id', title: 'User' },
          { accessor: 'recipient_target_masked', title: 'Recipient' },
          { accessor: 'channel_type', title: 'Channel' },
          { accessor: 'category', title: 'Category' },
          {
            accessor: 'status',
            render: (r) => <Badge color={STATUS_COLORS[r.status ?? ''] ?? 'gray'}>{r.status}</Badge>,
          },
          { accessor: 'error_code', title: 'Error' },
        ]}
      />
    </Stack>
  );
}
