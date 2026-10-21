import { Route, Routes } from 'react-router-dom';
import { GuestOnly, RequireAuth } from './auth/Guards';
import { Layout } from './components/Layout';
import { LinkDetail } from './pages/LinkDetail';
import { Links } from './pages/Links';
import { NotFound } from './pages/NotFound';
import { Overview } from './pages/Overview';
import { SignIn } from './pages/SignIn';

export function App() {
  return (
    <Routes>
      <Route element={<GuestOnly />}>
        <Route path="/login" element={<SignIn mode="login" />} />
        <Route path="/register" element={<SignIn mode="register" />} />
      </Route>
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route index element={<Overview />} />
          <Route path="links" element={<Links />} />
          <Route path="links/:id" element={<LinkDetail />} />
        </Route>
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
