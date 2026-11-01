import * as d3 from "d3";
import { useEffect, useRef } from "react";
import type { Network } from "@/api/types";
import { type GraphLink, type GraphNode, radius, toGraph } from "@/lib/network";

interface Props {
  network: Network;
  onOpenCompany: (regNumber: string) => void;
  height?: number;
}

const COLORS: Record<string, string> = {
  company: "var(--color-chart-1)",
  person: "var(--color-chart-2)",
  entity: "var(--color-chart-4)",
};

/**
 * The company–person graph drawn with d3-force: drag nodes, zoom and pan, hover for names
 * and roles, click a company to open it. The root company is fixed in the centre.
 */
export function NetworkGraph({ network, onOpenCompany, height = 560 }: Props) {
  const svgRef = useRef<SVGSVGElement>(null);

  useEffect(() => {
    const svgElement = svgRef.current;
    if (!svgElement) {
      return;
    }
    const width = svgElement.clientWidth || 900;
    const { nodes, links } = toGraph(network);
    const root = nodes.find((n) => n.id === network.root);
    if (root) {
      root.fx = width / 2;
      root.fy = height / 2;
    }

    const svg = d3.select(svgElement).attr("viewBox", `0 0 ${width} ${height}`);
    svg.selectAll("*").remove();
    const canvas = svg.append("g");
    const zoom = d3
      .zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.2, 4])
      .on("zoom", (event) => canvas.attr("transform", event.transform.toString()));
    svg.call(zoom);
    let fitted = false;
    // Zoom so every node is in view, small graphs up to 2.5x (once; not again after dragging).
    const fit = () => {
      if (fitted || nodes.length === 0) return;
      fitted = true;
      const [x0, x1] = d3.extent(nodes, (n) => n.x ?? 0) as [number, number];
      const [y0, y1] = d3.extent(nodes, (n) => n.y ?? 0) as [number, number];
      const scale = Math.min(2.5, 0.9 * Math.min(width / (x1 - x0 + 160), height / (y1 - y0 + 40)));
      svg
        .transition()
        .duration(0)
        .call(zoom.transform, d3.zoomIdentity.translate(width / 2, height / 2).scale(scale).translate(-(x0 + x1) / 2, -(y0 + y1) / 2));
    };

    const link = canvas
      .append("g")
      .attr("stroke", "var(--color-border)")
      .attr("stroke-width", 1.2)
      .selectAll<SVGLineElement, GraphLink>("line")
      .data(links)
      .join("line");
    link.append("title").text((l) => l.role.replaceAll("_", " "));

    const node = canvas
      .append("g")
      .selectAll<SVGGElement, GraphNode>("g")
      .data(nodes)
      .join("g")
      .style("cursor", (n) => (n.type === "company" && n.regNumber ? "pointer" : "grab"))
      .on("click", (_, n) => {
        if (n.type === "company" && n.regNumber && n.id !== network.root) {
          onOpenCompany(n.regNumber);
        }
      });
    node
      .append("circle")
      .attr("r", radius)
      .attr("fill", (n) => COLORS[n.type] ?? "var(--color-muted-foreground)")
      .attr("stroke", "var(--color-background)")
      .attr("stroke-width", 1.5)
      .attr("opacity", (n) => (n.status === "TERMINATED" || n.status === "REORGANIZED" ? 0.45 : 1));
    node
      .append("text")
      .text((n) => (n.label.length > 28 ? `${n.label.slice(0, 27)}…` : n.label))
      .attr("x", (n) => radius(n) + 3)
      .attr("y", 4)
      .attr("font-size", (n) => (n.depth <= 1 ? 11 : 9))
      .attr("fill", "var(--color-foreground)")
      .attr("opacity", (n) => (n.depth <= 2 ? 1 : 0.7));
    node.append("title").text((n) => [n.label, n.regNumber, n.status].filter(Boolean).join(" · "));

    const draw = () => {
      link
        .attr("x1", (l) => (l.source as GraphNode).x ?? 0)
        .attr("y1", (l) => (l.source as GraphNode).y ?? 0)
        .attr("x2", (l) => (l.target as GraphNode).x ?? 0)
        .attr("y2", (l) => (l.target as GraphNode).y ?? 0);
      node.attr("transform", (n) => `translate(${n.x ?? 0},${n.y ?? 0})`);
    };
    // Lay the graph out up front (a few ms for a few hundred nodes), draw it once and zoom to
    // fit; the simulation only runs again while a node is dragged. Saves CPU on the 1-CPU VM.
    const simulation = d3
      .forceSimulation<GraphNode>(nodes)
      .force("link", d3.forceLink<GraphNode, GraphLink>(links).id((n) => n.id).distance(70))
      .force("charge", d3.forceManyBody().strength(-180))
      .force("collide", d3.forceCollide<GraphNode>().radius((n) => radius(n) + 6))
      .force("x", d3.forceX(width / 2).strength(0.04))
      .force("y", d3.forceY(height / 2).strength(0.04))
      .stop();
    simulation.tick(300);
    draw();
    fit();
    simulation.on("tick", draw);

    node.call(
      d3
        .drag<SVGGElement, GraphNode>()
        .on("start", (event, n) => {
          if (!event.active) simulation.alphaTarget(0.3).restart();
          n.fx = n.x;
          n.fy = n.y;
        })
        .on("drag", (event, n) => {
          n.fx = event.x;
          n.fy = event.y;
        })
        .on("end", (event, n) => {
          if (!event.active) simulation.alphaTarget(0);
          if (n.id !== network.root) {
            n.fx = null;
            n.fy = null;
          }
        }),
    );

    return () => {
      simulation.stop();
    };
  }, [network, onOpenCompany, height]);

  return (
    <svg
      ref={svgRef}
      className="w-full rounded-lg border bg-card"
      style={{ height }}
      role="img"
      aria-label={`Network graph with ${network.nodes.length} nodes`}
    />
  );
}

export function NetworkLegend() {
  return (
    <div className="flex flex-wrap gap-4 text-xs text-muted-foreground">
      {Object.entries(COLORS).map(([type, color]) => (
        <span key={type} className="flex items-center gap-1.5">
          <span className="inline-block size-2.5 rounded-full" style={{ background: color }} />
          {type}
        </span>
      ))}
      <span>Faded: closed company · Drag nodes, scroll to zoom, click a company to open it</span>
    </div>
  );
}
