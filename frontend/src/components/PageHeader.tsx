import { Group, Text, Title } from '@mantine/core';
import type { ReactNode } from 'react';
import { formatNumber } from '../utils/format';

interface PageHeaderProps {
  title: ReactNode;
  description?: ReactNode;
  /** Total matching records; shown as "N results" next to the actions. */
  total?: number;
  /** Primary page actions (e.g. "New template"). */
  actions?: ReactNode;
}

/** Consistent page title row: title + description on the left, result count and actions on the right. */
export function PageHeader({ title, description, total, actions }: PageHeaderProps) {
  return (
    <Group justify="space-between" align="flex-end" gap="sm">
      <div>
        <Title order={3}>{title}</Title>
        {description && (
          <Text size="sm" c="dimmed">
            {description}
          </Text>
        )}
      </div>
      <Group gap="md">
        {total !== undefined && (
          <Text size="sm" c="dimmed" aria-live="polite">
            <Text span fw={600} c="var(--mantine-color-text)">
              {formatNumber(total)}
            </Text>{' '}
            {total === 1 ? 'result' : 'results'}
          </Text>
        )}
        {actions}
      </Group>
    </Group>
  );
}
