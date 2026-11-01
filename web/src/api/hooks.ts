import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { api, unwrap } from "./client";

const PAGE_SIZE = 20;

export function useSearch(q: string, page: number) {
  return useQuery({
    queryKey: ["search", q, page],
    queryFn: () => unwrap(api.GET("/api/search", { params: { query: { q, page, size: PAGE_SIZE } } })),
    enabled: q.trim().length >= 2,
    placeholderData: keepPreviousData,
  });
}

export function useCompany(reg: string) {
  return useQuery({
    queryKey: ["company", reg],
    queryFn: () => unwrap(api.GET("/api/companies/{reg}", { params: { path: { reg } } })),
  });
}

export function useCompanyEvents(reg: string, page: number) {
  return useQuery({
    queryKey: ["company-events", reg, page],
    queryFn: () =>
      unwrap(api.GET("/api/companies/{reg}/events", { params: { path: { reg }, query: { page, size: PAGE_SIZE } } })),
    placeholderData: keepPreviousData,
  });
}

export function useNetwork(reg: string) {
  return useQuery({
    queryKey: ["network", reg],
    queryFn: () => unwrap(api.GET("/api/companies/{reg}/network", { params: { path: { reg } } })),
  });
}

export function useEvents(date: string | undefined, type: string | undefined, page: number) {
  return useQuery({
    queryKey: ["events", date, type, page],
    queryFn: () => unwrap(api.GET("/api/events", { params: { query: { date, type, page, size: PAGE_SIZE } } })),
    placeholderData: keepPreviousData,
  });
}

export function useStats() {
  return useQuery({ queryKey: ["stats"], queryFn: () => unwrap(api.GET("/api/stats")), staleTime: 10 * 60_000 });
}

export function useRuns(page: number) {
  return useQuery({
    queryKey: ["runs", page],
    queryFn: () => unwrap(api.GET("/api/runs", { params: { query: { page, size: PAGE_SIZE } } })),
    placeholderData: keepPreviousData,
  });
}

export function useRun(id: string) {
  return useQuery({
    queryKey: ["run", id],
    queryFn: () => unwrap(api.GET("/api/runs/{id}", { params: { path: { id } } })),
  });
}

export function useMassAddresses(page: number) {
  return useQuery({
    queryKey: ["mass-addresses", page],
    queryFn: () =>
      unwrap(api.GET("/api/features/mass-addresses", { params: { query: { page, size: PAGE_SIZE } } })),
    placeholderData: keepPreviousData,
  });
}
