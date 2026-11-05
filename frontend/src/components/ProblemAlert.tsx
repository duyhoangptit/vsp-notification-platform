import { Alert, Button, Group, List, Text } from '@mantine/core';
import { ApiError } from '../api/client';

interface Props {
  error: unknown;
  onReload?: () => void;
}

/** Renders an RFC 9457 problem: code, detail and field errors; VERSION_CONFLICT offers a reload. */
export function ProblemAlert({ error, onReload }: Props) {
  if (!error) return null;
  if (error instanceof ApiError) {
    const { problem } = error;
    if (problem.code === 'VERSION_CONFLICT') {
      return (
        <Alert color="orange" title="Modified by someone else" role="alert">
          <Text size="sm">This record was changed by another operator after you opened it.</Text>
          {onReload && (
            <Group mt="sm">
              <Button size="xs" variant="light" onClick={onReload}>
                Reload latest version
              </Button>
            </Group>
          )}
        </Alert>
      );
    }
    return (
      <Alert color="red" title={problem.title ?? 'Request failed'} role="alert">
        {problem.code && (
          <Text size="xs" c="dimmed" ff="monospace">
            {problem.code}
          </Text>
        )}
        {problem.detail && <Text size="sm">{problem.detail}</Text>}
        {problem.errors && problem.errors.length > 0 && (
          <List size="sm" mt="xs">
            {problem.errors.map((e, i) => (
              <List.Item key={i}>
                <b>{e.field}</b>: {e.message}
              </List.Item>
            ))}
          </List>
        )}
        {problem.missing_variables && problem.missing_variables.length > 0 && (
          <Text size="sm">Missing variables: {problem.missing_variables.join(', ')}</Text>
        )}
      </Alert>
    );
  }
  return (
    <Alert color="red" title="Unexpected error" role="alert">
      {error instanceof Error ? error.message : String(error)}
    </Alert>
  );
}
