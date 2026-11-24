import { Alert, Card, Code, Stack, Text, Title } from '@mantine/core';
import { useClock } from '../../components/ClockOverrideBanner';
import { formatDateTime } from '../../utils/format';

/**
 * Read-only view of the system time (non-production). The override is managed centrally by vsp-catalog-service
 * (PUT/DELETE /api/v1/admin/system-clock) and followed by every VSP service.
 */
export function ClockPage() {
  const { data: clock } = useClock();

  if (clock === null) {
    return <Text>System time override is disabled in this environment.</Text>;
  }

  return (
    <Stack maw={560}>
      <Title order={3}>System time</Title>
      <Card withBorder>
        <Text size="sm">Mode: {clock?.mode ?? '—'}</Text>
        <Text size="sm">Application now: {formatDateTime(clock?.now)}</Text>
        <Text size="sm">Real now: {formatDateTime(clock?.real_now)}</Text>
        {clock?.changed_by && (
          <Text size="sm">
            Changed by {clock.changed_by} at {formatDateTime(clock.changed_at)}
          </Text>
        )}
      </Card>
      <Alert color="blue" variant="light">
        The system time is managed centrally in <b>vsp-catalog-service</b> for all VSP services: <br />
        <Code>PUT /api/v1/admin/system-clock</Code> (FIXED / OFFSET) and <Code>DELETE /api/v1/admin/system-clock</Code>{' '}
        (back to real time).
      </Alert>
    </Stack>
  );
}
