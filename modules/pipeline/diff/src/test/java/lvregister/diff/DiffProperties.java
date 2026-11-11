package lvregister.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lvregister.domain.Address;
import lvregister.domain.Company;
import lvregister.domain.CompanyEvent;
import lvregister.domain.CompanyStatus;
import lvregister.domain.LegalForm;
import lvregister.domain.Party;
import lvregister.domain.PersonKey;
import lvregister.domain.RegNumber;
import lvregister.domain.Role;
import lvregister.domain.Snapshot;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Properties from PLAN.md section 9, on small random snapshots. */
class DiffProperties {

    private static final List<RegNumber> REGS = List.of("40003000001", "40003000002", "40003000003", "40003000004",
            "012345678", "40003000005").stream().map(RegNumber::new).toList();
    private static final List<Party> HOLDERS = List.of(
            new Party.NaturalPerson("Test Person", Optional.of(new PersonKey("a1"))),
            new Party.NaturalPerson("Other Person", Optional.empty()),
            new Party.LatvianEntity(new RegNumber("40003999999"), "SIA \"Holder\""),
            new Party.ForeignEntity("Foreign Ltd"));

    @Property(tries = 300)
    void diffOfASnapshotWithItselfIsEmpty(@ForAll("snapshotPairs") Snapshot[] pair) {
        Snapshot a = pair[0];
        Snapshot same = new Snapshot(a.date().plusDays(7), a.companies(), a.roles());

        assertThat(SnapshotDiff.diff(a, same)).isEmpty();
    }

    @Property(tries = 500)
    void replayingTheDiffOnAGivesBForTrackedFields(@ForAll("snapshotPairs") Snapshot[] pair) {
        Snapshot a = pair[0];
        Snapshot b = pair[1];
        List<CompanyEvent> events = SnapshotDiff.diff(a, b);

        Map<RegNumber, Tracked> replayed = replay(a, events);

        Set<RegNumber> terminated = events.stream().filter(CompanyEvent.Terminated.class::isInstance)
                .map(CompanyEvent::regNumber).collect(Collectors.toSet());
        assertThat(replayed.keySet()).isEqualTo(b.companies().keySet());
        for (RegNumber reg : a.companies().keySet()) {
            if (!b.companies().containsKey(reg) || terminated.contains(reg)) {
                continue;
            }
            assertThat(replayed.get(reg)).as(reg.value()).isEqualTo(Tracked.of(b, reg));
        }
        for (RegNumber reg : terminated) {
            assertThat(events.stream().filter(e -> e.regNumber().equals(reg)).toList()).as("only Terminated").hasSize(1);
        }
    }

    @Property(tries = 300)
    void eventIdsAreStableAndUniquePerCapture(@ForAll("snapshotPairs") Snapshot[] pair) {
        List<CompanyEvent> events = SnapshotDiff.diff(pair[0], pair[1]);

        List<StoredEvent> first = EventIds.store(events);
        List<StoredEvent> second = EventIds.store(SnapshotDiff.diff(pair[0], pair[1]));

        assertThat(first).isEqualTo(second);
        assertThat(first.stream().map(StoredEvent::id).distinct().count()).isEqualTo(events.size());
        assertThat(DiffStep.jsonLines(first)).isEqualTo(DiffStep.jsonLines(second));
    }

    /** The fields the diff tracks for one company. */
    record Tracked(String name, Optional<Address> address, LegalForm form, CompanyStatus status,
            Set<Role> officers, Set<Role> ownership) {
        static Tracked of(Snapshot s, RegNumber reg) {
            Company c = s.companies().get(reg);
            Set<Role> roles = s.rolesOf(reg);
            return new Tracked(c.name(), c.address(), c.legalForm(), c.status(),
                    roles.stream().filter(Role.Officer.class::isInstance).collect(Collectors.toSet()),
                    roles.stream().filter(r -> !(r instanceof Role.Officer)).collect(Collectors.toSet()));
        }
    }

    /** Applies the events to A's tracked state. */
    private static Map<RegNumber, Tracked> replay(Snapshot a, List<CompanyEvent> events) {
        Map<RegNumber, Tracked> state = new HashMap<>();
        a.companies().keySet().forEach(reg -> state.put(reg, Tracked.of(a, reg)));
        for (CompanyEvent event : events) {
            RegNumber reg = event.regNumber();
            Tracked t = state.get(reg);
            switch (event) {
                case CompanyEvent.Registered e -> state.put(reg, null);
                case CompanyEvent.MissingFromSnapshot e -> state.remove(reg);
                case CompanyEvent.Terminated e -> { }
                case CompanyEvent.NameChanged e -> state.put(reg, new Tracked(e.newName(), t.address(), t.form(), t.status(), t.officers(), t.ownership()));
                case CompanyEvent.AddressChanged e -> state.put(reg, new Tracked(t.name(), e.newAddress(), t.form(), t.status(), t.officers(), t.ownership()));
                case CompanyEvent.LegalFormChanged e -> state.put(reg, new Tracked(t.name(), t.address(), e.newForm(), t.status(), t.officers(), t.ownership()));
                case CompanyEvent.StatusChanged e -> state.put(reg, new Tracked(t.name(), t.address(), t.form(), e.newStatus(), t.officers(), t.ownership()));
                case CompanyEvent.ManagementChanged e -> state.put(reg, new Tracked(t.name(), t.address(), t.form(), t.status(),
                        apply(t.officers(), e.added(), e.removed()), t.ownership()));
                case CompanyEvent.OwnershipChanged e -> state.put(reg, new Tracked(t.name(), t.address(), t.form(), t.status(),
                        t.officers(), apply(t.ownership(), e.added(), e.removed())));
            }
        }
        return state;
    }

    private static Set<Role> apply(Set<Role> current, Set<? extends Role> added, Set<? extends Role> removed) {
        Set<Role> result = new HashSet<>(current);
        result.removeAll(removed);
        result.addAll(added);
        return result;
    }

    // ---- generators ----

    @Provide
    Arbitrary<Snapshot[]> snapshotPairs() {
        return Combinators.combine(snapshot(LocalDate.of(2026, 11, 2)), snapshot(LocalDate.of(2026, 11, 9)))
                .as((a, b) -> new Snapshot[] {a, b});
    }

    private Arbitrary<Snapshot> snapshot(LocalDate date) {
        List<Arbitrary<Optional<Map.Entry<Company, Set<Role>>>>> parts = REGS.stream()
                .map(reg -> companyWithRoles(reg).optional(0.3)).toList();
        return Combinators.combine(parts).as(entries -> {
            Map<RegNumber, Company> companies = new HashMap<>();
            Map<RegNumber, Set<Role>> roles = new HashMap<>();
            for (Optional<Map.Entry<Company, Set<Role>>> entry : entries) {
                entry.ifPresent(e -> {
                    companies.put(e.getKey().regNumber(), e.getKey());
                    if (!e.getValue().isEmpty()) {
                        roles.put(e.getKey().regNumber(), e.getValue());
                    }
                });
            }
            return new Snapshot(date, companies, roles);
        });
    }

    private Arbitrary<Map.Entry<Company, Set<Role>>> companyWithRoles(RegNumber reg) {
        return Combinators.combine(company(reg), role(reg).set().ofMaxSize(4)).as(Map::entry);
    }

    private Arbitrary<Company> company(RegNumber reg) {
        Arbitrary<String> names = Arbitraries.of("SIA \"Alfa\"", "SIA \"Beta\"");
        Arbitrary<LegalForm> forms = Arbitraries.of(Samples.SIA, Samples.AS);
        Arbitrary<CompanyStatus> statuses = Arbitraries.of(CompanyStatus.class);
        Arbitrary<Optional<Address>> addresses = Arbitraries.of(
                Optional.empty(), Optional.of(Samples.address("Rīga, A iela 1")), Optional.of(Samples.address("Rīga, B iela 2")));
        return Combinators.combine(names, forms, statuses, addresses).as((name, form, status, address) ->
                new Company(reg, name, form, status, address, Optional.of(LocalDate.of(2010, 1, 2)),
                        status.isClosed() ? Optional.of(LocalDate.of(2026, 10, 1)) : Optional.empty(), Optional.empty()));
    }

    private Arbitrary<Role> role(RegNumber reg) {
        Arbitrary<Party> holders = Arbitraries.of(HOLDERS);
        Arbitrary<Role> officers = Combinators.combine(holders, Arbitraries.of("BOARD_MEMBER", "CHAIR_OF_BOARD"))
                .as((h, p) -> new Role.Officer(reg, h, Optional.of(p), Optional.empty(), Optional.empty()));
        Arbitrary<Role> shareholders = Combinators.combine(holders, Arbitraries.of("10", "50", "100.00"))
                .as((h, n) -> new Role.Shareholder(reg, h, Role.ShareRegister.MEMBERS, new BigDecimal(n), Optional.empty(), Optional.empty()));
        Arbitrary<Role> owners = Arbitraries.<Set<String>>of(Set.of(), Set.of("LV"), Set.of("LV", "US"))
                .map(n -> new Role.BeneficialOwner(reg, (Party.NaturalPerson) HOLDERS.getFirst(), n, Optional.of("LV"), Optional.empty()));
        return Arbitraries.oneOf(officers, shareholders, owners);
    }
}
