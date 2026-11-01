package lvregister.normalize;

import java.util.Optional;
import lvregister.domain.Party;
import lvregister.domain.RegNumber;

/**
 * The holder of a role from the common person/entity columns of the role files
 * ({@code entity_type}, {@code name}, {@code latvian_identity_number_masked}, {@code birth_date},
 * {@code legal_entity_registration_number}). Joint owners and depositories are handled by
 * the shareholder normalizer.
 */
final class Parties {

    /** Drop rules, prefixed with the file name by the caller. */
    static final String UNKNOWN_ENTITY_TYPE = "unknown_entity_type";
    static final String MISSING_NAME = "missing_name";
    static final String INVALID_HOLDER_REGCODE = "invalid_holder_regcode";

    private Parties() {}

    /** The holder, or the rule (without file prefix) that drops the row. */
    sealed interface Holder {
        record Found(Party party) implements Holder {}

        record Invalid(String rule) implements Holder {}
    }

    static Holder of(Row row) {
        Optional<String> type = row.code("entity_type");
        Optional<String> name = row.text("name");
        if (type.isEmpty()) {
            return new Holder.Invalid(UNKNOWN_ENTITY_TYPE);
        }
        return switch (type.get()) {
            case "NATURAL_PERSON" -> name
                    .<Holder>map(n -> new Holder.Found(person(n, row.text("latvian_identity_number_masked"), row.text("birth_date"))))
                    .orElse(new Holder.Invalid(MISSING_NAME));
            case "LEGAL_ENTITY" -> {
                Optional<RegNumber> reg = row.regNumber("legal_entity_registration_number");
                if (name.isEmpty()) {
                    yield new Holder.Invalid(MISSING_NAME);
                }
                yield reg.<Holder>map(r -> new Holder.Found(new Party.LatvianEntity(r, name.get())))
                        .orElse(new Holder.Invalid(INVALID_HOLDER_REGCODE));
            }
            case "FOREIGN_ENTITY" -> name.<Holder>map(n -> new Holder.Found(new Party.ForeignEntity(n)))
                    .orElse(new Holder.Invalid(MISSING_NAME));
            default -> new Holder.Invalid(UNKNOWN_ENTITY_TYPE);
        };
    }

    static Party.NaturalPerson person(String name, Optional<String> maskedCode, Optional<String> birthDate) {
        return new Party.NaturalPerson(name, PersonKeys.of(name, maskedCode, birthDate));
    }
}
