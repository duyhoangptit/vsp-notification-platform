import {
  Badge,
  Button,
  Checkbox,
  Group,
  JsonInput,
  Select,
  Stack,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDisclosure } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { Link, RichTextEditor } from '@mantine/tiptap';
import { useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { useEffect, useRef } from 'react';
import { useNavigate, useParams } from 'react-router';
import { ApiError } from '../../api/client';
import { ProblemAlert } from '../../components/ProblemAlert';
import { InsertVariableMenu } from './InsertVariableMenu';
import { PlaceholderHighlight } from './PlaceholderHighlight';
import { scanPlaceholders, smsInfo } from './placeholders';
import { TemplatePreviewModal } from './TemplatePreviewModal';
import { useSaveTemplate, useSetTemplateActive, useTemplate, type TemplateInput } from './api';

interface FormValues {
  template_code: string;
  template_name: string;
  channel_type: string;
  category: 'STANDARD' | 'OTP';
  is_critical: boolean;
  otp_variable: string;
  expiry_variable: string;
  subject_template: string;
  title_template: string;
  body_template: string;
  external_template_ref: string;
}

const EMPTY: FormValues = {
  template_code: '',
  template_name: '',
  channel_type: 'EMAIL',
  category: 'STANDARD',
  is_critical: false,
  otp_variable: 'otp_code',
  expiry_variable: '',
  subject_template: '',
  title_template: '',
  body_template: '',
  external_template_ref: '',
};

/** Template editor (T138): rich text for EMAIL, plain text with counters for SMS/PUSH; optimistic locking. */
export function TemplateEditorPage() {
  const { code } = useParams();
  const isNew = !code;
  const navigate = useNavigate();
  const { data: template, refetch } = useTemplate(code);
  const save = useSaveTemplate(code);
  const setActive = useSetTemplateActive(code ?? '');
  const [previewOpen, preview] = useDisclosure(false);
  const loadedVersion = useRef<number | undefined>(undefined);

  const form = useForm<FormValues>({
    initialValues: EMPTY,
    validate: {
      template_code: (v) => (/^[A-Z0-9_]{3,100}$/.test(v) ? null : 'Use A-Z, 0-9 and _ (3-100)'),
      template_name: (v) => (v.trim() ? null : 'Name is required'),
      subject_template: (v, values) => (values.channel_type === 'EMAIL' && !v.trim() ? 'Subject is required for EMAIL' : null),
      title_template: (v, values) => (values.channel_type === 'PUSH' && !v.trim() ? 'Title is required for PUSH' : null),
      body_template: (v) => (v.trim() && v !== '<p></p>' ? null : 'Body is required'),
      external_template_ref: (v) => {
        if (!v.trim()) return null;
        try {
          JSON.parse(v);
          return null;
        } catch {
          return 'Must be valid JSON';
        }
      },
    },
  });

  const editor = useEditor({
    extensions: [StarterKit.configure({ link: false }), Link, PlaceholderHighlight],
    content: '',
    onUpdate: ({ editor: e }) => form.setFieldValue('body_template', e.getHTML()),
    immediatelyRender: false,
  });

  useEffect(() => {
    if (template) {
      loadedVersion.current = template.version;
      const values: FormValues = {
        template_code: template.template_code ?? '',
        template_name: template.template_name ?? '',
        channel_type: template.channel_type ?? 'EMAIL',
        category: (template.category as 'STANDARD' | 'OTP') ?? 'STANDARD',
        is_critical: !!template.is_critical,
        otp_variable: template.otp_variable ?? 'otp_code',
        expiry_variable: template.expiry_variable ?? '',
        subject_template: template.subject_template ?? '',
        title_template: template.title_template ?? '',
        body_template: template.body_template ?? '',
        external_template_ref: template.external_template_ref ? JSON.stringify(template.external_template_ref, null, 2) : '',
      };
      form.setValues(values);
      form.resetDirty(values);
    }
    // only a new server copy (load, save, reload) resets the form: never the editor becoming ready, which would wipe
    // what the operator typed meanwhile
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [template]);

  useEffect(() => {
    if (editor && template && template.channel_type === 'EMAIL') {
      editor.commands.setContent(template.body_template ?? '', { emitUpdate: false });
    }
  }, [template, editor]);

  const v = form.values;
  const isEmail = v.channel_type === 'EMAIL';
  const scan = scanPlaceholders(v.subject_template, isEmail ? '' : v.title_template, v.body_template);
  const sms = smsInfo(v.body_template);

  const insert = (placeholder: string) => {
    if (isEmail && editor) {
      editor.chain().focus().insertContent(placeholder).run();
    } else {
      form.setFieldValue('body_template', v.body_template + placeholder);
    }
  };

  const submit = (values: FormValues) => {
    const input: TemplateInput & { version?: number } = {
      template_code: values.template_code,
      template_name: values.template_name,
      channel_type: values.channel_type,
      category: values.category,
      is_critical: values.is_critical,
      otp_variable: values.category === 'OTP' ? values.otp_variable : undefined,
      expiry_variable: values.category === 'OTP' && values.expiry_variable ? values.expiry_variable : null,
      subject_template: isEmail ? values.subject_template : undefined,
      title_template: values.channel_type === 'PUSH' ? values.title_template : undefined,
      body_template: values.body_template,
      external_template_ref: values.external_template_ref.trim() ? JSON.parse(values.external_template_ref) : null,
      version: loadedVersion.current,
    };
    save.mutate(input, {
      onSuccess: (saved) => {
        notifications.show({ color: 'green', message: `Template ${saved.template_code} saved` });
        if (isNew) navigate(`/templates/${saved.template_code}`, { replace: true });
      },
    });
  };

  const conflict = save.error instanceof ApiError && save.error.code === 'VERSION_CONFLICT';

  return (
    <form onSubmit={form.onSubmit(submit)}>
      <Stack maw={900}>
        <Group justify="space-between">
          <Title order={3}>{isNew ? 'New template' : `Template ${code}`}</Title>
          {template && <Badge color={template.status === 'ACTIVE' ? 'green' : 'gray'}>{template.status}</Badge>}
        </Group>
        <Group grow>
          <TextInput label="Code" disabled={!isNew} {...form.getInputProps('template_code')} />
          <TextInput label="Name" {...form.getInputProps('template_name')} />
        </Group>
        <Group grow>
          <Select label="Channel" data={['EMAIL', 'SMS', 'PUSH']} disabled={!isNew} {...form.getInputProps('channel_type')} />
          <Select label="Category" data={['STANDARD', 'OTP']} {...form.getInputProps('category')} />
        </Group>
        <Checkbox label="Critical (bypasses user opt-out)" {...form.getInputProps('is_critical', { type: 'checkbox' })} />
        {v.category === 'OTP' && (
          <Group grow>
            <TextInput label="OTP variable" {...form.getInputProps('otp_variable')} />
            <Select
              label="Expiry variable (minutes)"
              data={scan.valid}
              clearable
              value={v.expiry_variable || null}
              onChange={(val) => form.setFieldValue('expiry_variable', val ?? '')}
            />
          </Group>
        )}
        {isEmail && <TextInput label="Subject" {...form.getInputProps('subject_template')} />}
        {v.channel_type === 'PUSH' && <TextInput label="Title" {...form.getInputProps('title_template')} />}
        <Group justify="space-between">
          <Text fw={500} size="sm">
            Body
          </Text>
          <InsertVariableMenu variables={scan.valid} onInsert={insert} />
        </Group>
        {isEmail ? (
          <RichTextEditor editor={editor}>
            <RichTextEditor.Toolbar>
              <RichTextEditor.ControlsGroup>
                <RichTextEditor.Bold />
                <RichTextEditor.Italic />
                <RichTextEditor.Underline />
                <RichTextEditor.BulletList />
                <RichTextEditor.OrderedList />
                <RichTextEditor.Link />
              </RichTextEditor.ControlsGroup>
            </RichTextEditor.Toolbar>
            <RichTextEditor.Content />
          </RichTextEditor>
        ) : (
          <Textarea
            aria-label="Body"
            autosize
            minRows={4}
            {...form.getInputProps('body_template')}
            description={
              v.channel_type === 'SMS'
                ? `${sms.chars} characters · ${sms.segments} segment(s) · ${sms.encoding}`
                : `${sms.chars} characters`
            }
          />
        )}
        {form.errors.body_template && (
          <Text c="red" size="sm">
            {form.errors.body_template}
          </Text>
        )}
        <Group gap="xs" data-testid="required-variables">
          <Text size="sm">Required variables:</Text>
          {scan.valid.length === 0 ? (
            <Text size="sm" c="dimmed">
              none
            </Text>
          ) : (
            scan.valid.map((name) => (
              <Badge key={name} variant="light">
                {name}
              </Badge>
            ))
          )}
          {scan.invalid.map((bad) => (
            <Badge key={bad} color="red" variant="light">
              invalid {bad}
            </Badge>
          ))}
        </Group>
        <JsonInput
          label="External template reference (JSON)"
          description="Only for channels that use provider-side templates (e.g. Zalo ZNS)"
          formatOnBlur
          autosize
          minRows={2}
          {...form.getInputProps('external_template_ref')}
        />
        <ProblemAlert
          error={save.error}
          onReload={
            conflict
              ? () => {
                  save.reset();
                  refetch();
                }
              : undefined
          }
        />
        <ProblemAlert error={setActive.error} />
        <Group>
          <Button type="submit" loading={save.isPending}>
            Save
          </Button>
          <Button variant="default" onClick={preview.open} disabled={!v.body_template}>
            Preview
          </Button>
          {template && (
            <Button
              variant="subtle"
              color={template.status === 'ACTIVE' ? 'red' : 'green'}
              onClick={() => setActive.mutate({ active: template.status !== 'ACTIVE', version: template.version ?? 0 })}
            >
              {template.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
            </Button>
          )}
        </Group>
      </Stack>
      <TemplatePreviewModal
        opened={previewOpen}
        onClose={preview.close}
        channelType={v.channel_type}
        subject={isEmail ? v.subject_template : undefined}
        title={v.channel_type === 'PUSH' ? v.title_template : undefined}
        body={v.body_template}
        variables={scan.valid}
      />
    </form>
  );
}
