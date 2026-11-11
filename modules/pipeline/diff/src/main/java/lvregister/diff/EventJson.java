package lvregister.diff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import lvregister.domain.Address;
import lvregister.domain.CompanyEvent;
import lvregister.domain.LegalForm;
import lvregister.domain.Money;
import lvregister.domain.Party;
import lvregister.domain.Role;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical JSON for events: object keys sorted, sets sorted by their own canonical JSON,
 * decimals as plain strings, absent values left out. The same change always gives the same
 * bytes, so the event id derived from it is stable.
 */
public final class EventJson {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private EventJson() {}

    /** Upper-snake type name, e.g. {@code NAME_CHANGED}. */
    public static String type(CompanyEvent event) {
        return switch (event) {
            case CompanyEvent.Registered _ -> "REGISTERED";
            case CompanyEvent.NameChanged _ -> "NAME_CHANGED";
            case CompanyEvent.AddressChanged _ -> "ADDRESS_CHANGED";
            case CompanyEvent.LegalFormChanged _ -> "LEGAL_FORM_CHANGED";
            case CompanyEvent.ManagementChanged _ -> "MANAGEMENT_CHANGED";
            case CompanyEvent.OwnershipChanged _ -> "OWNERSHIP_CHANGED";
            case CompanyEvent.StatusChanged _ -> "STATUS_CHANGED";
            case CompanyEvent.Terminated _ -> "TERMINATED";
            case CompanyEvent.MissingFromSnapshot _ -> "MISSING_FROM_SNAPSHOT";
        };
    }

    /** The type-specific part of an event, as a sorted map. */
    public static Map<String, Object> payload(CompanyEvent event) {
        Map<String, Object> p = new TreeMap<>();
        switch (event) {
            case CompanyEvent.Registered(_, _, var registeredOn) -> put(p, "registeredOn", registeredOn.map(Object::toString));
            case CompanyEvent.NameChanged(_, _, var oldName, var newName) -> {
                p.put("old", oldName);
                p.put("new", newName);
            }
            case CompanyEvent.AddressChanged(_, _, var oldAddress, var newAddress) -> {
                put(p, "old", oldAddress.map(EventJson::address));
                put(p, "new", newAddress.map(EventJson::address));
            }
            case CompanyEvent.LegalFormChanged(_, _, var oldForm, var newForm) -> {
                p.put("old", legalForm(oldForm));
                p.put("new", legalForm(newForm));
            }
            case CompanyEvent.ManagementChanged(_, _, var added, var removed) -> {
                p.put("added", sorted(added.stream().map(EventJson::role).toList()));
                p.put("removed", sorted(removed.stream().map(EventJson::role).toList()));
            }
            case CompanyEvent.OwnershipChanged(_, _, var added, var removed) -> {
                p.put("added", sorted(added.stream().map(EventJson::role).toList()));
                p.put("removed", sorted(removed.stream().map(EventJson::role).toList()));
            }
            case CompanyEvent.StatusChanged(_, _, var oldStatus, var newStatus) -> {
                p.put("old", oldStatus.name());
                p.put("new", newStatus.name());
            }
            case CompanyEvent.Terminated(_, _, var terminatedOn) -> put(p, "terminatedOn", terminatedOn.map(Object::toString));
            case CompanyEvent.MissingFromSnapshot _ -> { }
        }
        return p;
    }

    /** One JSON Lines record: id, type, registration number, both capture dates, payload. */
    public static String line(StoredEvent stored) {
        CompanyEvent event = stored.event();
        Map<String, Object> line = new TreeMap<>();
        line.put("id", stored.id());
        line.put("type", type(event));
        line.put("regNumber", event.regNumber().value());
        line.put("previous", event.observed().previous().toString());
        line.put("current", event.observed().current().toString());
        line.put("payload", payload(event));
        return write(line);
    }

    static String write(Object value) {
        return JSON.writeValueAsString(value);
    }

    static Map<String, Object> role(Role role) {
        Map<String, Object> m = new TreeMap<>();
        switch (role) {
            case Role.Officer o -> {
                m.put("kind", "officer");
                m.put("holder", party(o.holder()));
                put(m, "position", o.position());
                put(m, "governingBody", o.governingBody());
                put(m, "since", o.since().map(Object::toString));
            }
            case Role.Shareholder s -> {
                m.put("kind", "shareholder");
                m.put("register", s.register().name());
                m.put("holder", party(s.holder()));
                m.put("shares", s.shares().toPlainString());
                put(m, "nominalValue", s.nominalValue().map(EventJson::money));
                put(m, "since", s.since().map(Object::toString));
            }
            case Role.BeneficialOwner b -> {
                m.put("kind", "beneficial_owner");
                m.put("holder", party(b.holder()));
                m.put("nationalities", b.nationalities().stream().sorted().toList());
                put(m, "residence", b.residence());
                put(m, "since", b.since().map(Object::toString));
            }
        }
        return m;
    }

    static Map<String, Object> party(Party party) {
        Map<String, Object> m = new TreeMap<>();
        switch (party) {
            case Party.NaturalPerson(var name, var key) -> {
                m.put("kind", "natural_person");
                m.put("name", name);
                put(m, "key", key.map(k -> k.value()));
            }
            case Party.LatvianEntity(var reg, var name) -> {
                m.put("kind", "latvian_entity");
                m.put("regNumber", reg.value());
                m.put("name", name);
            }
            case Party.ForeignEntity(var name) -> {
                m.put("kind", "foreign_entity");
                m.put("name", name);
            }
            case Party.JointOwners(var owners) -> {
                m.put("kind", "joint_owners");
                m.put("owners", sorted(owners.stream().map(EventJson::party).toList()));
            }
        }
        return m;
    }

    private static Map<String, Object> address(Address a) {
        Map<String, Object> m = new TreeMap<>();
        m.put("line", a.line());
        put(m, "postalCode", a.postalCode());
        put(m, "vzdId", a.vzdId().map(Object::toString));
        put(m, "atvk", a.atvk());
        return m;
    }

    private static Map<String, Object> legalForm(LegalForm f) {
        return new TreeMap<>(Map.of("code", f.code(), "family", f.family().name()));
    }

    private static Map<String, Object> money(Money money) {
        return new TreeMap<>(Map.of("amount", money.amount().toPlainString(), "currency", money.currency()));
    }

    private static void put(Map<String, Object> m, String key, Optional<?> value) {
        value.ifPresent(v -> m.put(key, v));
    }

    /** Sets have no order; sort their elements by canonical JSON. */
    private static List<Map<String, Object>> sorted(Collection<Map<String, Object>> items) {
        List<Map<String, Object>> list = new ArrayList<>(items);
        list.sort(Comparator.comparing(EventJson::write));
        return list;
    }
}
