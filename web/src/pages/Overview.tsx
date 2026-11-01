import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from "recharts";
import { useStats } from "@/api/hooks";
import { ErrorState, Loading } from "@/components/States";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { type ChartConfig, ChartContainer, ChartLegend, ChartLegendContent, ChartTooltip, ChartTooltipContent } from "@/components/ui/chart";
import { formatDate, formatNumber, humanize } from "@/lib/format";
import { eventsPerCapture, sumBy, total } from "@/lib/stats";

const countConfig = { value: { label: "Companies", color: "var(--chart-1)" } } satisfies ChartConfig;

function Figure({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Card>
      <CardHeader>
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl tabular-nums">{value}</CardTitle>
        {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
      </CardHeader>
    </Card>
  );
}

function HorizontalBars({ title, description, data, label = humanize }: {
  title: string;
  description: string;
  data: { key: string; value: number }[];
  label?: (key: string) => string;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>
        <ChartContainer config={countConfig} className="aspect-auto w-full" style={{ height: 28 * data.length + 20 }}>
          <BarChart data={data.map((d) => ({ ...d, label: label(d.key) }))} layout="vertical" margin={{ left: 8, right: 16 }}>
            <CartesianGrid horizontal={false} />
            <YAxis dataKey="label" type="category" width={130} tickLine={false} axisLine={false} />
            <XAxis type="number" tickFormatter={(v: number) => formatNumber(v)} />
            <ChartTooltip content={<ChartTooltipContent />} />
            <Bar dataKey="value" fill="var(--color-value)" radius={3} />
          </BarChart>
        </ChartContainer>
      </CardContent>
    </Card>
  );
}

export function Overview() {
  const stats = useStats();
  if (stats.isPending) return <Loading rows={6} />;
  if (stats.isError) return <ErrorState error={stats.error} />;

  const companies = stats.data.companies;
  const active = companies.filter((r) => r.dimensions.status === "ACTIVE");
  const events = eventsPerCapture(stats.data.eventsPerCapture);
  const latest = events.data.at(-1);
  const eventsConfig = Object.fromEntries(
    events.types.map((type, i) => [type, { label: humanize(type), color: `var(--chart-${(i % 5) + 1})` }]),
  ) satisfies ChartConfig;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-heading text-2xl font-semibold">Latvian companies, week by week</h1>
        <p className="text-sm text-muted-foreground">
          Every registered entity from the open register, and what changed between weekly captures.
        </p>
      </div>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Figure label="Entities in the register" value={formatNumber(total(companies))} />
        <Figure label="Active" value={formatNumber(total(active))} />
        <Figure
          label="Events in the latest capture"
          value={latest ? formatNumber(events.types.reduce((s, t) => s + ((latest[t] as number | undefined) ?? 0), 0)) : "–"}
          hint={latest ? formatDate(latest.capture) : undefined}
        />
        <Figure label="Captures with a diff" value={formatNumber(events.data.length)} />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <HorizontalBars title="By legal family" description="All entities" data={sumBy(companies, "legal_family")} />
        <HorizontalBars title="By status" description="All entities" data={sumBy(companies, "status")} />
        <HorizontalBars title="Top legal forms" description="Active entities, the 10 most common legal form codes" data={sumBy(active, "legal_form_code", 10)} label={(k) => k} />
        <Card>
          <CardHeader>
            <CardTitle>Events per capture</CardTitle>
            <CardDescription>Changes seen against the previous capture, by type</CardDescription>
          </CardHeader>
          <CardContent>
            <ChartContainer config={eventsConfig} className="aspect-auto h-72 w-full">
              <BarChart data={events.data.map((d) => ({ ...d, label: formatDate(d.capture) }))}>
                <CartesianGrid vertical={false} />
                <XAxis dataKey="label" tickLine={false} />
                <YAxis tickFormatter={(v: number) => formatNumber(v)} />
                <ChartTooltip content={<ChartTooltipContent />} />
                <ChartLegend content={<ChartLegendContent />} />
                {events.types.map((type) => (
                  <Bar key={type} dataKey={type} stackId="events" fill={`var(--color-${type})`} />
                ))}
              </BarChart>
            </ChartContainer>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
