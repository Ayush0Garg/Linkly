import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { auth } from '../api/endpoints';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { GoogleSignIn } from '../components/GoogleSignIn';
import { useCooldown } from '../lib/cooldown';

const GOOGLE_CLIENT_ID = (import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined)?.trim();

const FACTS = [
  ['No IP addresses stored', 'Country comes from an offline lookup. The address is hashed with a key that changes every day, then forgotten.'],
  ['Bots are counted apart', 'Crawlers, link previews and scripts never inflate the numbers you read.'],
  ['Unique visitors reset daily', 'On purpose: the same person on two days counts twice, because we cannot tell it was them.'],
];

export function SignIn({ mode }: { mode: 'login' | 'register' }) {
  const { setUser } = useAuth();
  const register = mode === 'register';
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const cooldown = useCooldown();

  async function run(action: () => Promise<Awaited<ReturnType<typeof auth.login>>>) {
    setPending(true);
    setFormError(null);
    setErrors({});
    try {
      setUser(await action()); // GuestOnly notices the new state and moves us on
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.status === 429) cooldown.start(e.retryAfterSeconds ?? 30); // the button counts down instead of inviting retries
        setErrors(e.fieldErrors);
        if (Object.keys(e.fieldErrors).length === 0) setFormError(e.message);
      } else {
        setFormError('Could not reach the server. Check your connection and try again.');
      }
    } finally {
      setPending(false);
    }
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (register && password.length < 10) {
      setErrors({ password: 'Use at least 10 characters.' });
      return;
    }
    void run(() => (register ? auth.register(email.trim(), password, name.trim()) : auth.login(email.trim(), password)));
  }

  return (
    <div className="entry">
      <section className="entry__brand" aria-label="About Linkly">
        <div>
          <p className="entry__mark" aria-label="Linkly">
            linkly<span>/</span>
          </p>
          <p className="entry__tagline">Short links, and an honest count of who used them.</p>
        </div>
        <dl className="entry__facts">
          {FACTS.map(([title, text]) => (
            <div key={title} className="entry__fact">
              <dt><b>{title}</b></dt>
              <dd><span>{text}</span></dd>
            </div>
          ))}
        </dl>
      </section>

      <main className="entry__form">
        <div className="entry__panel">
          <nav className="tabs" aria-label="Account">
            <Link to="/login" aria-current={register ? undefined : 'page'}>Sign in</Link>
            <Link to="/register" aria-current={register ? 'page' : undefined}>Create account</Link>
          </nav>

          <form onSubmit={submit} noValidate>
            {formError && <p className="form-error" role="alert">{formError}</p>}

            {register && (
              <div className="field">
                <label className="field__label" htmlFor="name">Name <span className="field__optional">(optional)</span></label>
                <input id="name" className="input" autoComplete="name" value={name} maxLength={100}
                  onChange={(e) => setName(e.target.value)} />
              </div>
            )}

            <div className="field">
              <label className="field__label" htmlFor="email">Email</label>
              <input id="email" className="input" type="email" autoComplete="username" required value={email}
                aria-invalid={!!errors.email} aria-describedby={errors.email ? 'email-error' : undefined}
                onChange={(e) => setEmail(e.target.value)} />
              {errors.email && <p id="email-error" className="field__error">{errors.email}</p>}
            </div>

            <div className="field">
              <label className="field__label" htmlFor="password">Password</label>
              <input id="password" className="input" type="password" required value={password}
                autoComplete={register ? 'new-password' : 'current-password'}
                aria-invalid={!!errors.password} aria-describedby={errors.password ? 'password-error' : 'password-hint'}
                onChange={(e) => setPassword(e.target.value)} />
              {errors.password
                ? <p id="password-error" className="field__error">{errors.password}</p>
                : register && <p id="password-hint" className="field__hint">At least 10 characters.</p>}
            </div>

            <button className="btn btn--wide" type="submit" disabled={pending || cooldown.secondsLeft > 0}>
              {cooldown.secondsLeft > 0
                ? `Try again in ${cooldown.secondsLeft}s`
                : pending ? 'One moment…' : register ? 'Create account' : 'Sign in'}
            </button>
          </form>

          {GOOGLE_CLIENT_ID && (
            <>
              <div className="divider">or</div>
              <GoogleSignIn clientId={GOOGLE_CLIENT_ID} onCredential={(token) => void run(() => auth.google(token))} />
            </>
          )}
        </div>
      </main>
    </div>
  );
}
