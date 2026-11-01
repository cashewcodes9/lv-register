package lvregister.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lvregister.serving.CompanyView;
import lvregister.serving.EventView;
import lvregister.serving.FeatureView;
import lvregister.serving.Page;
import lvregister.serving.PartyLink;
import lvregister.serving.RunView;
import lvregister.serving.SearchHit;
import lvregister.serving.ServingNames;
import lvregister.serving.ServingQueries;
import lvregister.serving.StatView;

/** In-memory serving store with fixed synthetic data (no real person data). */
class FakeQueries implements ServingQueries {

    static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    static final LocalDate D2 = LocalDate.of(2026, 11, 9);
    static final String ALFA = "40003000001";
    static final String BETA = "40003000002";

    final Map<String, CompanyView> companies = new LinkedHashMap<>();
    final AtomicInteger statsCalls = new AtomicInteger();

    FakeQueries() {
        CompanyView.PartyView person = new CompanyView.PartyView("natural_person", "Test Person", Optional.empty(),
                Optional.of("ab".repeat(32)), List.of());
        CompanyView.RoleView officer = new CompanyView.RoleView("officer", person, Optional.of("BOARD_MEMBER"),
                Optional.of("EXECUTIVE_BOARD"), Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
                Optional.empty(), Optional.of(LocalDate.of(2020, 1, 1)));
        companies.put(ALFA, new CompanyView(ALFA, "SIA \"Alfa\"", "SIA", "LLC", "ACTIVE",
                Optional.of(new CompanyView.AddressView("Rīga, Testa iela 1", Optional.of("LV-1050"), Optional.of(100L),
                        Optional.empty())),
                Optional.of(LocalDate.of(2010, 1, 2)), Optional.empty(), Optional.of(new CompanyView.MoneyView("2800", "EUR")),
                List.of(officer), D2));
        companies.put(BETA, new CompanyView(BETA, "SIA \"Beta\"", "SIA", "LLC", "TERMINATED", Optional.empty(),
                Optional.empty(), Optional.of(LocalDate.of(2026, 11, 5)), Optional.empty(), List.of(officer), D2));
    }

    @Override
    public Page<SearchHit> search(String query, int page, int size) {
        Page.check(page, size);
        List<SearchHit> hits = companies.values().stream()
                .filter(c -> c.name().toLowerCase().contains(query.toLowerCase()) || c.regNumber().equals(query))
                .map(c -> new SearchHit(c.regNumber(), c.name(), c.legalFormCode(), c.status(), 1.0)).toList();
        return new Page<>(hits, page, size, hits.size());
    }

    @Override
    public Optional<CompanyView> company(String regNumber) {
        return Optional.ofNullable(companies.get(regNumber));
    }

    @Override
    public List<CompanyView> companies(Collection<String> regNumbers) {
        return regNumbers.stream().map(companies::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<PartyLink> rolesOfParties(Collection<String> partyKeys, int limit) {
        List<PartyLink> links = new ArrayList<>();
        for (CompanyView c : companies.values()) {
            for (CompanyView.RoleView r : c.roles()) {
                r.holder().personKey().map(PartyLink::personKey).filter(partyKeys::contains)
                        .ifPresent(k -> links.add(new PartyLink(k, c.regNumber(), r.kind())));
            }
        }
        return links.stream().limit(limit).toList();
    }

    @Override
    public Page<EventView> eventsOf(String regNumber, int page, int size) {
        return events(Optional.empty(), Optional.empty(), page, size);
    }

    @Override
    public Page<EventView> events(Optional<LocalDate> captureDate, Optional<String> type, int page, int size) {
        Page.check(page, size);
        List<EventView> all = List.of(new EventView("e".repeat(64), "NAME_CHANGED", ALFA, D1, D2,
                "{\"new\":\"SIA \\\"Alfa\\\"\",\"old\":\"SIA \\\"A\\\"\"}"));
        List<EventView> items = all.stream().filter(e -> captureDate.map(d -> d.equals(e.current())).orElse(true))
                .filter(e -> type.map(t -> t.equals(e.type())).orElse(true)).toList();
        return new Page<>(items, page, size, items.size());
    }

    @Override
    public Page<RunView> runs(int page, int size) {
        Page.check(page, size);
        return new Page<>(List.of(run("run-1").orElseThrow()), page, size, 1);
    }

    @Override
    public Optional<RunView> run(String runId) {
        return runId.equals("run-1")
                ? Optional.of(new RunView("run-1", D2, "OK", "{\"status\":\"OK\",\"warnings\":[]}"))
                : Optional.empty();
    }

    @Override
    public List<StatView> stats(String stat) {
        statsCalls.incrementAndGet();
        return stat.equals(ServingNames.COMPANIES_STAT)
                ? List.of(new StatView(stat, Map.of("legal_family", "LLC", "status", "ACTIVE"), 2))
                : List.of(new StatView(stat, Map.of("capture_date", "2026-11-09", "event_type", "NAME_CHANGED"), 1));
    }

    @Override
    public Page<FeatureView> feature(String feature, int page, int size) {
        Page.check(page, size);
        return new Page<>(List.of(new FeatureView(feature, "100", 1, "{\"active_companies\":185}")), page, size, 1);
    }
}
