package lvregister.serving;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the two-hop {@link Network} through {@link ServingQueries} only: the company, its
 * parties, the other companies of those parties, and the parties of those companies. Bounded by
 * {@code maxCompanies} (other companies) and {@code maxNodes}, so a big holding cannot blow up
 * a request.
 */
public final class NetworkBuilder {

    /** Links read per request, before the company limit applies. */
    static final int MAX_LINKS = 2_000;

    private final ServingQueries queries;
    private final int maxCompanies;
    private final int maxNodes;

    public NetworkBuilder(ServingQueries queries, int maxCompanies, int maxNodes) {
        if (maxCompanies < 0 || maxNodes < 1) {
            throw new IllegalArgumentException("maxCompanies must be >= 0 and maxNodes >= 1");
        }
        this.queries = queries;
        this.maxCompanies = maxCompanies;
        this.maxNodes = maxNodes;
    }

    /** Empty when the company is not in the serving store. */
    public Optional<Network> build(String regNumber) {
        Optional<CompanyView> root = queries.company(regNumber);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        Graph graph = new Graph();
        graph.company(root.get(), 0);
        Set<String> linkable = graph.parties(root.get(), 1);

        List<PartyLink> links = queries.rolesOfParties(linkable, MAX_LINKS);
        if (links.size() == MAX_LINKS) {
            graph.truncated = true;
        }
        Set<String> others = new LinkedHashSet<>();
        for (PartyLink link : links) {
            if (!link.regNumber().equals(regNumber)) {
                others.add(link.regNumber());
            }
        }
        List<String> chosen = others.stream().limit(maxCompanies).toList();
        if (chosen.size() < others.size()) {
            graph.truncated = true;
        }
        List<CompanyView> companies = new ArrayList<>(queries.companies(chosen));
        companies.sort(java.util.Comparator.comparingInt(c -> chosen.indexOf(c.regNumber())));
        for (CompanyView company : companies) {
            graph.company(company, 2);
        }
        for (CompanyView company : companies) {
            graph.parties(company, 3);
        }
        return Optional.of(new Network(PartyLink.companyKey(regNumber), List.copyOf(graph.nodes.values()),
                List.copyOf(graph.edges), graph.truncated));
    }

    private final class Graph {
        final Map<String, Network.Node> nodes = new LinkedHashMap<>();
        final Set<Network.Edge> edges = new LinkedHashSet<>();
        boolean truncated;

        void company(CompanyView c, int depth) {
            String id = PartyLink.companyKey(c.regNumber());
            Network.Node existing = nodes.get(id);
            Network.Node node = new Network.Node(id, "company", c.name(), Optional.of(c.regNumber()), Optional.of(c.status()),
                    existing == null ? depth : Math.min(depth, existing.depth()));
            if (existing != null || add(id)) {
                nodes.put(id, node);
            }
        }

        /** Adds the parties of a company with their edges; returns the keys that can be followed. */
        Set<String> parties(CompanyView c, int depth) {
            Set<String> linkable = new LinkedHashSet<>();
            String to = PartyLink.companyKey(c.regNumber());
            if (!nodes.containsKey(to)) {
                return linkable;
            }
            int unlinked = 0;
            for (CompanyView.RoleView role : c.roles()) {
                List<CompanyView.PartyView> holders = role.holder().kind().equals("joint_owners")
                        ? role.holder().owners() : List.of(role.holder());
                for (CompanyView.PartyView p : holders) {
                    String id;
                    String type;
                    if (p.personKey().isPresent()) {
                        id = PartyLink.personKey(p.personKey().get());
                        type = "person";
                        linkable.add(id);
                    } else if (p.regNumber().isPresent()) {
                        id = PartyLink.companyKey(p.regNumber().get());
                        type = "company";
                        linkable.add(id);
                    } else {
                        id = "party:" + c.regNumber() + ":" + unlinked++;
                        type = p.kind().equals("natural_person") ? "person" : "entity";
                    }
                    if (!nodes.containsKey(id)) {
                        if (!add(id)) {
                            continue;
                        }
                        nodes.put(id, new Network.Node(id, type, p.name(), p.regNumber(), Optional.empty(), depth));
                    }
                    edges.add(new Network.Edge(id, to, role.kind(), role.position()));
                }
            }
            return linkable;
        }

        private boolean add(String id) {
            if (nodes.size() >= maxNodes) {
                truncated = true;
                return false;
            }
            return true;
        }
    }
}
