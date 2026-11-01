import { useState } from "react";
import { Link, useParams } from "react-router";
import { DAGSTER_URL } from "@/api/client";
import { useRun, useRuns } from "@/api/hooks";
import { Pager } from "@/components/Pager";
import { Empty, ErrorState, Loading } from "@/components/States";
import { StatusBadge } from "@/components/StatusBadge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatBytes, formatDate, formatNumber, humanize } from "@/lib/format";

export function Pipeline() {
  const [page, setPage] = useState(0);
  const runs = useRuns(page);
  return (
    <div className="space-y-4">
      <div>
        <h1 className="font-heading text-2xl font-semibold">Pipeline runs</h1>
        <p className="text-sm text-muted-foreground">One quality report per capture: what was read, kept and dropped.</p>
      </div>
      {runs.isPending ? (
        <Loading />
      ) : runs.isError ? (
        <ErrorState error={runs.error} />
      ) : runs.data.items.length === 0 ? (
        <Empty>No runs yet.</Empty>
      ) : (
        <>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Capture</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Run</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {runs.data.items.map((r) => (
                <TableRow key={r.runId}>
                  <TableCell>{formatDate(r.captureDate)}</TableCell>
                  <TableCell>
                    <StatusBadge status={r.status} />
                  </TableCell>
                  <TableCell>
                    <Link to={`/pipeline/${r.runId}`} className="font-mono text-xs hover:underline">
                      {r.runId}
                    </Link>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Pager page={page} size={runs.data.size} total={runs.data.total} onPage={setPage} />
        </>
      )}
    </div>
  );
}

interface FileReport {
  file: string;
  rowsRead: number;
  rowsReadPrevious: number | null;
  rowsKept: number;
  sizeBytes: number;
  droppedByRule: Record<string, number>;
}

interface Report {
  status: string;
  captureDate: string;
  previousCapture: string | null;
  daysSincePrevious: number | null;
  dagsterRunId: string | null;
  errors: string[];
  warnings: { code: string; message: string }[];
  files: FileReport[];
  snapshot: Record<string, number>;
  eventsByType: Record<string, number>;
  bucketBytes: Record<string, number>;
}

function sum(values: Record<string, number>): number {
  return Object.values(values).reduce((a, b) => a + b, 0);
}

export function RunDetail() {
  const { id = "" } = useParams();
  const run = useRun(id);
  if (run.isPending) return <Loading rows={6} />;
  if (run.isError) return <ErrorState error={run.error} />;
  const report = run.data.report as unknown as Report;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="font-heading text-2xl font-semibold">Capture {formatDate(report.captureDate)}</h1>
          <p className="text-sm text-muted-foreground">
            {report.previousCapture
              ? `Compared with ${formatDate(report.previousCapture)} (${report.daysSincePrevious} days earlier)`
              : "Baseline: the first capture"}
          </p>
        </div>
        <div className="flex items-center gap-3 text-sm">
          <StatusBadge status={report.status} />
          {report.dagsterRunId && (
            <a href={`${DAGSTER_URL}/runs/${report.dagsterRunId}`} target="_blank" rel="noreferrer" className="hover:underline">
              Dagster run ↗
            </a>
          )}
        </div>
      </div>
      {report.errors.length > 0 && (
        <Alert variant="destructive">
          <AlertTitle>Errors</AlertTitle>
          <AlertDescription>{report.errors.join("; ")}</AlertDescription>
        </Alert>
      )}
      {report.warnings.length > 0 && (
        <Alert>
          <AlertTitle>{report.warnings.length} warnings</AlertTitle>
          <AlertDescription>
            <ul className="list-disc pl-4">
              {report.warnings.map((w, i) => (
                <li key={i}>
                  <span className="font-mono text-xs">{w.code}</span>: {w.message}
                </li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
      <Card>
        <CardHeader>
          <CardTitle>Files</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>File</TableHead>
                <TableHead className="text-right">Size</TableHead>
                <TableHead className="text-right">Rows read</TableHead>
                <TableHead className="text-right">Previous</TableHead>
                <TableHead className="text-right">Kept</TableHead>
                <TableHead className="text-right">Dropped</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {report.files.map((f) => (
                <TableRow key={f.file}>
                  <TableCell className="font-mono text-xs">{f.file}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatBytes(f.sizeBytes)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatNumber(f.rowsRead)}</TableCell>
                  <TableCell className="text-right tabular-nums">
                    {f.rowsReadPrevious === null ? "–" : formatNumber(f.rowsReadPrevious)}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{formatNumber(f.rowsKept)}</TableCell>
                  <TableCell
                    className="text-right tabular-nums"
                    title={Object.entries(f.droppedByRule).map(([rule, n]) => `${rule}: ${n}`).join("\n")}
                  >
                    {formatNumber(sum(f.droppedByRule))}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <div className="grid gap-4 md:grid-cols-3">
        <Card>
          <CardHeader>
            <CardTitle>Snapshot</CardTitle>
          </CardHeader>
          <CardContent>
            <Figures values={report.snapshot} />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Events</CardTitle>
          </CardHeader>
          <CardContent>
            {Object.keys(report.eventsByType).length === 0 ? <Empty>None.</Empty> : <Figures values={report.eventsByType} />}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Storage</CardTitle>
          </CardHeader>
          <CardContent>
            <Figures values={report.bucketBytes} format={formatBytes} />
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function Figures({ values, format = formatNumber }: { values: Record<string, number>; format?: (n: number) => string }) {
  return (
    <dl className="space-y-1 text-sm">
      {Object.entries(values).map(([key, value]) => (
        <div key={key} className="flex justify-between gap-3">
          <dt className="text-muted-foreground">{humanize(key.replace(/([a-z])([A-Z])/g, "$1_$2"))}</dt>
          <dd className="tabular-nums">{format(value)}</dd>
        </div>
      ))}
    </dl>
  );
}
