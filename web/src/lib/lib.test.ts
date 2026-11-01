import { describe, expect, it } from "vitest";
import { summarize } from "./events";
import { formatBytes, formatDate, formatNumber, humanize, isRegNumber } from "./format";
import { filterNetwork, radius, ROLE_KINDS, toGraph } from "./network";
import { eventsPerCapture, sumBy, total } from "./stats";

describe("format", () => {
  it("formats numbers, dates, bytes and codes", () => {
    expect(formatNumber(489963)).toBe("489,963");
    expect(formatDate("2026-11-09")).toBe("9 Nov 2026");
    expect(formatDate(null)).toBe("–");
    expect(formatBytes(591_810_056)).toBe("564.4 MB");
    expect(humanize("MANAGEMENT_CHANGED")).toBe("Management changed");
    expect(humanize("LLC")).toBe("LLC");
    expect(isRegNumber("40003000001")).toBe(true);
    expect(isRegNumber("010000001")).toBe(true);
    expect(isRegNumber("4000300000")).toBe(false);
  });
});

describe("summarize", () => {
  it("describes value changes and role changes", () => {
    expect(summarize({ type: "NAME_CHANGED", payload: { old: "SIA \"A\"", new: "SIA \"B\"" } })).toBe('SIA "A" → SIA "B"');
    expect(summarize({ type: "LEGAL_FORM_CHANGED", payload: { old: { code: "SIA" }, new: { code: "AS" } } })).toBe("SIA → AS");
    expect(
      summarize({
        type: "MANAGEMENT_CHANGED",
        payload: {
          added: [{ kind: "officer", position: "BOARD_MEMBER", holder: { name: "Test Person" } }],
          removed: [{ kind: "officer", holder: { name: "Other Person" } }],
        },
      }),
    ).toBe("+ Test Person (board member); − Other Person");
    expect(summarize({ type: "MISSING_FROM_SNAPSHOT", payload: {} })).toBe("no longer in the register file");
  });
});

describe("stats", () => {
  const rows = [
    { dimensions: { legal_family: "LLC", status: "ACTIVE" }, value: 5 },
    { dimensions: { legal_family: "LLC", status: "TERMINATED" }, value: 3 },
    { dimensions: { legal_family: "ASSOCIATION", status: "ACTIVE" }, value: 2 },
    { dimensions: { legal_family: "OTHER", status: "ACTIVE" }, value: 1 },
  ];

  it("sums by a dimension, largest first, with the rest as Other", () => {
    expect(sumBy(rows, "legal_family")).toEqual([
      { key: "LLC", value: 8 },
      { key: "ASSOCIATION", value: 2 },
      { key: "OTHER", value: 1 },
    ]);
    expect(sumBy(rows, "legal_family", 1)).toEqual([
      { key: "LLC", value: 8 },
      { key: "Other", value: 3 },
    ]);
    expect(total(rows)).toBe(11);
  });

  it("pivots events per capture by type", () => {
    const result = eventsPerCapture([
      { dimensions: { capture_date: "2026-11-09", event_type: "NAME_CHANGED" }, value: 2 },
      { dimensions: { capture_date: "2026-11-02", event_type: "REGISTERED" }, value: 7 },
      { dimensions: { capture_date: "2026-11-09", event_type: "REGISTERED" }, value: 1 },
    ]);
    expect(result.types).toEqual(["NAME_CHANGED", "REGISTERED"]);
    expect(result.data).toEqual([
      { capture: "2026-11-02", REGISTERED: 7 },
      { capture: "2026-11-09", NAME_CHANGED: 2, REGISTERED: 1 },
    ]);
  });
});

describe("network", () => {
  it("copies nodes, merges parallel edges and drops dangling ones", () => {
    const api = {
      root: "company:1",
      truncated: false,
      nodes: [
        { id: "company:1", type: "company", label: "A", regNumber: "1", status: "ACTIVE", depth: 0 },
        { id: "person:p", type: "person", label: "P", regNumber: null, status: null, depth: 1 },
      ],
      edges: [
        { from: "person:p", to: "company:1", role: "officer", position: "BOARD_MEMBER" },
        { from: "person:p", to: "company:1", role: "beneficial_owner", position: null },
        { from: "person:p", to: "company:9", role: "officer", position: null },
      ],
    };
    const graph = toGraph(api);
    expect(graph.links).toEqual([
      { source: "person:p", target: "company:1", role: "officer (BOARD_MEMBER), beneficial_owner", position: "BOARD_MEMBER" },
    ]);
    graph.nodes[0]!.x = 5;
    expect(api.nodes[0]).not.toHaveProperty("x");
    expect(radius({ type: "company", depth: 0 })).toBeGreaterThan(radius({ type: "company", depth: 2 }));
  });
});

describe("filterNetwork", () => {
  const network = {
    root: "company:1",
    truncated: true,
    nodes: [
      { id: "company:1", type: "company", label: "Root", regNumber: "1", status: "ACTIVE", depth: 0 },
      { id: "person:a", type: "person", label: "A", regNumber: null, status: null, depth: 1 },
      { id: "person:b", type: "person", label: "B", regNumber: null, status: null, depth: 1 },
      { id: "company:2", type: "company", label: "Other", regNumber: "2", status: "ACTIVE", depth: 2 },
      { id: "person:c", type: "person", label: "C", regNumber: null, status: null, depth: 3 },
    ],
    edges: [
      { from: "person:a", to: "company:1", role: "officer", position: "BOARD_MEMBER" },
      { from: "person:b", to: "company:1", role: "beneficial_owner", position: null },
      { from: "person:b", to: "company:2", role: "shareholder", position: null },
      { from: "person:c", to: "company:2", role: "officer", position: null },
    ],
  };
  const all = new Set(ROLE_KINDS);

  it("cuts by detail level", () => {
    expect(filterNetwork(network, 1, all).nodes.map((n) => n.id)).toEqual(["company:1", "person:a", "person:b"]);
    expect(filterNetwork(network, 2, all).nodes.map((n) => n.id)).toEqual(["company:1", "person:a", "person:b", "company:2"]);
    expect(filterNetwork(network, 3, all).nodes).toHaveLength(5);
  });

  it("drops role kinds and whatever is no longer connected to the company", () => {
    const officers = filterNetwork(network, 3, new Set(["officer"]));
    expect(officers.nodes.map((n) => n.id)).toEqual(["company:1", "person:a"]);
    expect(officers.edges).toHaveLength(1);
    expect(officers.truncated).toBe(true);
  });
});
