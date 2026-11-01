import { Link, useSearchParams } from "react-router";
import { useSearch } from "@/api/hooks";
import { Pager } from "@/components/Pager";
import { Empty, ErrorState, Loading } from "@/components/States";
import { StatusBadge } from "@/components/StatusBadge";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatNumber } from "@/lib/format";
import { useDebounced } from "@/lib/useDebounced";

export function Search() {
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const page = Number(params.get("page") ?? "0");
  const query = useDebounced(q.trim(), 300);
  const search = useSearch(query, page);

  return (
    <div className="space-y-4">
      <h1 className="font-heading text-2xl font-semibold">Search companies</h1>
      <Input
        autoFocus
        type="search"
        value={q}
        placeholder="Name or registration number, e.g. rigas udens or 40003000001"
        aria-label="Company name or registration number"
        onChange={(e) => setParams(e.target.value ? { q: e.target.value } : {}, { replace: true })}
      />
      <p className="text-xs text-muted-foreground">Case, Latvian diacritics and small typos are ignored.</p>
      {query.length < 2 ? (
        <Empty>Type at least two characters.</Empty>
      ) : search.isPending ? (
        <Loading rows={8} />
      ) : search.isError ? (
        <ErrorState error={search.error} />
      ) : search.data.items.length === 0 ? (
        <Empty>No company matches “{query}”.</Empty>
      ) : (
        <>
          <p className="text-sm text-muted-foreground">{formatNumber(search.data.total)} matches</p>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Registration number</TableHead>
                <TableHead>Form</TableHead>
                <TableHead>Status</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {search.data.items.map((hit) => (
                <TableRow key={hit.regNumber}>
                  <TableCell>
                    <Link to={`/companies/${hit.regNumber}`} className="font-medium hover:underline">
                      {hit.name}
                    </Link>
                  </TableCell>
                  <TableCell className="tabular-nums">{hit.regNumber}</TableCell>
                  <TableCell>{hit.legalFormCode}</TableCell>
                  <TableCell>
                    <StatusBadge status={hit.status} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Pager
            page={page}
            size={search.data.size}
            total={search.data.total}
            onPage={(p) => setParams({ q, page: String(p) })}
          />
        </>
      )}
    </div>
  );
}
