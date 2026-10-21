import { Link } from 'react-router-dom';

export function NotFound({ inApp = false }: { inApp?: boolean }) {
  return (
    <div className={inApp ? 'page' : 'page notfound'}>
      <div className="notfound">
        <p className="notfound__code" aria-hidden="true">404</p>
        <h1 className="page__title">Nothing at this address.</h1>
        <p className="muted">The page may have moved, or the link is not yours.</p>
        <Link className="btn" to="/">Back to overview</Link>
      </div>
    </div>
  );
}
