import { Badge, Button, Group, Select, Stack, TextInput, Title } from '@mantine/core';
import { DateTimePicker } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { DataTable } from 'mantine-datatable';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { api, unwrap } from '../../api/client';
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

function fromParams(p: URLSearchParams): Filters {
  return {
    client_request_id: p.get('client_request_id') ?? '',
    user_id: p.get('user_id') ?? '',
    recipient_target: p.get('recipient_target') ?? '',
    channel_type: p.get('channel_type'),
    status: p.get('status'),
    client_id: p.get('client_id') ?? '',
    from: p.get('from') ?? dayjs().subtract(1, 'day').toISOString(),
    to: p.get('to'),
  };
}

/** Delivery Audit search (T143, US8): filters ↔ URL query, newest first, masked recipients. */
export function NotificationSearchPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [page, setPage] = useState(1);
  const applied = fromParams(params);
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

  return (
    <Stack>
      <Title order={3}>Delivery Audit</Title>
      <form onSubmit={form.onSubmit(apply)}>
        <Group align="flex-end">
          <TextInput label="Client request ID" {...form.getInputProps('client_request_id')} />
          <TextInput label="User ID" {...form.getInputProps('user_id')} />
          <TextInput label="Recipient" {...form.getInputProps('recipient_target')} />
          <Select label="Channel" data={['EMAIL', 'SMS', 'PUSH']} clearable {...form.getInputProps('channel_type')} />
          <Select
            label="Status"
            data={Object.keys(STATUS_COLORS)}
            clearable
            {...form.getInputProps('status')}
          />
          <TextInput label="Client" {...form.getInputProps('client_id')} />
          <DateTimePicker
            label="From"
            clearable
            value={form.values.from ? dayjs(form.values.from).toDate() : null}
            onChange={(v) => form.setFieldValue('from', v ? dayjs(v).toISOString() : null)}
          />
          <DateTimePicker
            label="To"
            clearable
            value={form.values.to ? dayjs(form.values.to).toDate() : null}
            onChange={(v) => form.setFieldValue('to', v ? dayjs(v).toISOString() : null)}
          />
          <Button type="submit">Search</Button>
        </Group>
      </form>
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
