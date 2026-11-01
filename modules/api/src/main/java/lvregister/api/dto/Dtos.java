package lvregister.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lvregister.serving.CompanyView;
import lvregister.serving.EventView;
import lvregister.serving.FeatureView;
import lvregister.serving.Network;
import lvregister.serving.Page;
import lvregister.serving.RunView;
import lvregister.serving.SearchHit;
import lvregister.serving.StatView;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API's response records (PLAN.md section 13), separate from the serving views: absent
 * values are {@code null} instead of {@code Optional}, and stored JSON (event payloads, quality
 * reports, feature rows) is embedded as JSON, not as a string. Fields that may be {@code null}
 * carry {@link Nullable}; {@link OpenApiConfig} marks all others required in the OpenAPI spec.
 */
public final class Dtos {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Dtos() {}

    public record PageDto<T>(List<T> items, int page, int size, long total) {}

    public record SearchHitDto(String regNumber, String name, String legalFormCode, String status, double score) {}

    public record CompanyDto(String regNumber, String name, String legalFormCode, String legalFamily, String status,
            @Nullable AddressDto address, @Nullable LocalDate registered, @Nullable LocalDate terminated,
            @Nullable MoneyDto capital, List<RoleDto> roles,
            LocalDate asOf) {}

    public record AddressDto(String line, @Nullable String postalCode, @Nullable Long vzdId, @Nullable String atvk) {}

    public record MoneyDto(String amount, String currency) {}

    public record RoleDto(String kind, PartyDto holder, @Nullable String position, @Nullable String governingBody,
            @Nullable String shareRegister,
            @Nullable String shares, @Nullable MoneyDto nominalValue, List<String> nationalities, @Nullable String residence,
            @Nullable LocalDate since) {}

    /** {@code personKey}: a pseudonymous key linking the same person across companies. */
    public record PartyDto(String kind, String name, @Nullable String regNumber, @Nullable String personKey,
            List<PartyDto> owners) {}

    public record EventDto(String id, String type, String regNumber, LocalDate previousCapture, LocalDate capture,
            JsonNode payload) {}

    public record RunSummaryDto(String runId, LocalDate captureDate, String status) {}

    public record RunDto(String runId, LocalDate captureDate, String status, JsonNode report) {}

    public record StatRowDto(Map<String, String> dimensions, long value) {}

    public record StatsDto(List<StatRowDto> companies, List<StatRowDto> eventsPerCapture) {}

    public record MassAddressDto(int rank, String vzdId, JsonNode data) {}

    public record NetworkDto(String root, List<NodeDto> nodes, List<EdgeDto> edges, boolean truncated) {}

    public record NodeDto(String id, String type, String label, @Nullable String regNumber, @Nullable String status,
            int depth) {}

    public record EdgeDto(String from, String to, String role, @Nullable String position) {}

    public static <V, D> PageDto<D> page(Page<V> page, Function<V, D> map) {
        return new PageDto<>(page.items().stream().map(map).toList(), page.page(), page.size(), page.total());
    }

    public static SearchHitDto hit(SearchHit h) {
        return new SearchHitDto(h.regNumber(), h.name(), h.legalFormCode(), h.status(), h.score());
    }

    public static CompanyDto company(CompanyView c) {
        return new CompanyDto(c.regNumber(), c.name(), c.legalFormCode(), c.legalFamily(), c.status(),
                c.address().map(a -> new AddressDto(a.line(), a.postalCode().orElse(null), a.vzdId().orElse(null),
                        a.atvk().orElse(null))).orElse(null),
                c.registered().orElse(null), c.terminated().orElse(null), c.capital().map(Dtos::money).orElse(null),
                c.roles().stream().map(Dtos::role).toList(), c.asOf());
    }

    private static MoneyDto money(CompanyView.MoneyView m) {
        return new MoneyDto(m.amount(), m.currency());
    }

    private static RoleDto role(CompanyView.RoleView r) {
        return new RoleDto(r.kind(), party(r.holder()), r.position().orElse(null), r.governingBody().orElse(null),
                r.shareRegister().orElse(null), r.shares().orElse(null), r.nominalValue().map(Dtos::money).orElse(null),
                r.nationalities(), r.residence().orElse(null), r.since().orElse(null));
    }

    private static PartyDto party(CompanyView.PartyView p) {
        return new PartyDto(p.kind(), p.name(), p.regNumber().orElse(null), p.personKey().orElse(null),
                p.owners().stream().map(Dtos::party).toList());
    }

    public static EventDto event(EventView e) {
        return new EventDto(e.id(), e.type(), e.regNumber(), e.previous(), e.current(), JSON.readTree(e.payloadJson()));
    }

    public static RunSummaryDto runSummary(RunView r) {
        return new RunSummaryDto(r.runId(), r.captureDate(), r.status());
    }

    public static RunDto run(RunView r) {
        return new RunDto(r.runId(), r.captureDate(), r.status(), JSON.readTree(r.reportJson()));
    }

    public static StatRowDto stat(StatView s) {
        return new StatRowDto(s.dimensions(), s.value());
    }

    public static MassAddressDto massAddress(FeatureView f) {
        return new MassAddressDto(f.rank(), f.key(), JSON.readTree(f.json()));
    }

    public static NetworkDto network(Network n) {
        return new NetworkDto(n.root(),
                n.nodes().stream().map(x -> new NodeDto(x.id(), x.type(), x.label(), x.regNumber().orElse(null),
                        x.status().orElse(null), x.depth())).toList(),
                n.edges().stream().map(e -> new EdgeDto(e.from(), e.to(), e.role(), e.position().orElse(null))).toList(),
                n.truncated());
    }
}
