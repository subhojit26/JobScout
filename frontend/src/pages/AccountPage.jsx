import { useState } from 'react';
import {
  Button, Card, Divider, Group, PasswordInput, Stack, Switch, Text, TextInput, Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconUserCircle } from '@tabler/icons-react';
import { PageHeader } from '../components/ui.jsx';
import { useAuth } from '../auth.jsx';
import { changePassword, errorMessage } from '../api.js';

/** Account settings: display name, daily email on/off, password change. */
export default function AccountPage() {
  const { user, updateSettings, replaceSession } = useAuth();
  const [name, setName] = useState(user?.name || '');
  const [saving, setSaving] = useState(false);
  const [pw, setPw] = useState({ current: '', next: '' });
  const [pwBusy, setPwBusy] = useState(false);

  const saveName = async () => {
    setSaving(true);
    try {
      await updateSettings({ name });
      notifications.show({ color: 'teal', message: 'Saved' });
    } catch (err) {
      notifications.show({ color: 'red', message: errorMessage(err, 'Could not save') });
    } finally {
      setSaving(false);
    }
  };

  const toggleDigest = async (on) => {
    try {
      await updateSettings({ digestEnabled: on });
      notifications.show({ color: 'teal', message: on ? 'Daily email turned on' : 'Daily email turned off' });
    } catch (err) {
      notifications.show({ color: 'red', message: errorMessage(err, 'Could not save') });
    }
  };

  const submitPassword = async (e) => {
    e.preventDefault();
    if (pw.next.length < 8) {
      notifications.show({ color: 'red', message: 'New password must be at least 8 characters.' });
      return;
    }
    setPwBusy(true);
    try {
      replaceSession(await changePassword({ currentPassword: pw.current, newPassword: pw.next }));
      setPw({ current: '', next: '' });
      notifications.show({ color: 'teal', message: 'Password changed. Other devices were signed out.' });
    } catch (err) {
      notifications.show({ color: 'red', message: errorMessage(err, 'Could not change password') });
    } finally {
      setPwBusy(false);
    }
  };

  return (
    <Stack gap={0} maw={640}>
      <PageHeader icon={IconUserCircle} title="Account" subtitle={user?.email} />
      <Card padding="lg">
        <Stack gap="lg">
          <Group align="flex-end" wrap="nowrap">
            <TextInput label="Name" value={name} onChange={(e) => setName(e.currentTarget.value)}
              style={{ flex: 1 }} />
            <Button variant="light" onClick={saveName} loading={saving}>Save</Button>
          </Group>

          <Switch
            checked={!!user?.digestEnabled}
            onChange={(e) => toggleDigest(e.currentTarget.checked)}
            label="Daily email at 6 AM (India time)"
            description={`New matches for your active profiles are sent to ${user?.email}.`}
          />

          <Divider />

          <form onSubmit={submitPassword}>
            <Stack gap="sm">
              <Title order={5}>Change password</Title>
              <PasswordInput label="Current password" value={pw.current} required
                autoComplete="current-password"
                onChange={(e) => { const v = e.currentTarget.value; setPw((p) => ({ ...p, current: v })); }} />
              <PasswordInput label="New password" value={pw.next} required
                autoComplete="new-password" description="At least 8 characters."
                onChange={(e) => { const v = e.currentTarget.value; setPw((p) => ({ ...p, next: v })); }} />
              <Group>
                <Button type="submit" variant="light" loading={pwBusy}>Change password</Button>
              </Group>
              <Text size="xs" c="dimmed">Changing your password signs you out on other devices.</Text>
            </Stack>
          </form>
        </Stack>
      </Card>
    </Stack>
  );
}
