import { Link, useSearchParams } from "react-router";
import { useEvents } from "@/api/hooks";
import { Pager } from "@/components/Pager";
import { Empty, ErrorState, Loading } from "@/components/States";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EVENT_TYPES, summarize } from "@/lib/events";
import { formatDate, formatNumber, humanize } from "@/lib/format";

const ALL = "all";

export function Changes() {
  const [params, setParams] = useSearchParams();
  const date = params.get("date") ?? undefined;
  const type = params.get("type") ?? undefined;
  const page = Number(params.get("page") ?? "0");
  const events = useEvents(date, type, page);

  const update = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    if (!("page" in changes)) next.delete("page");
    setParams(next);
  };

  return (
    <div className="space-y-4">
      <div>
        <h1 className="font-heading text-2xl font-semibold">Changes</h1>
        <p className="text-sm text-muted-foreground">Every change seen between two captures, newest first.</p>
      </div>
      <div className="flex flex-wrap gap-3">
        <Input
          type="date"
          className="w-44"
          value={date ?? ""}
          aria-label="Capture date"
          onChange={(e) => update({ date: e.target.value || undefined })}
        />
        <Select value={type ?? ALL} onValueChange={(v) => update({ type: v === ALL ? undefined : v })}>
          <SelectTrigger className="w-56" aria-label="Event type">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>All types</SelectItem>
            {EVENT_TYPES.map((t) => (
              <SelectItem key={t} value={t}>
                {humanize(t)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      {events.isPending ? (
        <Loading rows={8} />
      ) : events.isError ? (
        <ErrorState error={events.error} />
      ) : events.data.items.length === 0 ? (
        <Empty>No changes for this filter.</Empty>
      ) : (
        <>
          <p className="text-sm text-muted-foreground">{formatNumber(events.data.total)} changes</p>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Seen</TableHead>
                <TableHead>Company</TableHead>
                <TableHead>Change</TableHead>
                <TableHead>What</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {events.data.items.map((e) => (
                <TableRow key={e.id}>
                  <TableCell className="whitespace-nowrap">{formatDate(e.capture)}</TableCell>
                  <TableCell>
                    <Link to={`/companies/${e.regNumber}`} className="tabular-nums hover:underline">
                      {e.regNumber}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Badge variant="secondary">{humanize(e.type)}</Badge>
                  </TableCell>
                  <TableCell className="max-w-md truncate" title={summarize(e)}>
                    {summarize(e)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Pager page={page} size={events.data.size} total={events.data.total} onPage={(p) => update({ page: String(p) })} />
        </>
      )}
    </div>
  );
}
