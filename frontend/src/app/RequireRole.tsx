import { Center, Loader, Text, Title, Stack } from '@mantine/core';
import type { ReactNode } from 'react';
import { Navigate } from 'react-router';
import type { Role } from '../api/client';
import { useMe } from '../features/auth/useMe';

interface Props {
  roles?: Role[];
  children: ReactNode;
}

/** Redirects to /login when signed out; shows a 403 page when the role is not allowed (UI hint — server enforces). */
export function RequireRole({ roles, children }: Props) {
  const { data: me, isLoading } = useMe();
  if (isLoading) {
    return (
      <Center h={200}>
        <Loader />
      </Center>
    );
  }
  if (!me) return <Navigate to="/login" replace />;
  if (roles && (!me.role || !roles.includes(me.role))) {
    return (
      <Stack align="center" mt="xl">
        <Title order={3}>403 — Not allowed</Title>
        <Text c="dimmed">Your role ({me.role}) cannot open this page.</Text>
      </Stack>
    );
  }
  return <>{children}</>;
}
