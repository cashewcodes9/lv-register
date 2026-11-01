import { useState } from "react";
import { Link } from "react-router";
import { useMassAddresses } from "@/api/hooks";
import { Pager } from "@/components/Pager";
import { Empty, ErrorState, Loading } from "@/components/States";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatNumber } from "@/lib/format";

interface MassAddressRow {
  address_line?: string;
  postal_code?: string;
  active_companies?: number;
  llcs?: number;
  registered_last_year?: number;
  latest_reg_numbers?: string[];
}

export function Insights() {
  const [page, setPage] = useState(0);
  const addresses = useMassAddresses(page);
  return (
    <div className="space-y-4">
      <div>
        <h1 className="font-heading text-2xl font-semibold">Mass-registration addresses</h1>
        <p className="text-sm text-muted-foreground">
          Addresses where many active companies are registered: often virtual offices or formation agents.
        </p>
      </div>
      {addresses.isPending ? (
        <Loading rows={8} />
      ) : addresses.isError ? (
        <ErrorState error={addresses.error} />
      ) : addresses.data.items.length === 0 ? (
        <Empty>The dbt marts have not been built yet.</Empty>
      ) : (
        <>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>#</TableHead>
                <TableHead>Address</TableHead>
                <TableHead className="text-right">Active companies</TableHead>
                <TableHead className="text-right">Of them LLCs</TableHead>
                <TableHead className="text-right">Registered last year</TableHead>
                <TableHead>Latest</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {addresses.data.items.map((a) => {
                const row = a.data as MassAddressRow;
                return (
                  <TableRow key={a.vzdId}>
                    <TableCell className="tabular-nums">{a.rank}</TableCell>
                    <TableCell>
                      {row.address_line ?? "–"}
                      {row.postal_code && <span className="text-muted-foreground">, {row.postal_code}</span>}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{formatNumber(row.active_companies ?? 0)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatNumber(row.llcs ?? 0)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatNumber(row.registered_last_year ?? 0)}</TableCell>
                    <TableCell className="space-x-2">
                      {(row.latest_reg_numbers ?? []).slice(0, 3).map((reg) => (
                        <Link key={reg} to={`/companies/${reg}`} className="text-xs tabular-nums hover:underline">
                          {reg}
                        </Link>
                      ))}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
          <Pager page={page} size={addresses.data.size} total={addresses.data.total} onPage={setPage} />
        </>
      )}
    </div>
  );
}
