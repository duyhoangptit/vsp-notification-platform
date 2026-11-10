import { Badge, Button, Divider, Group, Paper, SimpleGrid, Stack, Text } from '@mantine/core';
import { IconRestore } from '@tabler/icons-react';
import type { FormEventHandler, ReactNode } from 'react';
import classes from './FilterPanel.module.css';

interface FilterPanelProps {
  /** Filter fields. They sit in a 4-column grid (2 on tablets, 1 on phones); wrap one in `<FilterSpan2>` to widen it. */
  children: ReactNode;
  /** Number of filters that differ from the defaults. */
  activeCount: number;
  onReset: () => void;
  /** Renders the panel as a form (for filters applied with a Search button). */
  onSubmit?: FormEventHandler<HTMLFormElement>;
  /** Left side of the footer, e.g. quick ranges. Defaults to a hint when filters apply live. */
  footerStart?: ReactNode;
  /** Extra footer actions after Reset, e.g. the Search submit button. */
  actions?: ReactNode;
}

/** Bordered filter panel shared by list pages: aligned field grid + footer with active count and Reset. */
export function FilterPanel({
  children,
  activeCount,
  onReset,
  onSubmit,
  footerStart,
  actions,
}: FilterPanelProps) {
  const formProps = onSubmit ? ({ component: 'form', onSubmit } as const) : {};
  return (
    <Paper withBorder radius="md" p="md" role="search" aria-label="Filters" {...formProps}>
      <Stack gap="sm">
        <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="sm" verticalSpacing="sm">
          {children}
        </SimpleGrid>
        <Divider />
        <Group justify="space-between" gap="sm">
          <Group gap={6}>
            {footerStart ?? (
              <Text size="xs" c="dimmed">
                Results update as you type
              </Text>
            )}
          </Group>
          <Group gap="xs">
            {activeCount > 0 && (
              <Badge variant="light" size="sm">
                {activeCount} active {activeCount === 1 ? 'filter' : 'filters'}
              </Badge>
            )}
            <Button
              variant="subtle"
              color="gray"
              leftSection={<IconRestore size={16} aria-hidden />}
              onClick={onReset}
              disabled={activeCount === 0 && !onSubmit}
            >
              Reset
            </Button>
            {actions}
          </Group>
        </Group>
      </Stack>
    </Paper>
  );
}

/** Makes a filter field span two grid columns on desktop (e.g. a free-text search). */
export function FilterSpan2({ children }: { children: ReactNode }) {
  return <div className={classes.span2}>{children}</div>;
}
