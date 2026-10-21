import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { links, stats } from '../api/endpoints';

// While a new period / page loads, the previous result stays on screen (dimmed by the caller) instead of flashing.
export const useOverview = (days: number) =>
  useQuery({ queryKey: ['overview', days], queryFn: () => stats.overview(days), placeholderData: keepPreviousData });

export const useLinkStats = (id: number, days: number) =>
  useQuery({ queryKey: ['link-stats', id, days], queryFn: () => stats.link(id, days), placeholderData: keepPreviousData });

export const useLinks = (page: number) =>
  useQuery({ queryKey: ['links', page], queryFn: () => links.list(page), placeholderData: keepPreviousData });

export const useLink = (id: number) => useQuery({ queryKey: ['link', id], queryFn: () => links.get(id) });
