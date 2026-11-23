import { useState } from 'react';
import {
  Alert, Anchor, Box, Button, Card, Center, Group, PasswordInput, Stack, Text, TextInput, Title,
} from '@mantine/core';
import { IconAlertCircle, IconRadar2 } from '@tabler/icons-react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth.jsx';
import { errorMessage } from '../api.js';

/** Sign-in and sign-up screen. `mode` is "login" or "signup". */
export default function AuthPage({ mode }) {
  const signup = mode === 'signup';
  const { login, signup: register } = useAuth();
  const nav = useNavigate();
  const location = useLocation();
  const [form, setForm] = useState({ name: '', email: '', password: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const set = (key) => (e) => {
    const { value } = e.currentTarget; // read now: currentTarget is gone by the time the updater runs
    setForm((f) => ({ ...f, [key]: value }));
  };

  const submit = async (e) => {
    e.preventDefault();
    setError('');
    if (signup && form.password.length < 8) {
      setError('Password must be at least 8 characters.');
      return;
    }
    setBusy(true);
    try {
      if (signup) {
        await register(form.name, form.email, form.password);
        nav('/profiles', { replace: true });
      } else {
        await login(form.email, form.password);
        nav(location.state?.from || '/profiles', { replace: true });
      }
    } catch (err) {
      setError(errorMessage(err, signup ? 'Could not create your account.' : 'Could not sign in.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Center mih="100vh" p="md" className="jw-main">
      <Box w="100%" maw={420} className="jw-fade-up">
        <Group gap={10} justify="center" mb="lg">
          <div className="jw-logo"><IconRadar2 size={20} stroke={2} /></div>
          <Text fw={800} fz={22} className="jw-gradient-text">JobWatch</Text>
        </Group>
        <Card padding="xl">
          <form onSubmit={submit}>
            <Stack gap="md">
              <Box>
                <Title order={3}>{signup ? 'Create your account' : 'Welcome back'}</Title>
                <Text c="dimmed" size="sm" mt={4}>
                  {signup
                    ? 'Set up your own job profiles and get matching openings by email every morning.'
                    : 'Sign in to see your matches and manage your profiles.'}
                </Text>
              </Box>

              {error && (
                <Alert color="red" variant="light" icon={<IconAlertCircle size={18} />}>{error}</Alert>
              )}

              {signup && (
                <TextInput label="Name" placeholder="Your name" value={form.name}
                  onChange={set('name')} autoComplete="name" />
              )}
              <TextInput label="Email" placeholder="you@example.com" type="email" required
                value={form.email} onChange={set('email')} autoComplete="email"
                description={signup ? 'Your daily job digest is sent here.' : undefined} />
              <PasswordInput label="Password" required value={form.password} onChange={set('password')}
                autoComplete={signup ? 'new-password' : 'current-password'}
                description={signup ? 'At least 8 characters.' : undefined} />

              <Button type="submit" size="md" loading={busy} fullWidth mt="xs">
                {signup ? 'Create account' : 'Sign in'}
              </Button>

              <Text size="sm" ta="center" c="dimmed">
                {signup ? 'Already have an account? ' : 'New to JobWatch? '}
                <Anchor component={Link} to={signup ? '/login' : '/signup'} fw={600}>
                  {signup ? 'Sign in' : 'Create an account'}
                </Anchor>
              </Text>
            </Stack>
          </form>
        </Card>
      </Box>
    </Center>
  );
}
