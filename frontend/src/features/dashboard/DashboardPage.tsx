import { BarChart } from '@mantine/charts';
import { Card, Group, SegmentedControl, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { api, unwrap } from '../../api/client';
import { ProblemAlert } from '../../components/ProblemAlert';
import { formatNumber, formatPercent } from '../../utils/format';

const CHANNEL_COLORS: Record<string, string> = { EMAIL: 'indigo.6', SMS: 'teal.6', PUSH: 'orange.6' };

type Granularity = 'HOUR' | 'DAY';

/** Operations dashboard (T165, US9): volume per channel by hour/day and success/failure rates per channel. */
export function DashboardPage() {
  const [range, setRange] = useState<[string | null, string | null]>([
    dayjs().subtract(6, 'day').format('YYYY-MM-DD'),
    dayjs().format('YYYY-MM-DD'),
  ]);
  const [granularity, setGranularity] = useState<Granularity>('DAY');
  const from = range[0] ? dayjs(range[0]).startOf('day').toISOString() : null;
  const to = range[1] ? dayjs(range[1]).add(1, 'day').startOf('day').toISOString() : null;
  const enabled = !!from && !!to;

  const volume = useQuery({
    queryKey: ['dashboard-volume', from, to, granularity],
    enabled,
    queryFn: () => unwrap(api.GET('/console/api/dashboard/volume', { params: { query: { from: from!, to: to!, granularity } } })),
  });
  const rates = useQuery({
    queryKey: ['dashboard-rates', from, to],
    enabled,
    queryFn: () => unwrap(api.GET('/console/api/dashboard/channel-rates', { params: { query: { from: from!, to: to! } } })),
  });

  const channels = Array.from(
    new Set([
      ...(volume.data?.buckets ?? []).flatMap((b) => Object.keys(b.counts ?? {})),
      ...(rates.data ?? []).map((r) => r.channel_type ?? ''),
    ]),
  ).filter(Boolean);
  const chartData = (volume.data?.buckets ?? []).map((b) => ({
    bucket: dayjs(b.bucket_start).format(granularity === 'HOUR' ? 'MM-DD HH:00' : 'YYYY-MM-DD'),
    ...b.counts,
  }));

  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <Title order={3}>Dashboard</Title>
        <Group align="flex-end">
          <DatePickerInput type="range" label="Period" value={range} onChange={setRange} maxDate={new Date()} w={260} />
          <SegmentedControl
            aria-label="Granularity"
            value={granularity}
            onChange={(v) => setGranularity(v as Granularity)}
            data={[
              { label: 'Hour', value: 'HOUR' },
              { label: 'Day', value: 'DAY' },
            ]}
          />
        </Group>
      </Group>
      <ProblemAlert error={volume.error ?? rates.error} />
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        {(rates.data ?? []).map((r) => (
          <Card withBorder key={r.channel_type} data-testid={`rate-${r.channel_type}`}>
            <Text fw={700}>{r.channel_type}</Text>
            <Text size="sm" c="teal">
              Success {formatPercent(r.success_rate)} ({formatNumber(r.success_count)})
            </Text>
            <Text size="sm" c="red">
              Failure {formatPercent(r.failure_rate)} ({formatNumber(r.failure_count)})
            </Text>
            <Text size="xs" c="dimmed">
              In progress {formatNumber(r.in_progress_count)}
            </Text>
          </Card>
        ))}
      </SimpleGrid>
      <Card withBorder>
        <Text fw={600} mb="sm">
          Volume per {granularity === 'HOUR' ? 'hour' : 'day'}
        </Text>
        {chartData.length === 0 ? (
          <Text c="dimmed" size="sm">
            No notifications in this period
          </Text>
        ) : (
          <BarChart
            h={300}
            data={chartData}
            dataKey="bucket"
            type="stacked"
            withLegend
            series={channels.map((c) => ({ name: c, color: CHANNEL_COLORS[c] ?? 'gray.6' }))}
          />
        )}
      </Card>
    </Stack>
  );
}
