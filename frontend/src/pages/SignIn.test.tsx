import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { auth } from '../api/endpoints';
import { AuthProvider } from '../auth/AuthContext';
import { GuestOnly, RequireAuth } from '../auth/Guards';
import { renderWithProviders } from '../test/render';
import { SignIn } from './SignIn';

vi.mock('../api/endpoints', () => ({
  auth: { me: vi.fn(), login: vi.fn(), register: vi.fn(), google: vi.fn(), logout: vi.fn() },
}));
const mocked = vi.mocked(auth);
const user = { id: 1, email: 'ayush@example.com', name: 'Ayush', avatarUrl: null };

function app(route: string) {
  return renderWithProviders(
    <AuthProvider>
      <Routes>
        <Route element={<GuestOnly />}>
          <Route path="/login" element={<SignIn mode="login" />} />
          <Route path="/register" element={<SignIn mode="register" />} />
        </Route>
        <Route element={<RequireAuth />}>
          <Route path="/" element={<h1>Overview page</h1>} />
          <Route path="/links" element={<h1>Links page</h1>} />
        </Route>
      </Routes>
    </AuthProvider>,
    { route },
  );
}

beforeEach(() => {
  Object.values(mocked).forEach((fn) => fn.mockReset());
  mocked.me.mockResolvedValue(null);
});

describe('route guards', () => {
  it('sends a signed-out visitor to sign-in', async () => {
    app('/links');
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
  });

  it('lets a signed-in visitor through, and bounces them off the sign-in page', async () => {
    mocked.me.mockResolvedValue(user);
    app('/login');
    expect(await screen.findByRole('heading', { name: 'Overview page' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/');
  });
});

describe('sign in', () => {
  it('signs in with a trimmed email and lands on the app', async () => {
    mocked.login.mockResolvedValueOnce(user);
    app('/login');
    await userEvent.type(await screen.findByLabelText('Email'), '  ayush@example.com ');
    await userEvent.type(screen.getByLabelText('Password'), 'correct horse battery');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(mocked.login).toHaveBeenCalledWith('ayush@example.com', 'correct horse battery'));
    expect(await screen.findByRole('heading', { name: 'Overview page' })).toBeInTheDocument();
  });

  it('returns to the page the visitor was trying to reach', async () => {
    mocked.login.mockResolvedValueOnce(user);
    app('/links');
    await userEvent.type(await screen.findByLabelText('Email'), 'ayush@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'correct horse battery');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('heading', { name: 'Links page' })).toBeInTheDocument();
  });

  it('shows the server\'s answer to a wrong password and keeps what was typed', async () => {
    mocked.login.mockRejectedValueOnce(new ApiError(401, 'Invalid email or password'));
    app('/login');
    await userEvent.type(await screen.findByLabelText('Email'), 'ayush@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password');
    expect(screen.getByLabelText('Email')).toHaveValue('ayush@example.com');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
  });

  it('reports an unreachable server plainly', async () => {
    mocked.login.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    app('/login');
    await userEvent.type(await screen.findByLabelText('Email'), 'a@b.co');
    await userEvent.type(screen.getByLabelText('Password'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Could not reach the server/);
  });

  it('does not offer Google when no client id is configured', async () => {
    app('/login');
    await screen.findByLabelText('Email');
    expect(screen.queryByText('or')).not.toBeInTheDocument();
  });
});

describe('too many attempts', () => {
  afterEach(() => vi.useRealTimers());

  it('shows the server\'s message and disables the button with a live countdown, then re-enables it', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    mocked.login.mockRejectedValueOnce(new ApiError(429, 'Too many attempts. Try again in 30 seconds.', {}, 30));
    app('/login');
    await userEvent.type(await screen.findByLabelText('Email'), 'ayush@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'whatever it is');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Try again in 30 seconds.');
    const waiting = screen.getByRole('button', { name: /Try again in \d+s/ });
    expect(waiting).toBeDisabled();
    expect(mocked.login).toHaveBeenCalledTimes(1);

    act(() => { vi.advanceTimersByTime(31_000); });
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeEnabled();
  });

  it('waits a sensible default when the server did not say how long', async () => {
    mocked.register.mockRejectedValueOnce(new ApiError(429, 'Too many attempts.'));
    app('/register');
    await userEvent.type(await screen.findByLabelText('Email'), 'ayush@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'correct horse battery');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('button', { name: /Try again in 30s|Try again in 29s/ })).toBeDisabled();
  });
});

describe('create account', () => {
  it('refuses a short password before calling the server', async () => {
    app('/register');
    await userEvent.type(await screen.findByLabelText('Email'), 'ayush@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'short');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(screen.getByText('Use at least 10 characters.')).toBeInTheDocument();
    expect(mocked.register).not.toHaveBeenCalled();
  });

  it('sends the optional name only when given, and signs the new user in', async () => {
    mocked.register.mockResolvedValueOnce(user);
    app('/register');
    await userEvent.type(await screen.findByLabelText(/Name/), ' Ayush ');
    await userEvent.type(screen.getByLabelText('Email'), 'ayush@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'correct horse battery');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() => expect(mocked.register).toHaveBeenCalledWith('ayush@example.com', 'correct horse battery', 'Ayush'));
    expect(await screen.findByRole('heading', { name: 'Overview page' })).toBeInTheDocument();
  });

  it('shows the server\'s per-field problems next to the fields', async () => {
    mocked.register.mockRejectedValueOnce(new ApiError(400, 'Please correct the highlighted fields.', { email: 'Must be a well-formed email address.' }));
    app('/register');
    await userEvent.type(await screen.findByLabelText('Email'), 'nope');
    await userEvent.type(screen.getByLabelText('Password'), 'correct horse battery');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText('Must be a well-formed email address.')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
  });

  it('tells a duplicate email apart from a field problem', async () => {
    mocked.register.mockRejectedValueOnce(new ApiError(409, 'An account with this email already exists'));
    app('/register');
    await userEvent.type(await screen.findByLabelText('Email'), 'ayush@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'correct horse battery');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('An account with this email already exists');
  });
});
