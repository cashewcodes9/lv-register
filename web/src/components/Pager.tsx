import { Button } from "@/components/ui/button";
import { formatNumber } from "@/lib/format";

interface Props {
  page: number;
  size: number;
  total: number;
  onPage: (page: number) => void;
}

/** Previous / next with "21–40 of 489,963". Pages start at 0, as in the API. */
export function Pager({ page, size, total, onPage }: Props) {
  if (total <= size) {
    return null;
  }
  const first = page * size + 1;
  const last = Math.min(total, (page + 1) * size);
  return (
    <div className="flex items-center justify-between pt-3 text-sm text-muted-foreground">
      <span>
        {formatNumber(first)}–{formatNumber(last)} of {formatNumber(total)}
      </span>
      <div className="flex gap-2">
        <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
          Previous
        </Button>
        <Button variant="outline" size="sm" disabled={last >= total} onClick={() => onPage(page + 1)}>
          Next
        </Button>
      </div>
    </div>
  );
}
