import type { Network, NetworkNode } from "@/api/types";

export interface GraphNode {
  id: string;
  type: string;
  label: string;
  regNumber: string | null;
  status: string | null;
  depth: number;
  x?: number;
  y?: number;
  fx?: number | null;
  fy?: number | null;
}

export interface GraphLink {
  source: string | GraphNode;
  target: string | GraphNode;
  role: string;
  position: string | null;
}

/**
 * The API graph as d3-force data. Copies everything, because d3 mutates nodes and links;
 * merges parallel edges (the same party in the same company in several roles) into one link
 * with all roles.
 */
export function toGraph(network: Network): { nodes: GraphNode[]; links: GraphLink[] } {
  const nodes = network.nodes.map((n: NetworkNode) => ({ ...n }));
  const ids = new Set(nodes.map((n) => n.id));
  const links = new Map<string, GraphLink>();
  for (const e of network.edges) {
    if (!ids.has(e.from) || !ids.has(e.to)) {
      continue;
    }
    const key = `${e.from}→${e.to}`;
    const role = e.position ? `${e.role} (${e.position})` : e.role;
    const existing = links.get(key);
    if (existing) {
      if (!existing.role.split(", ").includes(role)) {
        existing.role = `${existing.role}, ${role}`;
      }
    } else {
      links.set(key, { source: e.from, target: e.to, role, position: e.position });
    }
  }
  return { nodes, links: [...links.values()] };
}

/** Radius by node type and depth: the root largest, companies larger than people. */
export function radius(node: Pick<GraphNode, "type" | "depth">): number {
  if (node.depth === 0) {
    return 14;
  }
  return node.type === "company" ? 9 : 6;
}

/** The role kinds of edges, in display order. */
export const ROLE_KINDS = ["officer", "shareholder", "beneficial_owner"] as const;
export type RoleKind = (typeof ROLE_KINDS)[number];

/**
 * Detail levels of the network page: 1 the company and its people, 2 plus their other
 * companies, 3 plus the people of those companies (everything the API returned).
 */
export const DETAIL_LEVELS = [
  { level: 1, maxDepth: 1, label: "Company and its people" },
  { level: 2, maxDepth: 2, label: "+ their other companies" },
  { level: 3, maxDepth: 3, label: "+ the people of those" },
] as const;

/**
 * A smaller view of the network: nodes up to {@code maxDepth}, edges of the chosen role kinds,
 * and only what is still connected to the root through the kept edges.
 */
export function filterNetwork(network: Network, maxDepth: number, roles: ReadonlySet<string>): Network {
  const candidates = new Set(network.nodes.filter((n) => n.depth <= maxDepth).map((n) => n.id));
  const edges = network.edges.filter((e) => roles.has(e.role) && candidates.has(e.from) && candidates.has(e.to));
  const neighbours = new Map<string, string[]>();
  for (const e of edges) {
    neighbours.set(e.from, [...(neighbours.get(e.from) ?? []), e.to]);
    neighbours.set(e.to, [...(neighbours.get(e.to) ?? []), e.from]);
  }
  const reached = new Set([network.root]);
  const queue = [network.root];
  while (queue.length > 0) {
    for (const next of neighbours.get(queue.shift()!) ?? []) {
      if (!reached.has(next)) {
        reached.add(next);
        queue.push(next);
      }
    }
  }
  return {
    root: network.root,
    truncated: network.truncated,
    nodes: network.nodes.filter((n) => reached.has(n.id)),
    edges: edges.filter((e) => reached.has(e.from) && reached.has(e.to)),
  };
}
