import { AppShell, Burger, Button, Group, NavLink, Text } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { NavLink as RouterLink, Outlet, useLocation, useNavigate } from 'react-router';
import { api, unwrap } from '../api/client';
import { ClockOverrideBanner } from '../components/ClockOverrideBanner';
import { ME_KEY, useMe } from '../features/auth/useMe';
import { navFor } from './roles';

export function AppLayout() {
  const [opened, { toggle }] = useDisclosure();
  const { data: me } = useMe();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const logout = useMutation({
    mutationFn: () => unwrap(api.POST('/console/api/auth/logout')),
    onSettled: () => {
      queryClient.setQueryData(ME_KEY, null);
      navigate('/login');
    },
  });

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 230, breakpoint: 'sm', collapsed: { mobile: !opened } }} padding="md">
      <AppShell.Header>
        <ClockOverrideBanner />
        <Group h={56} px="md" justify="space-between">
          <Group>
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
            <Text fw={700}>Notification Hub</Text>
          </Group>
          <Group>
            <Text size="sm" c="dimmed">
              {me?.display_name ?? me?.username} · {me?.role}
            </Text>
            <Button size="xs" variant="default" onClick={() => logout.mutate()}>
              Sign out
            </Button>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs" aria-label="Main navigation">
        {navFor(me?.role).map((item) => (
          <NavLink
            key={item.path}
            component={RouterLink}
            to={item.path}
            label={item.label}
            active={location.pathname.startsWith(item.path)}
          />
        ))}
      </AppShell.Navbar>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
