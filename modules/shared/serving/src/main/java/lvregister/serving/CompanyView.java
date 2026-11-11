package lvregister.serving;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A company as served: base data, status, capital and its current officers and owners.
 * {@code asOf} is the capture in which this state was first seen: re-indexing an unchanged
 * company keeps it. Plain values only, so every serving backend can store it as a document.
 */
public record CompanyView(
        String regNumber,
        String name,
        String legalFormCode,
        String legalFamily,
        String status,
        Optional<AddressView> address,
        Optional<LocalDate> registered,
        Optional<LocalDate> terminated,
        Optional<MoneyView> capital,
        List<RoleView> roles,
        LocalDate asOf) {

    public CompanyView {
        roles = List.copyOf(roles);
    }

    public record AddressView(String line, Optional<String> postalCode, Optional<Long> vzdId, Optional<String> atvk) {}

    /** Amount as a plain decimal string, so no backend rounds it. */
    public record MoneyView(String amount, String currency) {}

    /**
     * One role: {@code kind} is {@code officer}, {@code shareholder} or {@code beneficial_owner}.
     * Fields that do not apply to the kind are empty.
     */
    public record RoleView(
            String kind,
            PartyView holder,
            Optional<String> position,
            Optional<String> governingBody,
            Optional<String> shareRegister,
            Optional<String> shares,
            Optional<MoneyView> nominalValue,
            List<String> nationalities,
            Optional<String> residence,
            Optional<LocalDate> since) {

        public RoleView {
            nationalities = List.copyOf(nationalities);
        }
    }

    /**
     * Who holds a role: {@code kind} is {@code natural_person}, {@code latvian_entity},
     * {@code foreign_entity} or {@code joint_owners} (then {@code owners} lists them).
     */
    public record PartyView(String kind, String name, Optional<String> regNumber, Optional<String> personKey,
            List<PartyView> owners) {

        public PartyView {
            owners = List.copyOf(owners);
        }
    }
}
