import { Badge } from "@/components/ui/badge";
import { humanize } from "@/lib/format";

const CLOSED = new Set(["TERMINATED", "REORGANIZED"]);
const TROUBLE = new Set(["IN_LIQUIDATION", "INSOLVENT", "LEGAL_PROTECTION", "WARN", "FAILED"]);

export function StatusBadge({ status }: { status: string | null | undefined }) {
  if (!status) {
    return null;
  }
  const variant = CLOSED.has(status) || status === "FAILED" ? "destructive" : TROUBLE.has(status) ? "secondary" : "outline";
  return <Badge variant={variant}>{humanize(status)}</Badge>;
}
