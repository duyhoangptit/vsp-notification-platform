import { Alert, Button, Group, Modal, Stack, Text, TextInput, Title } from '@mantine/core';
import { useEffect, useState } from 'react';
import { ProblemAlert } from '../../components/ProblemAlert';
import { usePreview } from './api';

interface Props {
  opened: boolean;
  onClose: () => void;
  channelType: string;
  subject?: string;
  title?: string;
  body: string;
  variables: string[];
}

/** Renders the unsaved template with sample values (FR-022). */
export function TemplatePreviewModal({ opened, onClose, channelType, subject, title, body, variables }: Props) {
  const [values, setValues] = useState<Record<string, string>>({});
  const preview = usePreview();
  const { reset } = preview;
  useEffect(() => {
    if (opened) reset();
  }, [opened, reset]);

  const run = () =>
    preview.mutate({ channel_type: channelType, subject_template: subject, title_template: title, body_template: body, variables: values });

  return (
    <Modal opened={opened} onClose={onClose} title="Preview" size="lg">
      <Stack>
        {variables.map((v) => (
          <TextInput
            key={v}
            label={v}
            value={values[v] ?? ''}
            onChange={(e) => {
              const value = e.currentTarget.value;
              setValues((prev) => ({ ...prev, [v]: value }));
            }}
          />
        ))}
        <Group>
          <Button onClick={run} loading={preview.isPending}>
            Render preview
          </Button>
        </Group>
        <ProblemAlert error={preview.error} />
        {preview.data && (
          <Stack gap="xs" data-testid="preview-result">
            {preview.data.subject && <Title order={5}>{preview.data.subject}</Title>}
            {preview.data.title && <Title order={5}>{preview.data.title}</Title>}
            {channelType === 'EMAIL' ? (
              <iframe
                title="Email preview"
                sandbox=""
                srcDoc={preview.data.body ?? ''}
                style={{ width: '100%', minHeight: 240, border: '1px solid var(--mantine-color-gray-3)' }}
              />
            ) : (
              <Text style={{ whiteSpace: 'pre-wrap' }}>{preview.data.body}</Text>
            )}
            {channelType === 'SMS' && (
              <Text size="sm" c="dimmed">
                {preview.data.character_count} characters · {preview.data.sms_segments} SMS segment(s)
              </Text>
            )}
            {(preview.data.missing_variables?.length ?? 0) > 0 && (
              <Alert color="yellow">Missing values: {preview.data.missing_variables?.join(', ')}</Alert>
            )}
          </Stack>
        )}
      </Stack>
    </Modal>
  );
}
