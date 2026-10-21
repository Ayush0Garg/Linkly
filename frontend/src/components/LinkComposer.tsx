import { useId, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { links } from '../api/endpoints';
import { ApiError } from '../api/client';
import type { LinkItem } from '../api/types';
import { useCooldown } from '../lib/cooldown';
import { localInputToIso } from '../lib/format';
import { Code } from './Code';
import { CopyButton } from './CopyButton';

const MAX_URL = 8192;

type Field = 'longUrl' | 'alias' | 'expiresAt';

/** Mirrors the server's rules to catch typos instantly; the server stays the authority on every one of them. */
export function validate(longUrl: string, alias: string, expires: string, now = new Date()): Partial<Record<Field, string>> {
  const errors: Partial<Record<Field, string>> = {};
  const url = longUrl.trim();
  if (!url) errors.longUrl = 'Paste the address you want to shorten.';
  else if (!/^https?:\/\//i.test(url)) errors.longUrl = 'The address must start with http:// or https://';
  else if (url.length > MAX_URL) errors.longUrl = `That address is longer than ${MAX_URL} characters.`;

  const a = alias.trim();
  if (a && !/^[A-Za-z0-9_-]{3,32}$/.test(a)) errors.alias = '3 to 32 letters, digits, - or _.';
  else if (/^linkly-/i.test(a)) errors.alias = '"linkly-" is reserved for generated links.';

  if (expires) {
    const when = new Date(expires);
    if (Number.isNaN(when.getTime()) || when <= now) errors.expiresAt = 'Pick a time in the future.';
  }
  return errors;
}

export function LinkComposer() {
  const qc = useQueryClient();
  const id = useId();
  const [longUrl, setLongUrl] = useState('');
  const [alias, setAlias] = useState('');
  const [expires, setExpires] = useState('');
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [created, setCreated] = useState<LinkItem | null>(null);
  const cooldown = useCooldown();

  const create = useMutation({
    mutationFn: () =>
      links.create({
        longUrl: longUrl.trim(),
        alias: alias.trim() || undefined,
        expiresAt: localInputToIso(expires),
      }),
    onSuccess: (link) => {
      setCreated(link);
      setLongUrl('');
      setAlias('');
      setExpires('');
      void qc.invalidateQueries({ queryKey: ['links'] });
      void qc.invalidateQueries({ queryKey: ['overview'] });
    },
    onError: (e) => {
      if (e instanceof ApiError) {
        if (e.status === 429) cooldown.start(e.retryAfterSeconds ?? 30);
        const serverFields = e.fieldErrors as Partial<Record<Field, string>>;
        setErrors(serverFields);
        // Service-level refusals (taken alias, blocked address) have no field name: show them above the form.
        if (Object.keys(serverFields).length === 0) setFormError(e.message);
      } else {
        setFormError('Could not reach the server. Try again.');
      }
    },
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    setCreated(null);
    const found = validate(longUrl, alias, expires);
    setErrors(found);
    if (Object.keys(found).length === 0) create.mutate();
  }

  const field = (name: Field) => ({
    'aria-invalid': !!errors[name],
    'aria-describedby': errors[name] ? `${id}-${name}-error` : undefined,
  });
  const error = (name: Field) =>
    errors[name] ? <p id={`${id}-${name}-error`} className="field__error">{errors[name]}</p> : null;

  return (
    <div>
      <form className="composer" onSubmit={submit} noValidate>
        <div className="field">
          <label className="field__label" htmlFor={`${id}-url`}>Destination</label>
          <input id={`${id}-url`} className="input input--mono" inputMode="url" autoComplete="off" spellCheck={false}
            placeholder="https://example.com/a/very/long/address" value={longUrl}
            onChange={(e) => setLongUrl(e.target.value)} {...field('longUrl')} />
          {error('longUrl')}
        </div>
        <div className="field">
          <label className="field__label" htmlFor={`${id}-alias`}>Alias <span className="field__optional">(optional)</span></label>
          <input id={`${id}-alias`} className="input input--mono" autoComplete="off" spellCheck={false}
            placeholder="my-launch" value={alias} onChange={(e) => setAlias(e.target.value)} {...field('alias')} />
          {error('alias')}
        </div>
        <div className="field">
          <label className="field__label" htmlFor={`${id}-exp`}>Expires <span className="field__optional">(optional)</span></label>
          <input id={`${id}-exp`} className="input" type="datetime-local" value={expires}
            onChange={(e) => setExpires(e.target.value)} {...field('expiresAt')} />
          {error('expiresAt')}
        </div>
        <button className="btn" type="submit" disabled={create.isPending || cooldown.secondsLeft > 0}>
          {cooldown.secondsLeft > 0 ? `Wait ${cooldown.secondsLeft}s` : create.isPending ? 'Creating…' : 'Shorten'}
        </button>
      </form>

      {formError && <p className="form-error" role="alert" style={{ marginTop: 14 }}>{formError}</p>}

      {created && (
        <div className="created" role="status">
          <span className="label">Created</span>
          <a href={created.shortUrl} target="_blank" rel="noopener noreferrer">
            <Code code={created.code} />
          </a>
          <CopyButton text={created.shortUrl} label="Copy link" />
          <Link to={`/links/${created.id}`} className="copy">Stats and QR code</Link>
        </div>
      )}
    </div>
  );
}
