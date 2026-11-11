package lvregister.serving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What every serving backend must do. An adapter test extends this class, gives an empty store
 * in {@link #reset()}, and must pass every test (docs/architecture.md, "Serving").
 */
public abstract class ServingContract {

    protected static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    protected static final LocalDate D2 = LocalDate.of(2026, 11, 9);

    protected abstract ServingWriter writer();

    protected abstract ServingQueries queries();

    /** Empties the store (after {@code prepare()}). */
    protected abstract void reset();

    @BeforeEach
    void emptyStore() {
        writer().prepare();
        reset();
    }

    protected static CompanyView company(String reg, String name, LocalDate asOf) {
        CompanyView.PartyView person = new CompanyView.PartyView("natural_person", "Test Person", Optional.empty(),
                Optional.of("ab".repeat(32)), List.of());
        CompanyView.PartyView joint = new CompanyView.PartyView("joint_owners", "joint owners", Optional.empty(), Optional.empty(),
                List.of(person, new CompanyView.PartyView("latvian_entity", "SIA \"Holder\"", Optional.of("40003999999"),
                        Optional.empty(), List.of())));
        List<CompanyView.RoleView> roles = List.of(
                new CompanyView.RoleView("officer", person, Optional.of("BOARD_MEMBER"), Optional.of("EXECUTIVE_BOARD"),
                        Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.of(LocalDate.of(2020, 1, 1))),
                new CompanyView.RoleView("shareholder", joint, Optional.empty(), Optional.empty(), Optional.of("MEMBERS"),
                        Optional.of("100.5"), Optional.of(new CompanyView.MoneyView("1", "EUR")), List.of(), Optional.empty(), Optional.empty()),
                new CompanyView.RoleView("beneficial_owner", person, Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), List.of("LV", "US"), Optional.of("LV"), Optional.empty()));
        return new CompanyView(reg, name, "SIA", "LLC", "ACTIVE",
                Optional.of(new CompanyView.AddressView("Rīga, Testa iela 1", Optional.of("1050"), Optional.of(100L), Optional.empty())),
                Optional.of(LocalDate.of(2010, 1, 2)), Optional.empty(), Optional.of(new CompanyView.MoneyView("2800", "EUR")),
                roles, asOf);
    }

    protected static EventView event(String id, String type, String reg, LocalDate current) {
        return new EventView(id.repeat(64).substring(0, 64), type, reg, current.minusDays(7), current,
                "{\"new\":\"SIA \\\"B\\\"\",\"old\":\"SIA \\\"A\\\"\"}");
    }

    // ---- companies ----

    @Test
    void prepareCanRunAgain() {
        writer().prepare();
        writer().prepare();
    }

    @Test
    void companyRoundTripKeepsEveryField() {
        CompanyView c = company("40003000001", "SIA \"Alfa\"", D1);

        writer().upsertCompanies(Stream.of(c));

        assertThat(queries().company("40003000001")).contains(c);
        assertThat(queries().company("40003000002")).isEmpty();
    }

    @Test
    void upsertingTheSameCompaniesAgainWritesNothing() {
        List<CompanyView> companies = List.of(company("40003000001", "SIA \"Alfa\"", D1), company("40003000002", "SIA \"Beta\"", D1));

        assertThat(writer().upsertCompanies(companies.stream())).isEqualTo(new ServingWriter.Upserted(2, 0));
        assertThat(writer().upsertCompanies(companies.stream())).isEqualTo(new ServingWriter.Upserted(0, 2));
    }

    @Test
    void anOlderCaptureNeverReplacesANewerOne() {
        writer().upsertCompanies(Stream.of(company("40003000001", "SIA \"New\"", D2)));

        assertThat(writer().upsertCompanies(Stream.of(company("40003000001", "SIA \"Old\"", D1)))).isEqualTo(new ServingWriter.Upserted(0, 1));
        assertThat(queries().company("40003000001").map(CompanyView::name)).contains("SIA \"New\"");
        writer().upsertCompanies(Stream.of(company("40003000001", "SIA \"Newer\"", D2.plusDays(7))));
        assertThat(queries().company("40003000001").map(CompanyView::name)).contains("SIA \"Newer\"");
    }

    @Test
    void companiesByRegistrationNumbers() {
        writer().upsertCompanies(Stream.of(company("40003000001", "SIA \"Alfa\"", D1), company("40003000002", "SIA \"Beta\"", D1)));

        assertThat(queries().companies(List.of("40003000002", "40003000001", "40003000009")))
                .extracting(CompanyView::regNumber).containsExactlyInAnyOrder("40003000001", "40003000002");
        assertThat(queries().companies(List.of())).isEmpty();
    }

    @Test
    void rolesOfPartiesLinkPersonsAndEntitiesIncludingJointOwners() {
        writer().upsertCompanies(Stream.of(company("40003000001", "SIA \"Alfa\"", D1), company("40003000002", "SIA \"Beta\"", D1)));
        String person = PartyLink.personKey("ab".repeat(32));
        String holder = PartyLink.companyKey("40003999999");

        assertThat(queries().rolesOfParties(List.of(person, holder), 100)).containsExactly(
                new PartyLink(holder, "40003000001", "shareholder"),
                new PartyLink(holder, "40003000002", "shareholder"),
                new PartyLink(person, "40003000001", "beneficial_owner"),
                new PartyLink(person, "40003000001", "officer"),
                new PartyLink(person, "40003000001", "shareholder"),
                new PartyLink(person, "40003000002", "beneficial_owner"),
                new PartyLink(person, "40003000002", "officer"),
                new PartyLink(person, "40003000002", "shareholder"));
        assertThat(queries().rolesOfParties(List.of(person), 2)).hasSize(2);
        assertThat(queries().rolesOfParties(List.of(), 10)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> queries().rolesOfParties(List.of(person), 0));
    }

    @Test
    void rolesOfPartiesFollowTheLatestDocument() {
        writer().upsertCompanies(Stream.of(company("40003000001", "SIA \"Alfa\"", D1)));
        CompanyView noRoles = new CompanyView("40003000001", "SIA \"Alfa\"", "SIA", "LLC", "ACTIVE", Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), D2);
        writer().upsertCompanies(Stream.of(noRoles));

        assertThat(queries().rolesOfParties(List.of(PartyLink.personKey("ab".repeat(32))), 10)).isEmpty();
    }

    // ---- search ----

    private void searchFixture() {
        writer().upsertCompanies(Stream.of(
                company("40003000001", "SIA \"Rīgas Ūdens\"", D1),
                company("40003000002", "SIA \"Rīgas Ūdens Serviss\"", D1),
                company("40003000003", "AS \"Latvijas Gāze\"", D1),
                company("012345678", "IK \"Ķekava\"", D1)));
    }

    @Test
    void searchIgnoresCaseAndDiacritics() {
        searchFixture();

        assertThat(queries().search("rigas udens", 0, 10).items()).extracting(SearchHit::regNumber)
                .containsExactly("40003000001", "40003000002");
        assertThat(queries().search("KEKAVA", 0, 10).items()).extracting(SearchHit::name).containsExactly("IK \"Ķekava\"");
    }

    @Test
    void searchToleratesSmallTypos() {
        searchFixture();

        assertThat(queries().search("latvijas gaaze", 0, 10).items()).extracting(SearchHit::regNumber).contains("40003000003");
    }

    @Test
    void searchByRegistrationNumberPutsItFirst() {
        searchFixture();

        assertThat(queries().search("012345678", 0, 10).items()).extracting(SearchHit::regNumber).first().isEqualTo("012345678");
    }

    @Test
    void searchFollowsTheLatestName() {
        writer().upsertCompanies(Stream.of(company("40003000001", "SIA \"Vecais Vārds\"", D1)));
        writer().upsertCompanies(Stream.of(company("40003000001", "SIA \"Jaunais Nosaukums\"", D2)));

        assertThat(queries().search("jaunais nosaukums", 0, 5).items()).extracting(SearchHit::regNumber).containsExactly("40003000001");
        assertThat(queries().search("vecais vards", 0, 5).items()).isEmpty();
    }

    @Test
    void searchPagesAndCounts() {
        searchFixture();

        Page<SearchHit> first = queries().search("rigas", 0, 1);
        Page<SearchHit> second = queries().search("rigas", 1, 1);

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items()).hasSize(1);
        assertThat(second.items()).hasSize(1).doesNotContainAnyElementsOf(first.items());
        assertThat(queries().search("nothing like this", 0, 10).items()).isEmpty();
    }

    @Test
    void searchTreatsLikeWildcardsAsText() {
        searchFixture();

        assertThat(queries().search("%", 0, 10).items()).isEmpty();
        assertThat(queries().search("_", 0, 10).items()).isEmpty();
    }

    @Test
    void pagingArgumentsAreChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> queries().search("a", 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> queries().runs(-1, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> queries().events(Optional.empty(), Optional.empty(), 0, Page.MAX_SIZE + 1));
    }

    // ---- events ----

    @Test
    void eventsKeepTheirPayloadAndAreIdempotent() {
        List<EventView> events = List.of(event("a", "NAME_CHANGED", "40003000001", D1), event("b", "NAME_CHANGED", "40003000001", D2),
                event("c", "STATUS_CHANGED", "40003000002", D2));

        assertThat(writer().upsertEvents(events).written()).isEqualTo(3);
        writer().upsertEvents(events);

        assertThat(queries().eventsOf("40003000001", 0, 10).items()).containsExactly(events.get(1), events.get(0));
        assertThat(queries().eventsOf("40003000001", 0, 10).total()).isEqualTo(2);
    }

    @Test
    void eventsFeedFiltersByCaptureAndType() {
        writer().upsertEvents(List.of(event("a", "NAME_CHANGED", "40003000001", D1), event("b", "NAME_CHANGED", "40003000001", D2),
                event("c", "STATUS_CHANGED", "40003000002", D2)));

        assertThat(queries().events(Optional.empty(), Optional.empty(), 0, 10).items()).extracting(EventView::current)
                .containsExactly(D2, D2, D1);
        assertThat(queries().events(Optional.of(D2), Optional.empty(), 0, 10).total()).isEqualTo(2);
        assertThat(queries().events(Optional.of(D2), Optional.of("STATUS_CHANGED"), 0, 10).items())
                .extracting(EventView::regNumber).containsExactly("40003000002");
    }

    // ---- runs ----

    @Test
    void runsNewestFirstAndReplacedByRunId() {
        writer().upsertRun(new RunView("run-1", D1, "OK", "{\"status\":\"OK\"}"));
        writer().upsertRun(new RunView("run-2", D2, "WARN", "{\"status\":\"WARN\"}"));
        writer().upsertRun(new RunView("run-2", D2, "OK", "{\"status\":\"OK\"}"));

        assertThat(queries().runs(0, 10).items()).extracting(RunView::runId).containsExactly("run-2", "run-1");
        assertThat(queries().run("run-2")).contains(new RunView("run-2", D2, "OK", "{\"status\":\"OK\"}"));
        assertThat(queries().run("run-9")).isEmpty();
    }

    // ---- stats and features ----

    @Test
    void statsAreReplacedAsAWhole() {
        writer().replaceStats("events_per_capture", List.of(
                new StatView("events_per_capture", java.util.Map.of("capture_date", "2026-11-09", "event_type", "NAME_CHANGED"), 12),
                new StatView("events_per_capture", java.util.Map.of("capture_date", "2026-11-09", "event_type", "TERMINATED"), 3)));
        writer().replaceStats("companies", List.of(new StatView("companies", java.util.Map.of("status", "ACTIVE"), 100)));

        writer().replaceStats("events_per_capture", List.of(
                new StatView("events_per_capture", java.util.Map.of("capture_date", "2026-11-16", "event_type", "NAME_CHANGED"), 7)));

        assertThat(queries().stats("events_per_capture")).containsExactly(
                new StatView("events_per_capture", java.util.Map.of("capture_date", "2026-11-16", "event_type", "NAME_CHANGED"), 7));
        assertThat(queries().stats("companies")).hasSize(1);
        assertThat(queries().stats("nothing")).isEmpty();
    }

    @Test
    void featuresByRankAndReplacedAsAWhole() {
        writer().replaceFeature("mass_registration_addresses", List.of(
                new FeatureView("mass_registration_addresses", "106", 2, "{\"active_companies\":157}"),
                new FeatureView("mass_registration_addresses", "105", 1, "{\"active_companies\":185}"),
                new FeatureView("mass_registration_addresses", "107", 3, "{\"active_companies\":132}")));

        Page<FeatureView> page = queries().feature("mass_registration_addresses", 0, 2);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(FeatureView::key).containsExactly("105", "106");
        assertThat(page.items().getFirst().json()).isEqualTo("{\"active_companies\":185}");
        writer().replaceFeature("mass_registration_addresses", List.of());
        assertThat(queries().feature("mass_registration_addresses", 0, 10).total()).isZero();
    }
}
