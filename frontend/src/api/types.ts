// Mirrors the Spring Boot API (see the Swagger UI at the backend root for the source of truth).

export interface User {
  id: number;
  email: string;
  name: string | null;
  avatarUrl: string | null;
}

export interface LinkItem {
  id: number;
  code: string;
  shortUrl: string;
  longUrl: string;
  custom: boolean;
  active: boolean;
  expiresAt: string | null;
  createdAt: string;
  totalClicks: number;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CreateLinkInput {
  longUrl: string;
  alias?: string;
  expiresAt?: string;
}

export interface Totals {
  clicks: number;
  uniqueVisitors: number;
  botClicks: number;
}

export interface DailyPoint {
  date: string; // YYYY-MM-DD, a UTC day
  clicks: number;
}

export interface Bucket {
  key: string;
  count: number;
}

export interface TopLink {
  linkId: number;
  code: string;
  shortUrl: string;
  longUrl: string;
  clicks: number;
}

export interface LinkStats {
  days: number;
  totals: Totals;
  timeseries: DailyPoint[];
  countries: Bucket[];
  devices: Bucket[];
  browsers: Bucket[];
  operatingSystems: Bucket[];
  referrers: Bucket[];
}

export interface Overview {
  days: number;
  totals: Totals;
  timeseries: DailyPoint[];
  topLinks: TopLink[];
}
