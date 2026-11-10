import {
  ActionIcon,
  AppShell,
  Avatar,
  Badge,
  Box,
  Burger,
  Divider,
  Group,
  Kbd,
  NavLink,
  ScrollArea,
  Stack,
  Text,
  Tooltip,
  UnstyledButton,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core';
import { useDisclosure, useLocalStorage, useMediaQuery, useOs } from '@mantine/hooks';
import { spotlight } from '@mantine/spotlight';
import {
  IconBellRinging,
  IconLayoutSidebarLeftCollapse,
  IconLayoutSidebarLeftExpand,
  IconLogout,
  IconMoon,
  IconSearch,
  IconSun,
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Fragment, useEffect, useRef } from 'react';
import { NavLink as RouterLink, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router';
import { api, unwrap } from '../api/client';
import { ClockOverrideBanner, useClock } from '../components/ClockOverrideBanner';
import { ME_KEY, useMe } from '../features/auth/useMe';
import { DeliveryLogDrawer } from '../features/notifications/DeliveryLogDrawer';
import classes from './AppLayout.module.css';
import { CommandPalette, INSPECT_PARAM } from './CommandPalette';
import { NAV_GROUPS, activeNavItem, navFor, type NavItem } from './roles';

const HEADER_HEIGHT = 56;
const FOOTER_HEIGHT = 32;
const NAV_WIDTH = 248;
const NAV_WIDTH_COLLAPSED = 64;
const APP_VERSION = '0.1.0';

function ColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme();
  const computed = useComputedColorScheme('light', { getInitialValueInEffect: true });
  const next = computed === 'dark' ? 'light' : 'dark';
  const label = `Switch to ${next} mode`;
  return (
    <Tooltip label={label}>
      <ActionIcon
        variant="subtle"
        color="gray"
        size="lg"
        aria-label={label}
        onClick={() => setColorScheme(next)}
      >
        {computed === 'dark' ? <IconSun size={18} stroke={1.75} /> : <IconMoon size={18} stroke={1.75} />}
      </ActionIcon>
    </Tooltip>
  );
}

function SearchTrigger() {
  const os = useOs();
  const mod = os === 'macos' || os === 'ios' ? '⌘' : 'Ctrl';
  return (
    <>
      <UnstyledButton
        className={classes.searchTrigger}
        onClick={spotlight.open}
        visibleFrom="sm"
        aria-label="Open command palette"
      >
        <IconSearch size={16} stroke={1.75} aria-hidden />
        <Text size="sm" c="dimmed" style={{ flex: 1 }}>
          Search or paste ID…
        </Text>
        <Group gap={2} wrap="nowrap" aria-hidden>
          <Kbd size="xs">{mod}</Kbd>
          <Kbd size="xs">K</Kbd>
        </Group>
      </UnstyledButton>
      <ActionIcon
        variant="subtle"
        color="gray"
        size="lg"
        hiddenFrom="sm"
        onClick={spotlight.open}
        aria-label="Open command palette"
      >
        <IconSearch size={18} stroke={1.75} />
      </ActionIcon>
    </>
  );
}

interface NavListProps {
  items: NavItem[];
  pathname: string;
  collapsed: boolean;
  onNavigate: () => void;
}

function NavList({ items, pathname, collapsed, onNavigate }: NavListProps) {
  const active = activeNavItem(pathname);
  const groups = NAV_GROUPS.map((g) => ({ group: g, items: items.filter((i) => i.group === g) })).filter(
    (g) => g.items.length > 0,
  );

  return (
    <Stack gap={2}>
      {groups.map(({ group, items: groupItems }, idx) => (
        <Fragment key={group}>
          {collapsed ? (
            idx > 0 && <Divider className={classes.groupDivider} />
          ) : (
            <Text component="div" className={classes.groupLabel} aria-hidden>
              {group}
            </Text>
          )}
          {groupItems.map((item) => {
            const Icon = item.icon;
            const isActive = active?.path === item.path;
            const link = (
              <NavLink
                component={RouterLink}
                to={item.path}
                onClick={onNavigate}
                active={isActive}
                aria-current={isActive ? 'page' : undefined}
                aria-label={collapsed ? item.label : undefined}
                label={collapsed ? null : item.label}
                leftSection={<Icon size={18} stroke={1.75} aria-hidden />}
                className={collapsed ? `${classes.navLink} ${classes.navLinkCollapsed}` : classes.navLink}
              />
            );
            return collapsed ? (
              <Tooltip key={item.path} label={item.label} position="right" withArrow openDelay={0}>
                {link}
              </Tooltip>
            ) : (
              <Fragment key={item.path}>{link}</Fragment>
            );
          })}
        </Fragment>
      ))}
    </Stack>
  );
}

function StatusFooter() {
  const { data: clock } = useClock();
  const overridden = !!clock?.mode && clock.mode !== 'SYSTEM';
  return (
    <AppShell.Footer className={classes.footer} component="footer">
      <Group gap={6} wrap="nowrap">
        <span className={classes.statusDot} data-warn={overridden || undefined} aria-hidden />
        <Text size="xs" c="dimmed">
          {overridden ? `Clock override: ${clock?.mode}` : 'System clock'}
        </Text>
      </Group>
      <Text size="xs" c="dimmed" ml="auto" visibleFrom="xs">
        Notification Hub · v{APP_VERSION}
      </Text>
      <Text size="xs" c="dimmed" ff="monospace" visibleFrom="sm">
        {Intl.DateTimeFormat().resolvedOptions().timeZone}
      </Text>
    </AppShell.Footer>
  );
}

export function AppLayout() {
  const [mobileOpened, { toggle: toggleMobile, close: closeMobile }] = useDisclosure();
  const [collapsed, setCollapsed] = useLocalStorage<boolean>({
    key: 'nh-nav-collapsed',
    defaultValue: false,
  });
  const isDesktop = useMediaQuery('(min-width: 48em)', true);
  const navCollapsed = collapsed && !!isDesktop;

  const { data: me } = useMe();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const mainRef = useRef<HTMLDivElement>(null);
  const [searchParams, setSearchParams] = useSearchParams();
  const inspectId = searchParams.get(INSPECT_PARAM);
  const closeInspector = () =>
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete(INSPECT_PARAM);
        return next;
      },
      { replace: true },
    );
  const logout = useMutation({
    mutationFn: () => unwrap(api.POST('/console/api/auth/logout')),
    onSettled: () => {
      queryClient.setQueryData(ME_KEY, null);
      navigate('/login');
    },
  });

  const items = navFor(me?.role);
  const current = activeNavItem(location.pathname);
  const displayName = me?.display_name ?? me?.username ?? '';

  // Keep the tab title in sync and move focus to the content on route change (screen readers).
  const firstRender = useRef(true);
  useEffect(() => {
    document.title = current ? `${current.label} · Notification Hub` : 'Notification Hub';
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    mainRef.current?.focus({ preventScroll: true });
  }, [location.pathname, current]);

  return (
    <AppShell
      header={{ height: HEADER_HEIGHT }}
      footer={{ height: FOOTER_HEIGHT }}
      navbar={{
        width: navCollapsed ? NAV_WIDTH_COLLAPSED : NAV_WIDTH,
        breakpoint: 'sm',
        collapsed: { mobile: !mobileOpened },
      }}
      padding={0}
    >
      <a href="#main-content" className={classes.skipLink}>
        Skip to main content
      </a>

      <AppShell.Header component="header">
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger
              opened={mobileOpened}
              onClick={toggleMobile}
              hiddenFrom="sm"
              size="sm"
              aria-label={mobileOpened ? 'Close navigation' : 'Open navigation'}
            />
            <UnstyledButton component={RouterLink} to="/dashboard" aria-label="Notification Hub home">
              <Group gap={10} wrap="nowrap">
                <span className={classes.brandMark}>
                  <IconBellRinging size={18} stroke={2} aria-hidden />
                </span>
                <Box visibleFrom="xs">
                  <Text fw={700} size="md" lh={1.1}>
                    Notification Hub
                  </Text>
                  <Text size="xs" c="dimmed" lh={1.2}>
                    Operations console
                  </Text>
                </Box>
              </Group>
            </UnstyledButton>
            {current && (
              <>
                <Divider orientation="vertical" mx={4} visibleFrom="md" />
                <Text size="sm" fw={500} c="dimmed" visibleFrom="md">
                  {current.group} /{' '}
                  <Text span inherit c="var(--mantine-color-text)">
                    {current.label}
                  </Text>
                </Text>
              </>
            )}
          </Group>

          <Group gap="xs" wrap="nowrap">
            <SearchTrigger />
            <ColorSchemeToggle />
            <Divider orientation="vertical" mx={4} />
            {me && (
              <Group gap={10} wrap="nowrap">
                <Avatar size={32} radius="xl" color="initials" name={displayName} aria-hidden />
                <Box visibleFrom="sm">
                  <Text size="sm" fw={600} lh={1.2}>
                    {displayName}
                  </Text>
                  <Badge size="xs" variant="light" color="gray" mt={2}>
                    {me.role}
                  </Badge>
                </Box>
              </Group>
            )}
            <Tooltip label="Sign out">
              <ActionIcon
                variant="subtle"
                color="red"
                size="lg"
                aria-label="Sign out"
                loading={logout.isPending}
                onClick={() => logout.mutate()}
              >
                <IconLogout size={18} stroke={1.75} />
              </ActionIcon>
            </Tooltip>
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar className={classes.navbar} aria-label="Main navigation" component="nav">
        <AppShell.Section grow component={ScrollArea} px={navCollapsed ? 8 : 10} pb="sm">
          <NavList
            items={items}
            pathname={location.pathname}
            collapsed={navCollapsed}
            onNavigate={closeMobile}
          />
        </AppShell.Section>
        <AppShell.Section
          visibleFrom="sm"
          p={8}
          style={{ borderTop: '1px solid var(--app-shell-border-color)' }}
        >
          <Tooltip label={navCollapsed ? 'Expand sidebar' : 'Collapse sidebar'} position="right">
            <ActionIcon
              variant="subtle"
              color="gray"
              size="lg"
              onClick={() => setCollapsed((c) => !c)}
              aria-label={navCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
              aria-expanded={!navCollapsed}
              ml={navCollapsed ? 4 : 2}
            >
              {navCollapsed ? (
                <IconLayoutSidebarLeftExpand size={18} />
              ) : (
                <IconLayoutSidebarLeftCollapse size={18} />
              )}
            </ActionIcon>
          </Tooltip>
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main className={classes.main}>
        <ClockOverrideBanner />
        <div id="main-content" ref={mainRef} tabIndex={-1} className={classes.content}>
          <Outlet />
        </div>
      </AppShell.Main>

      <StatusFooter />
      <CommandPalette role={me?.role} />
      {(me?.role === 'ADMIN' || me?.role === 'SUPPORT_VIEWER') && (
        <DeliveryLogDrawer notificationId={inspectId} onClose={closeInspector} />
      )}
    </AppShell>
  );
}
