import { Button, Card, Group, SegmentedControl, Stack, Text, TextInput, Title } from '@mantine/core';
import { DateTimePicker } from '@mantine/dates';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { api, unwrap } from '../../api/client';
import { CLOCK_KEY, useClock } from '../../components/ClockOverrideBanner';
import { ProblemAlert } from '../../components/ProblemAlert';
import { formatDateTime } from '../../utils/format';

/** ADMIN-only system time override (non-production; R29). */
export function ClockPage() {
  const queryClient = useQueryClient();
  const { data: clock } = useClock();
  const [mode, setMode] = useState<'FIXED' | 'OFFSET'>('FIXED');
  const [fixed, setFixed] = useState<string | null>(null);
  const [offset, setOffset] = useState('P1D');

  const apply = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT('/console/api/admin/clock', {
          body:
            mode === 'FIXED'
              ? { mode, fixed_instant: fixed ? dayjs(fixed).toISOString() : undefined }
              : { mode, offset },
        }),
      ),
    onSuccess: (data) => queryClient.setQueryData(CLOCK_KEY, data),
  });
  const reset = useMutation({
    mutationFn: () => unwrap(api.DELETE('/console/api/admin/clock')),
    onSuccess: (data) => queryClient.setQueryData(CLOCK_KEY, data),
  });

  if (clock === null) {
    return <Text>System time override is disabled in this environment.</Text>;
  }

  return (
    <Stack maw={520}>
      <Title order={3}>System time</Title>
      <Card withBorder>
        <Text size="sm">Mode: {clock?.mode ?? '—'}</Text>
        <Text size="sm">Application now: {formatDateTime(clock?.now)}</Text>
        <Text size="sm">Real now: {formatDateTime(clock?.real_now)}</Text>
      </Card>
      <SegmentedControl
        value={mode}
        onChange={(v) => setMode(v as 'FIXED' | 'OFFSET')}
        data={['FIXED', 'OFFSET']}
      />
      {mode === 'FIXED' ? (
        <DateTimePicker label="Fixed instant" value={fixed} onChange={setFixed} withSeconds />
      ) : (
        <TextInput
          label="Offset (ISO-8601 duration)"
          description="e.g. P30D, -PT2H"
          value={offset}
          onChange={(e) => setOffset(e.currentTarget.value)}
        />
      )}
      <ProblemAlert error={apply.error ?? reset.error} />
      <Group>
        <Button onClick={() => apply.mutate()} loading={apply.isPending}>
          Apply
        </Button>
        <Button variant="default" onClick={() => reset.mutate()} loading={reset.isPending}>
          Reset to system time
        </Button>
      </Group>
    </Stack>
  );
}
