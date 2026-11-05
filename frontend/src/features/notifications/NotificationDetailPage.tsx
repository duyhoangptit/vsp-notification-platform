import { Anchor, Badge, Card, Code, Collapse, Grid, Group, Loader, Stack, Text, Timeline, Title, UnstyledButton } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { api, unwrap, type Schemas } from '../../api/client';
import { ProblemAlert } from '../../components/ProblemAlert';
import { formatDateTime } from '../../utils/format';
import { STATUS_COLORS } from './NotificationSearchPage';

type TimelineEntry = NonNullable<Schemas['NotificationDetail']['timeline']>[number];

function Field({ label, value }: { label: string; value?: string | number | boolean | null }) {
  return (
    <Grid.Col span={{ base: 12, sm: 6, md: 4 }}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm">{value === undefined || value === null || value === '' ? '—' : String(value)}</Text>
    </Grid.Col>
  );
}

function ProviderResponse({ value }: { value: Record<string, unknown> }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <UnstyledButton onClick={() => setOpen((o) => !o)}>
        <Text size="xs" c="indigo">
          {open ? 'Hide' : 'Show'} provider response
        </Text>
      </UnstyledButton>
      <Collapse in={open}>
        <Code block>{JSON.stringify(value, null, 2)}</Code>
      </Collapse>
    </>
  );
}

function label(e: TimelineEntry): string {
  if (e.event_type === 'STATUS_CHANGED') return `${e.from_status ?? '∅'} → ${e.to_status}`;
  if (e.event_type === 'ATTEMPT_FAILED') return `Attempt ${e.attempt} failed`;
  if (e.event_type === 'CALLBACK_RECEIVED' && e.to_status) return `Provider report: ${e.from_status} → ${e.to_status}`;
  return (e.event_type ?? '').replaceAll('_', ' ').toLowerCase();
}

/** Notification detail with content (OTP masked) and full status timeline (T144, FR-014). */
export function NotificationDetailPage() {
  const { id } = useParams();
  const { data: n, error, isLoading } = useQuery({
    queryKey: ['notification', id],
    queryFn: () => unwrap(api.GET('/console/api/notifications/{id}', { params: { path: { id: id! } } })),
  });

  if (isLoading) return <Loader />;
  if (error || !n) return <ProblemAlert error={error} />;

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>Notification {n.notification_id}</Title>
        <Badge size="lg" color={STATUS_COLORS[n.status ?? ''] ?? 'gray'}>
          {n.status}
        </Badge>
      </Group>
      <Anchor component={Link} to="/notifications" size="sm">
        ← Back to search
      </Anchor>
      <Card withBorder>
        <Grid>
          <Field label="Client" value={n.client_id} />
          <Field label="Client request ID" value={n.client_request_id} />
          <Field label="User" value={n.user_id} />
          <Field label="Recipient" value={n.recipient_target} />
          <Field label="Channel" value={n.channel_type} />
          <Field label="Category" value={n.category} />
          <Field label="Mode" value={n.content_mode} />
          <Field label="Template" value={n.template_code ? `${n.template_code} (v${n.template_version})` : null} />
          <Field label="Expires at" value={n.expires_at ? formatDateTime(n.expires_at) : null} />
          <Field label="Retries" value={n.retry_count} />
          <Field label="Opt-out overridden" value={n.opt_out_overridden ? 'yes' : 'no'} />
          <Field label="Error" value={n.error_code ? `${n.error_code}${n.error_message ? ': ' + n.error_message : ''}` : null} />
        </Grid>
      </Card>
      <Card withBorder>
        <Stack gap="xs">
          {n.subject && <Text fw={600}>Subject: {n.subject}</Text>}
          {n.title && <Text fw={600}>Title: {n.title}</Text>}
          <Text size="sm" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }} data-testid="content">
            {n.content}
          </Text>
        </Stack>
      </Card>
      <Title order={4}>Timeline</Title>
      <Timeline bulletSize={18} lineWidth={2} active={(n.timeline?.length ?? 1) - 1}>
        {(n.timeline ?? []).map((e, i) => (
          <Timeline.Item
            key={i}
            color={e.event_type === 'ATTEMPT_FAILED' || e.to_status === 'FAILED' ? 'red' : undefined}
            title={label(e)}
          >
            <Text size="xs" c="dimmed">
              {formatDateTime(e.at)}
              {e.provider_key ? ` · ${e.provider_key}` : ''}
            </Text>
            {e.error_code && (
              <Text size="sm" c="red">
                {e.error_code}
                {e.error_message ? `: ${e.error_message}` : ''}
              </Text>
            )}
            {e.provider_response && <ProviderResponse value={e.provider_response as Record<string, unknown>} />}
          </Timeline.Item>
        ))}
      </Timeline>
    </Stack>
  );
}
