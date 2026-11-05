import { Button, Center, Paper, PasswordInput, Stack, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { api, unwrap } from '../../api/client';
import { ProblemAlert } from '../../components/ProblemAlert';
import { ME_KEY } from './useMe';

export function LoginPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const form = useForm({
    initialValues: { username: '', password: '' },
    validate: {
      username: (v) => (v.trim() ? null : 'Username is required'),
      password: (v) => (v ? null : 'Password is required'),
    },
  });
  const login = useMutation({
    mutationFn: (values: { username: string; password: string }) =>
      unwrap(api.POST('/console/api/auth/login', { body: values })),
    onSuccess: (me) => {
      queryClient.setQueryData(ME_KEY, me);
      navigate('/dashboard', { replace: true });
    },
  });

  return (
    <Center h="100vh">
      <Paper withBorder p="xl" w={360} radius="md">
        <form onSubmit={form.onSubmit((v) => login.mutate(v))}>
          <Stack>
            <Title order={3}>PNH Console</Title>
            <TextInput label="Username" autoComplete="username" {...form.getInputProps('username')} />
            <PasswordInput label="Password" autoComplete="current-password" {...form.getInputProps('password')} />
            <ProblemAlert error={login.error} />
            <Button type="submit" loading={login.isPending}>
              Sign in
            </Button>
          </Stack>
        </form>
      </Paper>
    </Center>
  );
}
