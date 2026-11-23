import {
  AppShell, Group, Text, NavLink, Box, ActionIcon, Burger, Tooltip, Stack, Badge, Menu, Avatar,
  UnstyledButton, useMantineColorScheme, useComputedColorScheme,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import {
  IconUsers, IconChartAreaLine, IconBriefcase2, IconSun, IconMoonStars, IconRadar2,
  IconUserCircle, IconLogout,
} from '@tabler/icons-react';
import { NavLink as RouterNavLink, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth.jsx';

const LINKS = [
  { to: '/profiles', label: 'Profiles', hint: 'What you’re hunting for', icon: IconUsers },
  { to: '/jobs', label: 'Jobs', hint: 'Matches & applications', icon: IconBriefcase2 },
  { to: '/analytics', label: 'Analytics', hint: 'Trends & insights', icon: IconChartAreaLine },
  { to: '/account', label: 'Account', hint: 'Email & password', icon: IconUserCircle },
];

/** Avatar menu in the header: who is signed in, account settings, sign out. */
function AccountMenu() {
  const { user, logout } = useAuth();
  const nav = useNavigate();
  const label = user?.name || user?.email || '';
  return (
    <Menu position="bottom-end" width={240} shadow="md">
      <Menu.Target>
        <UnstyledButton aria-label="Account menu">
          <Avatar color="brand" radius="xl" size={36}>{label.slice(0, 1).toUpperCase()}</Avatar>
        </UnstyledButton>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>
          <Text fz="sm" fw={600} c="var(--mantine-color-text)" truncate>{user?.name || 'Signed in'}</Text>
          <Text fz="xs" c="dimmed" truncate>{user?.email}</Text>
        </Menu.Label>
        <Menu.Divider />
        <Menu.Item leftSection={<IconUserCircle size={16} />} onClick={() => nav('/account')}>
          Account settings
        </Menu.Item>
        <Menu.Item color="red" leftSection={<IconLogout size={16} />}
          onClick={async () => { await logout(); nav('/login', { replace: true }); }}>
          Sign out
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}

function ColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme();
  const scheme = useComputedColorScheme('light', { getInitialValueInEffect: true });
  const dark = scheme === 'dark';
  return (
    <Tooltip label={dark ? 'Switch to light mode' : 'Switch to dark mode'}>
      <ActionIcon
        variant="default"
        size="lg"
        aria-label="Toggle color scheme"
        onClick={() => setColorScheme(dark ? 'light' : 'dark')}
      >
        {dark ? <IconSun size={18} stroke={1.8} /> : <IconMoonStars size={18} stroke={1.8} />}
      </ActionIcon>
    </Tooltip>
  );
}

function Brand() {
  return (
    <Group gap={10} wrap="nowrap">
      <div className="jw-logo"><IconRadar2 size={20} stroke={2} /></div>
      <Box lh={1.1}>
        <Text fw={800} fz={19} className="jw-gradient-text">JobWatch</Text>
        <Text fz={11} c="dimmed" fw={500} visibleFrom="xs">job alert dashboard</Text>
      </Box>
    </Group>
  );
}

/**
 * App chrome shared by every page: glass header, nav rail (drawer on mobile),
 * and a themed content area with a soft background.
 */
export default function Layout({ children }) {
  const { pathname } = useLocation();
  const [opened, { toggle, close }] = useDisclosure(false);

  return (
    <AppShell
      header={{ height: 64 }}
      navbar={{ width: 264, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding={{ base: 'md', sm: 'xl' }}
    >
      <AppShell.Header
        style={{
          background: 'color-mix(in srgb, var(--jw-surface) 82%, transparent)',
          backdropFilter: 'blur(14px)',
          WebkitBackdropFilter: 'blur(14px)',
          borderBottom: '1px solid var(--jw-border)',
        }}
      >
        <Group h="100%" px="lg" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Toggle navigation" />
            <Brand />
          </Group>
          <Group gap="xs">
            <Badge variant="dot" color="teal" size="lg" visibleFrom="xs">Live</Badge>
            <ColorSchemeToggle />
            <AccountMenu />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar
        p="md"
        style={{ background: 'var(--jw-surface)', borderRight: '1px solid var(--jw-border)' }}
      >
        <Text fz={11} fw={700} tt="uppercase" c="dimmed" px="sm" mb={8} lts={0.6}>Menu</Text>
        {LINKS.map(({ to, label, hint, icon: Icon }) => {
          const active = pathname === to || pathname.startsWith(to + '/') ||
            (to === '/profiles' && pathname === '/');
          return (
            <NavLink
              key={to}
              component={RouterNavLink}
              to={to}
              label={label}
              description={hint}
              leftSection={<Icon size={20} stroke={1.8} />}
              active={active}
              className="jw-nav-link"
              onClick={close}
              py={10}
            />
          );
        })}

        <Stack mt="auto" gap={4} p="sm">
          <Text fz={12} c="dimmed" fw={500}>Tip</Text>
          <Text fz={12} c="dimmed" lh={1.5}>
            Activate several profiles — jobs matching <b>any</b> of them are shown and emailed.
          </Text>
        </Stack>
      </AppShell.Navbar>

      <AppShell.Main className="jw-main">
        <Box maw={1240} mx="auto" className="jw-fade-up">{children}</Box>
      </AppShell.Main>
    </AppShell>
  );
}
