import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ThemeToggle } from './ThemeToggle';

export function Layout() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();

  async function leave() {
    await signOut();
    navigate('/login', { replace: true });
  }

  return (
    <>
      <a className="skip" href="#main">Skip to content</a>
      <header className="masthead">
        <div className="masthead__inner">
          <NavLink to="/" className="wordmark" aria-label="Linkly, overview">
            linkly<span className="wordmark__slash">/</span>
          </NavLink>
          <nav className="nav" aria-label="Main">
            <NavLink to="/" end>Overview</NavLink>
            <NavLink to="/links">Links</NavLink>
          </nav>
          <div className="masthead__who">
            <span className="masthead__email" title={user?.email}>{user?.email}</span>
            <ThemeToggle />
            <button type="button" className="btn btn--text copy" onClick={leave}>Sign out</button>
          </div>
        </div>
      </header>
      <main id="main">
        <Outlet />
      </main>
      <footer className="colophon">
        <span>Unique visitors are counted per UTC day. IP addresses are never stored.</span>
        <span>
          <a href="https://db-ip.com" target="_blank" rel="noopener noreferrer">IP Geolocation by DB-IP</a>
        </span>
      </footer>
    </>
  );
}
