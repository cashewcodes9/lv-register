package lvregister.serving;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NetworkBuilderTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 9);

    /** In-memory queries: just what the builder uses. */
    private static final class Store implements ServingQueries {
        final Map<String, CompanyView> companies = new LinkedHashMap<>();

        Store add(CompanyView c) {
            companies.put(c.regNumber(), c);
            return this;
        }

        @Override public Optional<CompanyView> company(String reg) { return Optional.ofNullable(companies.get(reg)); }

        @Override public List<CompanyView> companies(Collection<String> regs) {
            return regs.stream().map(companies::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override public List<PartyLink> rolesOfParties(Collection<String> keys, int limit) {
            List<PartyLink> links = new ArrayList<>();
            for (CompanyView c : companies.values()) {
                for (CompanyView.RoleView r : c.roles()) {
                    List<CompanyView.PartyView> ps = r.holder().kind().equals("joint_owners") ? r.holder().owners() : List.of(r.holder());
                    for (CompanyView.PartyView p : ps) {
                        String key = p.personKey().map(PartyLink::personKey).or(() -> p.regNumber().map(PartyLink::companyKey)).orElse(null);
                        if (key != null && keys.contains(key)) {
                            links.add(new PartyLink(key, c.regNumber(), r.kind()));
                        }
                    }
                }
            }
            return links.stream().distinct().sorted(java.util.Comparator.comparing(PartyLink::partyKey)
                    .thenComparing(PartyLink::regNumber)).limit(limit).toList();
        }

        @Override public Page<SearchHit> search(String q, int p, int s) { throw new UnsupportedOperationException(); }
        @Override public Page<EventView> eventsOf(String r, int p, int s) { throw new UnsupportedOperationException(); }
        @Override public Page<EventView> events(Optional<LocalDate> d, Optional<String> t, int p, int s) { throw new UnsupportedOperationException(); }
        @Override public Page<RunView> runs(int p, int s) { throw new UnsupportedOperationException(); }
        @Override public Optional<RunView> run(String id) { throw new UnsupportedOperationException(); }
        @Override public List<StatView> stats(String stat) { throw new UnsupportedOperationException(); }
        @Override public Page<FeatureView> feature(String f, int p, int s) { throw new UnsupportedOperationException(); }
    }

    private static CompanyView.PartyView person(String key, String name) {
        return new CompanyView.PartyView("natural_person", name, Optional.empty(), Optional.of(key), List.of());
    }

    private static CompanyView.PartyView foreign(String name) {
        return new CompanyView.PartyView("foreign_entity", name, Optional.empty(), Optional.empty(), List.of());
    }

    private static CompanyView.PartyView entity(String reg, String name) {
        return new CompanyView.PartyView("latvian_entity", name, Optional.of(reg), Optional.empty(), List.of());
    }

    private static CompanyView.RoleView role(String kind, CompanyView.PartyView holder) {
        return new CompanyView.RoleView(kind, holder, kind.equals("officer") ? Optional.of("BOARD_MEMBER") : Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty());
    }

    private static CompanyView company(String reg, String name, CompanyView.RoleView... roles) {
        return new CompanyView(reg, name, "SIA", "LLC", "ACTIVE", Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(roles), D);
    }

    private final Store store = new Store()
            .add(company("40003000001", "Root", role("officer", person("p1", "Person One")),
                    role("shareholder", new CompanyView.PartyView("joint_owners", "joint", Optional.empty(), Optional.empty(),
                            List.of(person("p2", "Person Two"), entity("40003000009", "Holder")))),
                    role("shareholder", foreign("Foreign Ltd"))))
            .add(company("40003000002", "Other of P1", role("officer", person("p1", "Person One")),
                    role("beneficial_owner", person("p3", "Person Three"))))
            .add(company("40003000003", "Other of Holder", role("shareholder", entity("40003000009", "Holder")),
                    role("officer", person("p4", "Person Four"))))
            .add(company("40003000004", "Third hop", role("officer", person("p3", "Person Three"))));

    @Test
    void twoHopsAroundACompany() {
        Network n = new NetworkBuilder(store, 30, 300).build("40003000001").orElseThrow();

        assertThat(n.root()).isEqualTo("company:40003000001");
        assertThat(n.truncated()).isFalse();
        Map<String, Integer> depth = new LinkedHashMap<>();
        n.nodes().forEach(node -> depth.put(node.id(), node.depth()));
        assertThat(depth).containsExactly(
                Map.entry("company:40003000001", 0), Map.entry("person:p1", 1), Map.entry("person:p2", 1),
                Map.entry("company:40003000009", 1), Map.entry("party:40003000001:0", 1),
                Map.entry("company:40003000003", 2), Map.entry("company:40003000002", 2),
                Map.entry("person:p4", 3), Map.entry("person:p3", 3));
        assertThat(n.nodes()).noneMatch(node -> node.id().equals("company:40003000004"));
        assertThat(n.edges()).contains(new Network.Edge("person:p1", "company:40003000001", "officer", Optional.of("BOARD_MEMBER")),
                new Network.Edge("company:40003000009", "company:40003000003", "shareholder", Optional.empty()),
                new Network.Edge("person:p3", "company:40003000002", "beneficial_owner", Optional.empty()));
        assertThat(n.edges()).allMatch(e -> depth.containsKey(e.from()) && depth.containsKey(e.to()));
    }

    @Test
    void limitsCutTheGraphAndSayItIsTruncated() {
        Network fewCompanies = new NetworkBuilder(store, 1, 300).build("40003000001").orElseThrow();
        assertThat(fewCompanies.truncated()).isTrue();
        assertThat(fewCompanies.nodes()).filteredOn(node -> node.depth() == 2).hasSize(1);

        Network fewNodes = new NetworkBuilder(store, 30, 4).build("40003000001").orElseThrow();
        assertThat(fewNodes.truncated()).isTrue();
        assertThat(fewNodes.nodes()).hasSize(4);
        List<String> ids = fewNodes.nodes().stream().map(Network.Node::id).toList();
        assertThat(fewNodes.edges()).allMatch(e -> ids.contains(e.from()) && ids.contains(e.to()));
    }

    @Test
    void unknownCompanyHasNoNetwork() {
        assertThat(new NetworkBuilder(store, 30, 300).build("40003000099")).isEmpty();
    }
}
