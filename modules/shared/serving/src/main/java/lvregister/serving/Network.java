package lvregister.serving;

import java.util.List;
import java.util.Optional;

/**
 * The company–person graph around one company (API {@code /companies/{reg}/network}). Edges
 * point from a party (person or entity) to a company in which it holds a role.
 *
 * @param truncated true when a limit cut nodes or companies off
 */
public record Network(String root, List<Node> nodes, List<Edge> edges, boolean truncated) {

    public Network {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /**
     * @param id     {@code company:<reg>}, {@code person:<key>}, or {@code party:<reg>:<n>} for a
     *               party that cannot be linked (foreign entity, person without key)
     * @param type   {@code company}, {@code person} or {@code entity}
     * @param depth  0 for the root, 1 its parties, 2 their other companies, 3 the parties of those
     */
    public record Node(String id, String type, String label, Optional<String> regNumber, Optional<String> status, int depth) {}

    public record Edge(String from, String to, String role, Optional<String> position) {}
}
