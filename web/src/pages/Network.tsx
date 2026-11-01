import { useCallback, useMemo } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { useCompany, useNetwork } from "@/api/hooks";
import { NetworkGraph, NetworkLegend } from "@/components/NetworkGraph";
import { ErrorState, Loading } from "@/components/States";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { formatNumber, humanize } from "@/lib/format";
import { DETAIL_LEVELS, filterNetwork, ROLE_KINDS } from "@/lib/network";

/**
 * Starts small (the company and its people) and grows on demand: detail level 2 adds the
 * other companies of those people, level 3 the people of those companies. Role toggles hide
 * officers, shareholders or beneficial owners. All filtering is local: one API call.
 */
export function Network() {
  const { reg = "" } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const company = useCompany(reg);
  const network = useNetwork(reg);

  const level = DETAIL_LEVELS.find((d) => String(d.level) === params.get("level")) ?? DETAIL_LEVELS[0];
  const roles = useMemo(() => {
    const chosen = params.get("roles");
    return new Set<string>(chosen === null ? ROLE_KINDS : chosen.split(",").filter(Boolean));
  }, [params]);
  const shown = useMemo(
    () => (network.data ? filterNetwork(network.data, level.maxDepth, roles) : undefined),
    [network.data, level.maxDepth, roles],
  );
  // Opening another company keeps the chosen level and roles.
  const open = useCallback(
    (r: string) => navigate({ pathname: `/companies/${r}/network`, search: params.toString() }),
    [navigate, params],
  );

  const update = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value === null) next.delete(key);
    else next.set(key, value);
    setParams(next, { replace: true });
  };
  const toggleRole = (role: string) => {
    const next = new Set(roles);
    if (next.has(role)) next.delete(role);
    else next.add(role);
    update("roles", next.size === ROLE_KINDS.length ? null : [...next].join(","));
  };

  if (network.isPending) return <Loading rows={8} />;
  if (network.isError) return <ErrorState error={network.error} />;
  const graph = shown!;
  const companies = graph.nodes.filter((n) => n.type === "company").length;

  return (
    <div className="space-y-4">
      <div>
        <h1 className="font-heading text-2xl font-semibold">
          Network of{" "}
          <Link to={`/companies/${reg}`} className="hover:underline">
            {company.data?.name ?? reg}
          </Link>
        </h1>
        <p className="text-sm text-muted-foreground">
          Showing {formatNumber(companies)} companies and {formatNumber(graph.nodes.length - companies)} people and
          entities, of {formatNumber(network.data.nodes.length)} nodes in the full network.
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Tabs value={String(level.level)} onValueChange={(v) => update("level", v === "1" ? null : v)}>
          <TabsList aria-label="Detail level">
            {DETAIL_LEVELS.map((d) => (
              <TabsTrigger key={d.level} value={String(d.level)}>
                {d.label}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
        <div className="flex flex-wrap gap-2" role="group" aria-label="Roles">
          {ROLE_KINDS.map((role) => (
            <Button
              key={role}
              size="sm"
              variant={roles.has(role) ? "default" : "outline"}
              aria-pressed={roles.has(role)}
              onClick={() => toggleRole(role)}
            >
              {humanize(role)}s
            </Button>
          ))}
        </div>
      </div>
      {network.data.truncated && level.level > 1 && (
        <Alert>
          <AlertDescription>The full network is large; it was cut at the limits of the API (30 companies, 300 nodes).</AlertDescription>
        </Alert>
      )}
      <NetworkGraph key={`${level.level}-${[...roles].sort().join(",")}`} network={graph} onOpenCompany={open} />
      <NetworkLegend />
    </div>
  );
}
