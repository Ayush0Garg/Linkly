import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { links } from '../api/endpoints';
import type { LinkItem } from '../api/types';
import { Code } from '../components/Code';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { CopyButton } from '../components/CopyButton';
import { LinkComposer } from '../components/LinkComposer';
import { QueryError } from '../components/QueryState';
import { StatusBadge } from '../components/StatusBadge';
import { formatDate, formatExact, linkStatus } from '../lib/format';
import { useLinks } from '../lib/queries';

export function Links() {
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number(params.get('page')) || 0);
  const q = useLinks(page);
  const qc = useQueryClient();
  const [target, setTarget] = useState<LinkItem | null>(null);

  const deactivate = useMutation({
    mutationFn: (link: LinkItem) => links.deactivate(link.id),
    onSuccess: (_, link) => {
      setTarget(null);
      void qc.invalidateQueries({ queryKey: ['links'] });
      void qc.invalidateQueries({ queryKey: ['link', link.id] });
      void qc.invalidateQueries({ queryKey: ['overview'] });
    },
  });

  const data = q.data;
  const goTo = (p: number) => setParams(p === 0 ? {} : { page: String(p) });

  return (
    <div className="page">
      <header className="page__head">
        <p className="label">Links</p>
        <h1 className="page__title">Shorten an address</h1>
      </header>

      <LinkComposer />

      <section className="section" aria-label="Your links">
        <div className="section__head">
          <h2 className="section__title">Your links</h2>
          {data && <span className="section__note">{formatExact(data.totalElements)} in total, newest first</span>}
        </div>

        {q.isPending && <p className="muted" role="status">Loading…</p>}
        {q.isError && !data && <QueryError error={q.error} onRetry={() => void q.refetch()} />}

        {data && data.items.length === 0 && (
          <div className="empty">
            <p className="empty__title">Nothing here yet.</p>
            <p className="muted">Paste an address above. You will get a short link and a place to watch it.</p>
          </div>
        )}

        {data && data.items.length > 0 && (
          <div className={`table-wrap${q.isPlaceholderData ? ' busy' : ''}`}>
            <table className="ledger">
              <caption className="sr-only">Your short links</caption>
              <thead>
                <tr>
                  <th scope="col">Short link</th>
                  <th scope="col">Destination</th>
                  <th scope="col" className="num">Clicks</th>
                  <th scope="col">Created</th>
                  <th scope="col">Status</th>
                  <th scope="col"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((link) => (
                  <tr key={link.id}>
                    <td>
                      <Link to={`/links/${link.id}`} aria-label={`Stats for ${link.code}`}><Code code={link.code} /></Link>{' '}
                      <CopyButton text={link.shortUrl} />
                    </td>
                    <td>
                      <a className="cell-url" href={link.longUrl} target="_blank" rel="noopener noreferrer" title={link.longUrl}>
                        {link.longUrl}
                      </a>
                    </td>
                    <td className="num">{formatExact(link.totalClicks)}</td>
                    <td className="muted" style={{ whiteSpace: 'nowrap' }}>{formatDate(link.createdAt)}</td>
                    <td><StatusBadge link={link} /></td>
                    <td>
                      <div className="row-actions">
                        <Link className="copy" to={`/links/${link.id}`}>Stats</Link>
                        {linkStatus(link) !== 'deactivated' && (
                          <button type="button" className="btn btn--text btn--danger copy" onClick={() => setTarget(link)}>
                            Deactivate
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {data && data.totalPages > 1 && (
          <nav className="pager" aria-label="Pages">
            <button type="button" className="btn btn--quiet" disabled={page === 0} onClick={() => goTo(page - 1)}>Newer</button>
            <span className="label">Page {page + 1} of {data.totalPages}</span>
            <button type="button" className="btn btn--quiet" disabled={page + 1 >= data.totalPages} onClick={() => goTo(page + 1)}>Older</button>
          </nav>
        )}
      </section>

      <ConfirmDialog
        open={target !== null}
        title="Deactivate this link?"
        body={target ? `${target.shortUrl} will stop redirecting and visitors will see "no longer available". The code is never reused. This cannot be undone.` : ''}
        confirmLabel="Deactivate"
        busy={deactivate.isPending}
        onConfirm={() => target && deactivate.mutate(target)}
        onCancel={() => setTarget(null)}
      />
    </div>
  );
}
