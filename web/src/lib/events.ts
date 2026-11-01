import type { CompanyEvent } from "@/api/types";

type Payload = Record<string, unknown>;

interface PayloadRole {
  kind?: string;
  position?: string;
  holder?: { name?: string };
}

function text(value: unknown): string {
  if (value === null || value === undefined) {
    return "–";
  }
  if (typeof value === "string") {
    return value;
  }
  if (typeof value === "object" && value !== null) {
    const v = value as Record<string, unknown>;
    if (typeof v.line === "string") {
      return v.line;
    }
    if (typeof v.code === "string") {
      return v.code;
    }
  }
  return JSON.stringify(value);
}

function roles(value: unknown): PayloadRole[] {
  return Array.isArray(value) ? (value as PayloadRole[]) : [];
}

function roleText(role: PayloadRole): string {
  const who = role.holder?.name ?? "unknown";
  return role.position ? `${who} (${role.position.toLowerCase().replaceAll("_", " ")})` : who;
}

/** One line describing an event's change, from its canonical payload. */
export function summarize(event: Pick<CompanyEvent, "type" | "payload">): string {
  const p = event.payload as Payload;
  switch (event.type) {
    case "NAME_CHANGED":
    case "ADDRESS_CHANGED":
    case "LEGAL_FORM_CHANGED":
    case "STATUS_CHANGED":
      return `${text(p.old)} → ${text(p.new)}`;
    case "MANAGEMENT_CHANGED":
    case "OWNERSHIP_CHANGED": {
      const added = roles(p.added);
      const removed = roles(p.removed);
      const parts = [];
      if (added.length > 0) {
        parts.push(`+ ${added.map(roleText).join(", ")}`);
      }
      if (removed.length > 0) {
        parts.push(`− ${removed.map(roleText).join(", ")}`);
      }
      return parts.join("; ") || "roles changed";
    }
    case "REGISTERED":
      return p.registeredOn ? `registered on ${text(p.registeredOn)}` : "registered";
    case "TERMINATED":
      return p.terminatedOn ? `terminated on ${text(p.terminatedOn)}` : "terminated";
    case "MISSING_FROM_SNAPSHOT":
      return "no longer in the register file";
    default:
      return "";
  }
}

export const EVENT_TYPES = [
  "REGISTERED",
  "NAME_CHANGED",
  "ADDRESS_CHANGED",
  "LEGAL_FORM_CHANGED",
  "STATUS_CHANGED",
  "MANAGEMENT_CHANGED",
  "OWNERSHIP_CHANGED",
  "TERMINATED",
  "MISSING_FROM_SNAPSHOT",
] as const;
