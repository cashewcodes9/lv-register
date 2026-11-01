const numbers = new Intl.NumberFormat("en-GB");

export function formatNumber(n: number): string {
  return numbers.format(n);
}

/** ISO date (2026-11-09) as "9 Nov 2026"; null and undefined as an en dash. */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) {
    return "–";
  }
  const date = new Date(`${iso.slice(0, 10)}T00:00:00Z`);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}

export function formatBytes(bytes: number): string {
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

const ACRONYMS: Record<string, string> = { LLC: "LLC", SIA: "SIA", AS: "AS", IK: "IK", EUR: "EUR" };

/** SCREAMING_SNAKE or snake_case codes as words: "MANAGEMENT_CHANGED" → "Management changed", "LLC" stays. */
export function humanize(code: string | null | undefined): string {
  if (!code) {
    return "–";
  }
  if (ACRONYMS[code]) {
    return ACRONYMS[code];
  }
  const words = code.toLowerCase().replaceAll("_", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** Registration numbers as UR shows them; also a cheap check for the router. */
export function isRegNumber(value: string): boolean {
  return /^(\d{9}|\d{11})$/.test(value);
}
