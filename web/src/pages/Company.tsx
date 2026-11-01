import { useState } from "react";
import { Link, useParams } from "react-router";
import { useCompany, useCompanyEvents } from "@/api/hooks";
import type { Company as CompanyData, Party, Role } from "@/api/types";
import { Pager } from "@/components/Pager";
import { Empty, ErrorState, Loading } from "@/components/States";
import { StatusBadge } from "@/components/StatusBadge";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { summarize } from "@/lib/events";
import { formatDate, humanize } from "@/lib/format";

function PartyName({ party }: { party: Party }) {
  if (party.kind === "joint_owners") {
    return (
      <span>
        jointly:{" "}
        {party.owners.map((o, i) => (
          <span key={i}>
            {i > 0 && ", "}
            <PartyName party={o} />
          </span>
        ))}
      </span>
    );
  }
  if (party.regNumber) {
    return (
      <Link to={`/companies/${party.regNumber}`} className="hover:underline">
        {party.name}
      </Link>
    );
  }
  return <span>{party.name}</span>;
}

function RoleTable({ roles, columns }: { roles: Role[]; columns: "officers" | "owners" | "beneficial" }) {
  if (roles.length === 0) {
    return <Empty>None recorded.</Empty>;
  }
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Name</TableHead>
          {columns === "officers" && <TableHead>Position</TableHead>}
          {columns === "officers" && <TableHead>Body</TableHead>}
          {columns === "owners" && <TableHead>Shares</TableHead>}
          {columns === "owners" && <TableHead>Register</TableHead>}
          {columns === "beneficial" && <TableHead>Residence</TableHead>}
          {columns === "beneficial" && <TableHead>Nationality</TableHead>}
          <TableHead>Since</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {roles.map((role, i) => (
          <TableRow key={i}>
            <TableCell>
              <PartyName party={role.holder} />
              {role.holder.kind === "foreign_entity" && <Badge variant="outline" className="ml-2">foreign</Badge>}
            </TableCell>
            {columns === "officers" && <TableCell>{humanize(role.position)}</TableCell>}
            {columns === "officers" && <TableCell>{humanize(role.governingBody)}</TableCell>}
            {columns === "owners" && (
              <TableCell className="tabular-nums">
                {role.shares ?? "–"}
                {role.nominalValue && ` × ${role.nominalValue.amount} ${role.nominalValue.currency}`}
              </TableCell>
            )}
            {columns === "owners" && <TableCell>{humanize(role.shareRegister)}</TableCell>}
            {columns === "beneficial" && <TableCell>{role.residence ?? "–"}</TableCell>}
            {columns === "beneficial" && <TableCell>{role.nationalities.join(", ") || "–"}</TableCell>}
            <TableCell>{formatDate(role.since)}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function Facts({ company }: { company: CompanyData }) {
  const facts: [string, React.ReactNode][] = [
    ["Registration number", <span className="tabular-nums">{company.regNumber}</span>],
    ["Legal form", `${company.legalFormCode} (${humanize(company.legalFamily)})`],
    ["Registered", formatDate(company.registered)],
    ["Terminated", formatDate(company.terminated)],
    ["Address", company.address?.line ?? "–"],
    ["Capital", company.capital ? `${company.capital.amount} ${company.capital.currency}` : "–"],
    ["State as of capture", formatDate(company.asOf)],
  ];
  return (
    <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-[max-content_1fr]">
      {facts.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-muted-foreground">{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

function Timeline({ reg }: { reg: string }) {
  const [page, setPage] = useState(0);
  const events = useCompanyEvents(reg, page);
  if (events.isPending) return <Loading />;
  if (events.isError) return <ErrorState error={events.error} />;
  if (events.data.items.length === 0) return <Empty>No changes seen since the first capture.</Empty>;
  return (
    <>
      <ol className="space-y-3">
        {events.data.items.map((e) => (
          <li key={e.id} className="border-l-2 pl-3">
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <Badge variant="secondary">{humanize(e.type)}</Badge>
              <span className="text-muted-foreground">
                seen between {formatDate(e.previousCapture)} and {formatDate(e.capture)}
              </span>
            </div>
            <p className="mt-1 text-sm">{summarize(e)}</p>
          </li>
        ))}
      </ol>
      <Pager page={page} size={events.data.size} total={events.data.total} onPage={setPage} />
    </>
  );
}

export function Company() {
  const { reg = "" } = useParams();
  const company = useCompany(reg);
  if (company.isPending) return <Loading rows={6} />;
  if (company.isError) return <ErrorState error={company.error} />;
  const c = company.data;
  const officers = c.roles.filter((r) => r.kind === "officer");
  const owners = c.roles.filter((r) => r.kind === "shareholder");
  const beneficial = c.roles.filter((r) => r.kind === "beneficial_owner");

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="font-heading text-2xl font-semibold">{c.name}</h1>
          <div className="mt-1 flex items-center gap-2">
            <StatusBadge status={c.status} />
            <span className="text-sm text-muted-foreground tabular-nums">{c.regNumber}</span>
          </div>
        </div>
        <Link to={`/companies/${c.regNumber}/network`} className={buttonVariants({ variant: "outline" })}>
          Show network
        </Link>
      </div>
      <Card>
        <CardHeader>
          <CardTitle>Facts</CardTitle>
        </CardHeader>
        <CardContent>
          <Facts company={c} />
        </CardContent>
      </Card>
      <Tabs defaultValue="officers">
        <TabsList>
          <TabsTrigger value="officers">Officers ({officers.length})</TabsTrigger>
          <TabsTrigger value="owners">Shareholders ({owners.length})</TabsTrigger>
          <TabsTrigger value="beneficial">Beneficial owners ({beneficial.length})</TabsTrigger>
          <TabsTrigger value="events">Changes</TabsTrigger>
        </TabsList>
        <TabsContent value="officers">
          <RoleTable roles={officers} columns="officers" />
        </TabsContent>
        <TabsContent value="owners">
          <RoleTable roles={owners} columns="owners" />
        </TabsContent>
        <TabsContent value="beneficial">
          <RoleTable roles={beneficial} columns="beneficial" />
        </TabsContent>
        <TabsContent value="events" className="pt-2">
          <Timeline reg={c.regNumber} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
