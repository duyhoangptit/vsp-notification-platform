import { CodeHighlight } from '@mantine/code-highlight';
import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  CopyButton,
  Divider,
  Drawer,
  Group,
  Popover,
  Skeleton,
  Stack,
  Tabs,
  Text,
  Timeline,
  Title,
  Tooltip,
  useMantineTheme,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconAlertTriangle, IconCheck, IconCopy, IconRefresh } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useMemo, useState, type ReactNode } from 'react';
import { api, unwrap, type Schemas } from '../../api/client';
import { ProblemAlert } from '../../components/ProblemAlert';
import { formatDateTime } from '../../utils/format';
import { STATUS_COLORS } from './NotificationSearchPage';

type NotificationDetail = Schemas['NotificationDetail'];
type TimelineEvent = NonNullable<NotificationDetail['timeline']>[number];

export interface DeliveryLogDrawerProps {
  /** Notification to inspect; `null` closes the drawer. Keep it in the URL (`?inspect=`) so links and the command palette can open it. */
  notificationId: string | null;
  onClose: () => void;
  /**
   * Re-sends the notification server-side by id. Omit it to hide the action
   * (e.g. for SUPPORT_VIEWER, or until the backend exposes a replay endpoint).
   */
  onReplay?: (notificationId: string) => Promise<void>;
}

type StepTone = 'neutral' | 'progress' | 'success' | 'failure';

interface JourneyStep {
  key: string;
  at: string;
  title: string;
  tone: StepTone;
  /** Gap since the previous step; null for the first one. */
  deltaMs: number | null;
  providerKey: string | null;
  error: string | null;
}

interface ProviderAttempt {
  key: string;
  label: string;
  at: string;
  failed: boolean;
  json: string;
}

const STAGE_TITLES: Record<string, string> = {
  RECEIVED: 'API received',
  PROCESSED: 'Rendered and queued',
  SENDING: 'Handed to provider',
  SENT: 'Provider accepted',
  DELIVERED: 'Delivered',
  FAILED: 'Failed',
};

const TONE_COLORS: Record<StepTone, string> = {
  neutral: 'gray',
  progress: 'dispatch',
  success: 'green',
  failure: 'red',
};

const REPLAYABLE_STATUSES: ReadonlySet<string> = new Set(['FAILED']);

function describe(e: TimelineEvent): Pick<JourneyStep, 'title' | 'tone'> {
  if (e.event_type === 'ATTEMPT_FAILED') {
    return { title: `Attempt ${e.attempt ?? '?'} failed, retry scheduled`, tone: 'failure' };
  }
  if (e.event_type === 'CALLBACK_RECEIVED') {
    return {
      title: `Provider reported ${(e.to_status ?? 'an update').toLowerCase()}`,
      tone: e.to_status === 'FAILED' ? 'failure' : 'success',
    };
  }
  const status = e.to_status ?? '';
  const tone: StepTone =
    status === 'FAILED'
      ? 'failure'
      : status === 'DELIVERED'
        ? 'success'
        : status === 'RECEIVED'
          ? 'neutral'
          : 'progress';
  return {
    title: STAGE_TITLES[status] ?? (e.event_type ?? 'Event').replaceAll('_', ' ').toLowerCase(),
    tone,
  };
}

function toJourney(events: readonly TimelineEvent[]): JourneyStep[] {
  const sorted = [...events]
    .filter((e): e is TimelineEvent & { at: string } => Boolean(e.at))
    .sort((a, b) => a.at.localeCompare(b.at));
  return sorted.map((e, i) => ({
    key: `${e.at}-${i}`,
    at: e.at,
    ...describe(e),
    deltaMs: i === 0 ? null : dayjs(e.at).diff(dayjs(sorted[i - 1].at)),
    providerKey: e.provider_key ?? null,
    error: e.error_code ? `${e.error_code}${e.error_message ? `: ${e.error_message}` : ''}` : null,
  }));
}

function toAttempts(events: readonly TimelineEvent[]): ProviderAttempt[] {
  return events
    .filter((e) => e.provider_response != null)
    .map((e, i) => ({
      key: `attempt-${i}`,
      label: e.attempt
        ? `Attempt ${e.attempt}`
        : e.event_type === 'CALLBACK_RECEIVED'
          ? 'Callback'
          : `Response ${i + 1}`,
      at: e.at ?? '',
      failed: e.event_type === 'ATTEMPT_FAILED' || e.to_status === 'FAILED',
      json: JSON.stringify(e.provider_response, null, 2),
    }));
}

/** What the platform stored for this send. Content arrives with OTPs already masked by the API. */
function toRequestJson(n: NotificationDetail): string {
  const request = {
    notification_id: n.notification_id,
    client_id: n.client_id,
    client_request_id: n.client_request_id,
    user_id: n.user_id,
    channel_type: n.channel_type,
    category: n.category,
    recipient_target: n.recipient_target,
    content_mode: n.content_mode,
    template: n.template_code ? { code: n.template_code, version: n.template_version } : null,
    subject: n.subject ?? undefined,
    title: n.title ?? undefined,
    content: n.content,
    expires_at: n.expires_at,
    opt_out_overridden: n.opt_out_overridden,
  };
  return JSON.stringify(request, null, 2);
}

function formatLatency(ms: number): string {
  if (ms < 1_000) return `+${ms} ms`;
  if (ms < 60_000) return `+${(ms / 1_000).toFixed(ms < 10_000 ? 2 : 1)} s`;
  return `+${Math.round(ms / 60_000)} min`;
}

/** Log-scaled so a 200 ms hop and a 40 s provider stall are both visible on one rail. */
function latencyWidth(ms: number): string {
  const pct = (Math.log10(ms + 1) / Math.log10(120_001)) * 100;
  return `${Math.min(100, Math.max(4, pct)).toFixed(1)}%`;
}

function LatencyGap({ ms }: { ms: number }) {
  const { other } = useMantineTheme();
  const { slowMs, criticalMs } = other.latency as { slowMs: number; criticalMs: number };
  const level = ms >= criticalMs ? 'critical' : ms >= slowMs ? 'slow' : 'ok';
  return (
    <Group gap="xs" wrap="nowrap" mt={4} aria-label={`${formatLatency(ms)} since previous step`}>
      <Box w={120} h={4} style={{ borderRadius: 2, background: 'var(--nh-surface-sunken)' }}>
        <Box
          h="100%"
          w={latencyWidth(ms)}
          style={{ borderRadius: 2, background: `var(--nh-latency-${level})` }}
        />
      </Box>
      <Text ff="monospace" fz="xs" c={level === 'ok' ? 'dimmed' : undefined} fw={level === 'ok' ? 400 : 600}>
        {formatLatency(ms)}
      </Text>
    </Group>
  );
}

function CopyId({ value }: { value: string }) {
  return (
    <Group gap={4} wrap="nowrap">
      <Text ff="monospace" fz="var(--nh-mono-id-size)" style={{ wordBreak: 'break-all' }}>
        {value}
      </Text>
      <CopyButton value={value} timeout={1500}>
        {({ copied, copy }) => (
          <Tooltip label={copied ? 'Copied' : 'Copy ID'}>
            <ActionIcon
              variant="subtle"
              color={copied ? 'green' : 'gray'}
              size="sm"
              onClick={copy}
              aria-label="Copy notification ID"
            >
              {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
            </ActionIcon>
          </Tooltip>
        )}
      </CopyButton>
    </Group>
  );
}

function Meta({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack gap={0} miw={0}>
      <Text fz="xs" c="dimmed">
        {label}
      </Text>
      <Text fz="sm" component="div" truncate>
        {children}
      </Text>
    </Stack>
  );
}

function DeliveryJourney({ steps, active }: { steps: JourneyStep[]; active: number }) {
  if (steps.length === 0)
    return (
      <Text c="dimmed" fz="sm">
        No delivery events recorded yet.
      </Text>
    );
  return (
    <Timeline active={active} bulletSize={14} lineWidth={2}>
      {steps.map((s) => (
        <Timeline.Item
          key={s.key}
          color={TONE_COLORS[s.tone]}
          title={
            <Group gap="xs" justify="space-between" wrap="nowrap">
              <Text fz="sm" fw={600} c={s.tone === 'failure' ? 'red' : undefined}>
                {s.title}
              </Text>
              <Text ff="monospace" fz="xs" c="dimmed">
                {dayjs(s.at).format('HH:mm:ss.SSS')}
              </Text>
            </Group>
          }
        >
          {s.deltaMs !== null && <LatencyGap ms={s.deltaMs} />}
          {s.providerKey && (
            <Text fz="xs" c="dimmed" mt={2}>
              via {s.providerKey}
            </Text>
          )}
          {s.error && (
            <Text fz="xs" c="red" ff="monospace" mt={2}>
              {s.error}
            </Text>
          )}
        </Timeline.Item>
      ))}
    </Timeline>
  );
}

function ReplayButton({
  notification,
  onReplay,
}: {
  notification: NotificationDetail;
  onReplay: (id: string) => Promise<void>;
}) {
  const [confirming, setConfirming] = useState(false);
  const queryClient = useQueryClient();
  const id = notification.notification_id ?? '';
  const expired = notification.expires_at ? dayjs(notification.expires_at).isBefore(dayjs()) : false;
  const replayable = REPLAYABLE_STATUSES.has(notification.status ?? '');
  const disabledReason = !replayable
    ? 'Only failed notifications can be replayed.'
    : expired
      ? `Expired at ${formatDateTime(notification.expires_at)}. Send a new request instead.`
      : null;

  const replay = useMutation({
    mutationFn: () => onReplay(id),
    onSuccess: async () => {
      setConfirming(false);
      notifications.show({
        color: 'green',
        title: 'Replay queued',
        message: `Notification ${id.slice(0, 8)} is being sent again.`,
      });
      await queryClient.invalidateQueries({ queryKey: ['notification', id] });
      await queryClient.invalidateQueries({ queryKey: ['notifications'] });
    },
    onError: (err: unknown) => {
      notifications.show({
        color: 'red',
        title: 'Replay not queued',
        message:
          err instanceof Error
            ? err.message
            : 'The server rejected the replay. Check the trace ID in the logs.',
      });
    },
  });

  return (
    <Popover opened={confirming} onChange={setConfirming} position="bottom-end" withArrow trapFocus>
      <Popover.Target>
        <Tooltip label={disabledReason} disabled={!disabledReason}>
          {/* span keeps the tooltip working on a disabled button */}
          <span>
            <Button
              size="xs"
              leftSection={<IconRefresh size={14} />}
              disabled={Boolean(disabledReason)}
              onClick={() => setConfirming((o) => !o)}
            >
              Replay notification
            </Button>
          </span>
        </Tooltip>
      </Popover.Target>
      <Popover.Dropdown maw={300}>
        <Stack gap="xs">
          <Text fz="sm">
            This sends the message to the recipient again through{' '}
            {notification.channel_type ?? 'the same channel'}. The recipient may get it twice.
          </Text>
          <Group justify="flex-end" gap="xs">
            <Button size="xs" variant="default" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
            <Button size="xs" color="red" loading={replay.isPending} onClick={() => replay.mutate()}>
              Replay now
            </Button>
          </Group>
        </Stack>
      </Popover.Dropdown>
    </Popover>
  );
}

function DrawerSkeleton() {
  return (
    <Stack gap="sm" aria-busy="true">
      <Skeleton h={22} w="60%" />
      <Skeleton h={14} w="90%" />
      <Skeleton h={160} mt="md" />
      <Skeleton h={220} />
    </Stack>
  );
}

function DrawerBody({
  n,
  onReplay,
}: {
  n: NotificationDetail;
  onReplay?: DeliveryLogDrawerProps['onReplay'];
}) {
  const events = useMemo(() => n.timeline ?? [], [n.timeline]);
  const steps = useMemo(() => toJourney(events), [events]);
  const attempts = useMemo(() => toAttempts(events), [events]);
  const requestJson = useMemo(() => toRequestJson(n), [n]);
  const lastFailed = [...attempts].reverse().find((a) => a.failed);
  const [tab, setTab] = useState<string | null>(lastFailed ? lastFailed.key : 'request');

  const total = steps.length > 1 ? dayjs(steps[steps.length - 1].at).diff(dayjs(steps[0].at)) : null;
  const failed = n.status === 'FAILED';

  return (
    <Stack gap="lg">
      <Stack gap="xs">
        <Group justify="space-between" align="flex-start" wrap="nowrap">
          <Group gap="xs">
            <Badge size="lg" color={STATUS_COLORS[n.status ?? ''] ?? 'gray'}>
              {n.status ?? 'Unknown'}
            </Badge>
            <Badge size="lg" variant="outline" color="gray">
              {n.channel_type}
            </Badge>
          </Group>
          {onReplay && <ReplayButton notification={n} onReplay={onReplay} />}
        </Group>
        {n.notification_id && <CopyId value={n.notification_id} />}
        <Group grow gap="md" align="flex-start" mt={4}>
          <Meta label="Recipient">{n.recipient_target || '—'}</Meta>
          <Meta label="Template">
            {n.template_code ? `${n.template_code} v${n.template_version}` : 'Direct content'}
          </Meta>
          <Meta label="Created">{formatDateTime(n.created_at)}</Meta>
        </Group>
      </Stack>

      {failed && (
        <Alert
          color="red"
          variant="light"
          icon={<IconAlertTriangle size={18} />}
          title={n.error_code ?? 'Delivery failed'}
        >
          <Text fz="sm">
            {n.error_message ?? 'The provider did not return a reason. Check the provider response below.'}
          </Text>
          {(n.retry_count ?? 0) > 0 && (
            <Text fz="xs" c="dimmed" mt={4}>
              Failed after {n.retry_count} {n.retry_count === 1 ? 'retry' : 'retries'}.
            </Text>
          )}
        </Alert>
      )}

      <section aria-labelledby="journey-heading">
        <Group justify="space-between" mb="sm">
          <Title order={4} id="journey-heading">
            Delivery journey
          </Title>
          {total !== null && (
            <Text fz="xs" c="dimmed">
              End to end{' '}
              <Text span ff="monospace" fz="xs" fw={600} c="var(--mantine-color-text)">
                {formatLatency(total).slice(1)}
              </Text>
            </Text>
          )}
        </Group>
        <DeliveryJourney steps={steps} active={steps.length - 1} />
      </section>

      <Divider />

      <section aria-labelledby="payload-heading">
        <Title order={4} id="payload-heading" mb="sm">
          Payload and provider response
        </Title>
        <Tabs value={tab} onChange={setTab} variant="outline" keepMounted={false}>
          <Tabs.List>
            <Tabs.Tab value="request">Request</Tabs.Tab>
            {attempts.map((a) => (
              <Tabs.Tab
                key={a.key}
                value={a.key}
                color={a.failed ? 'red' : undefined}
                leftSection={a.failed ? <IconAlertTriangle size={12} /> : undefined}
              >
                {a.label}
              </Tabs.Tab>
            ))}
          </Tabs.List>
          <Tabs.Panel value="request" pt="sm">
            <CodeHighlight
              code={requestJson}
              language="json"
              copyLabel="Copy request"
              copiedLabel="Copied"
              withExpandButton
              maxCollapsedHeight={320}
              defaultExpanded={false}
              expandCodeLabel="Show full request"
              collapseCodeLabel="Collapse"
              radius="sm"
            />
          </Tabs.Panel>
          {attempts.map((a) => (
            <Tabs.Panel key={a.key} value={a.key} pt="sm">
              <Text fz="xs" c="dimmed" mb={6}>
                Received {formatDateTime(a.at)}
              </Text>
              <CodeHighlight
                code={a.json}
                language="json"
                copyLabel="Copy response"
                copiedLabel="Copied"
                withExpandButton
                maxCollapsedHeight={320}
                expandCodeLabel="Show full response"
                collapseCodeLabel="Collapse"
                radius="sm"
              />
            </Tabs.Panel>
          ))}
        </Tabs>
        {attempts.length === 0 && (
          <Text fz="xs" c="dimmed" mt="xs">
            No provider response yet. It appears here once the provider answers or sends a callback.
          </Text>
        )}
      </section>
    </Stack>
  );
}

/** Right-side inspector for one notification: journey with per-step latency, payloads, and replay. */
export function DeliveryLogDrawer({ notificationId, onClose, onReplay }: DeliveryLogDrawerProps) {
  const opened = notificationId !== null;
  const { data, error, isLoading, refetch } = useQuery({
    queryKey: ['notification', notificationId],
    queryFn: () =>
      unwrap(api.GET('/console/api/notifications/{id}', { params: { path: { id: notificationId! } } })),
    enabled: opened,
    // Non-terminal notifications are still moving; keep the journey live while the drawer is open.
    refetchInterval: (q) => {
      const s = q.state.data?.status;
      return s && s !== 'DELIVERED' && s !== 'FAILED' ? 5_000 : false;
    },
  });

  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      size={640}
      title={<Text fw={600}>Delivery inspector</Text>}
      closeButtonProps={{ 'aria-label': 'Close inspector' }}
    >
      {isLoading && <DrawerSkeleton />}
      {error && <ProblemAlert error={error} onReload={() => void refetch()} />}
      {data && <DrawerBody key={data.notification_id} n={data} onReplay={onReplay} />}
    </Drawer>
  );
}
