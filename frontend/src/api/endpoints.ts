import { api, apiBlob, ApiError } from './client';
import type { CreateLinkInput, LinkItem, LinkStats, Overview, Page, User } from './types';

export const auth = {
  /** null when nobody is signed in (a 401 is an answer here, not an error). */
  async me(): Promise<User | null> {
    try {
      return await api<User>('/api/auth/me');
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return null;
      throw e;
    }
  },
  login: (email: string, password: string) => api<User>('/api/auth/login', { method: 'POST', body: { email, password } }),
  register: (email: string, password: string, name?: string) =>
    api<User>('/api/auth/register', { method: 'POST', body: { email, password, name: name || undefined } }),
  google: (idToken: string) => api<User>('/api/auth/google', { method: 'POST', body: { idToken } }),
  logout: () => api<void>('/api/auth/logout', { method: 'POST' }),
};

export const links = {
  list: (page: number, size = 20) => api<Page<LinkItem>>(`/api/links?page=${page}&size=${size}`),
  get: (id: number) => api<LinkItem>(`/api/links/${id}`),
  create: (input: CreateLinkInput) => api<LinkItem>('/api/links', { method: 'POST', body: input }),
  deactivate: (id: number) => api<void>(`/api/links/${id}`, { method: 'DELETE' }),
  qr: (id: number, format: 'png' | 'svg', size = 512) => apiBlob(`/api/links/${id}/qr?format=${format}&size=${size}`),
};

export const stats = {
  overview: (days: number) => api<Overview>(`/api/stats/overview?days=${days}`),
  link: (id: number, days: number) => api<LinkStats>(`/api/links/${id}/stats?days=${days}`),
};
