import type { StatRow } from "@/api/types";

export interface Bar {
  key: string;
  value: number;
}

/** Sums a stat by one dimension, largest first; at most {@code limit} bars, the rest as "Other". */
export function sumBy(rows: StatRow[], dimension: string, limit = Number.POSITIVE_INFINITY): Bar[] {
  const totals = new Map<string, number>();
  for (const row of rows) {
    const key = row.dimensions[dimension] || "unknown";
    totals.set(key, (totals.get(key) ?? 0) + row.value);
  }
  const bars = [...totals].map(([key, value]) => ({ key, value })).sort((a, b) => b.value - a.value || a.key.localeCompare(b.key));
  if (bars.length <= limit) {
    return bars;
  }
  const rest = bars.slice(limit).reduce((sum, bar) => sum + bar.value, 0);
  return [...bars.slice(0, limit), { key: "Other", value: rest }];
}

export function total(rows: StatRow[]): number {
  return rows.reduce((sum, row) => sum + row.value, 0);
}

export interface CaptureEvents {
  capture: string;
  [eventType: string]: string | number;
}

/** Events per capture, one object per capture date with a count per event type (for a stacked chart). */
export function eventsPerCapture(rows: StatRow[]): { data: CaptureEvents[]; types: string[] } {
  const byCapture = new Map<string, CaptureEvents>();
  const types = new Set<string>();
  for (const row of rows) {
    const capture = row.dimensions.capture_date ?? "unknown";
    const type = row.dimensions.event_type ?? "unknown";
    types.add(type);
    const entry = byCapture.get(capture) ?? { capture };
    entry[type] = ((entry[type] as number | undefined) ?? 0) + row.value;
    byCapture.set(capture, entry);
  }
  return {
    data: [...byCapture.values()].sort((a, b) => a.capture.localeCompare(b.capture)),
    types: [...types].sort(),
  };
}
