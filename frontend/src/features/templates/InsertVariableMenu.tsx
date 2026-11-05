import { Button, Group, Menu, TextInput } from '@mantine/core';
import { useState } from 'react';
import { isValidPlaceholderName } from './placeholders';

interface Props {
  variables: string[];
  onInsert: (placeholder: string) => void;
}

/** Inserts `${name}` — an existing variable or a new valid name (T136). */
export function InsertVariableMenu({ variables, onInsert }: Props) {
  const [name, setName] = useState('');
  const valid = isValidPlaceholderName(name);
  return (
    <Menu shadow="md" width={240} closeOnItemClick>
      <Menu.Target>
        <Button size="xs" variant="light">
          Insert variable
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        {variables.map((v) => (
          <Menu.Item key={v} onClick={() => onInsert(`\${${v}}`)}>
            {'${' + v + '}'}
          </Menu.Item>
        ))}
        {variables.length > 0 && <Menu.Divider />}
        <Group p="xs" gap="xs" wrap="nowrap">
          <TextInput
            size="xs"
            placeholder="new_variable"
            value={name}
            onChange={(e) => setName(e.currentTarget.value)}
            error={name && !valid ? 'letters, digits, _' : undefined}
            aria-label="New variable name"
          />
          <Button
            size="xs"
            disabled={!valid}
            onClick={() => {
              onInsert(`\${${name}}`);
              setName('');
            }}
          >
            Add
          </Button>
        </Group>
      </Menu.Dropdown>
    </Menu>
  );
}
