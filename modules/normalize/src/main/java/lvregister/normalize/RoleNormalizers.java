package lvregister.normalize;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lvregister.domain.Cleaned;
import lvregister.domain.Money;
import lvregister.domain.Party;
import lvregister.domain.RegNumber;
import lvregister.domain.Role;

/** Officers, members, stockholders and beneficial owners (source assessment 3.2). */
public final class RoleNormalizers {

    private static final List<String> HOLDER_COLUMNS = List.of(
            "entity_type", "name", "latvian_identity_number_masked", "birth_date", "legal_entity_registration_number");

    public static final List<String> OFFICER_COLUMNS = concat(HOLDER_COLUMNS,
            "at_legal_entity_registration_number", "position", "governing_body", "registered_on");
    public static final List<String> MEMBER_COLUMNS = concat(HOLDER_COLUMNS,
            "id", "at_legal_entity_registration_number", "number_of_shares", "share_nominal_value", "share_currency", "date_from");
    public static final List<String> STOCKHOLDER_COLUMNS = concat(MEMBER_COLUMNS, "depository_registration_number", "depository_name");
    public static final List<String> JOINT_OWNER_COLUMNS = concat(HOLDER_COLUMNS, "member_id");
    public static final List<String> BENEFICIAL_OWNER_COLUMNS = List.of(
            "legal_entity_registration_number", "forename", "surname", "latvian_identity_number_masked", "birth_date",
            "nationality", "residence", "registered_on");

    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    private RoleNormalizers() {}

    public static Cleaned<Role.Officer> officers(Stream<Row> rows) {
        return Cleaning.collect(rows.map(row -> {
            Optional<RegNumber> company = row.regNumber("at_legal_entity_registration_number");
            if (company.isEmpty()) {
                return Outcome.<Role.Officer>dropped("officers.invalid_company");
            }
            Optional<String> position = row.code("position");
            Optional<String> body = row.code("governing_body");
            if (position.isEmpty() && body.isEmpty()) {
                return Outcome.<Role.Officer>dropped("officers.no_position");
            }
            return switch (Parties.of(row)) {
                case Parties.Holder.Invalid(String rule) -> Outcome.<Role.Officer>dropped("officers." + rule);
                case Parties.Holder.Found(Party holder) -> Outcome.kept(new Role.Officer(company.get(), holder, position, body,
                        row.date("registered_on", "officers.invalid_date")), row);
            };
        }));
    }

    /** SIA members, with {@code members_joint_owners.csv} for jointly held shares. */
    public static Cleaned<Role.Shareholder> members(Stream<Row> rows, Stream<Row> jointOwners) {
        return shareholders("members", Role.ShareRegister.MEMBERS, rows, jointOwners);
    }

    /** AS stockholders, with {@code stockholders_joint_owners.csv}; depositories are Latvian entities. */
    public static Cleaned<Role.Shareholder> stockholders(Stream<Row> rows, Stream<Row> jointOwners) {
        return shareholders("stockholders", Role.ShareRegister.STOCKHOLDERS, rows, jointOwners);
    }

    private static Cleaned<Role.Shareholder> shareholders(
            String file, Role.ShareRegister register, Stream<Row> rows, Stream<Row> jointOwnerRows) {
        Map<String, List<Party>> joint = new HashMap<>();
        Map<String, Long> jointDropped = new HashMap<>();
        jointOwnerRows.forEach(row -> {
            Optional<String> memberId = row.text("member_id");
            Parties.Holder holder = Parties.of(row);
            switch (holder) {
                case Parties.Holder.Found(Party party) when memberId.isPresent() ->
                        joint.computeIfAbsent(memberId.get(), id -> new ArrayList<>()).add(party);
                case Parties.Holder.Found _ -> jointDropped.merge(file + "_joint_owners.missing_member_id", 1L, Long::sum);
                case Parties.Holder.Invalid(String rule) -> jointDropped.merge(file + "_joint_owners." + rule, 1L, Long::sum);
            }
        });
        Cleaned<Role.Shareholder> cleaned = Cleaning.collect(rows.map(row -> shareholder(file, register, row, joint)));
        Map<String, Long> dropped = new HashMap<>(cleaned.droppedByRule());
        jointDropped.forEach((rule, n) -> dropped.merge(rule, n, Long::sum));
        return new Cleaned<>(cleaned.rows(), dropped, cleaned.flaggedByRule());
    }

    private static Outcome<Role.Shareholder> shareholder(
            String file, Role.ShareRegister register, Row row, Map<String, List<Party>> joint) {
        Optional<RegNumber> company = row.regNumber("at_legal_entity_registration_number");
        if (company.isEmpty()) {
            return Outcome.dropped(file + ".invalid_company");
        }
        Optional<BigDecimal> shares = row.decimal("number_of_shares").filter(s -> s.signum() >= 0);
        if (shares.isEmpty()) {
            return Outcome.dropped(file + ".invalid_shares");
        }
        Optional<String> type = row.code("entity_type");
        Parties.Holder holder;
        if (type.equals(Optional.of("JOINT_OWNERS"))) {
            List<Party> owners = row.text("id").map(id -> joint.getOrDefault(id, List.of())).orElse(List.of());
            Set<Party> distinct = new LinkedHashSet<>(owners);
            if (distinct.isEmpty()) {
                return Outcome.dropped(file + ".joint_owners_missing");
            }
            if (distinct.size() == 1) {
                row.flag(file + ".single_joint_owner");
                holder = new Parties.Holder.Found(distinct.iterator().next());
            } else {
                holder = new Parties.Holder.Found(new Party.JointOwners(distinct));
            }
        } else if (type.equals(Optional.of("DEPOSITORY"))) {
            Optional<RegNumber> reg = row.regNumber("depository_registration_number");
            Optional<String> name = row.text("depository_name");
            holder = reg.isPresent() && name.isPresent()
                    ? new Parties.Holder.Found(new Party.LatvianEntity(reg.get(), name.get()))
                    : new Parties.Holder.Invalid(Parties.INVALID_HOLDER_REGCODE);
        } else {
            holder = Parties.of(row);
        }
        return switch (holder) {
            case Parties.Holder.Invalid(String rule) -> Outcome.dropped(file + "." + rule);
            case Parties.Holder.Found(Party party) -> Outcome.kept(new Role.Shareholder(company.get(), party, register,
                    shares.get(), nominalValue(row), row.date("date_from", file + ".invalid_date")), row);
        };
    }

    private static Optional<Money> nominalValue(Row row) {
        Optional<BigDecimal> value = row.decimal("share_nominal_value").filter(v -> v.signum() >= 0);
        Optional<String> currency = row.code("share_currency").filter(c -> c.matches("[A-Z]{3}"));
        return value.isPresent() && currency.isPresent() ? Optional.of(new Money(value.get(), currency.get())) : Optional.empty();
    }

    public static Cleaned<Role.BeneficialOwner> beneficialOwners(Stream<Row> rows) {
        return Cleaning.collect(rows.map(row -> {
            Optional<RegNumber> company = row.regNumber("legal_entity_registration_number");
            if (company.isEmpty()) {
                return Outcome.<Role.BeneficialOwner>dropped("beneficial_owners.invalid_company");
            }
            Optional<String> name = Text.clean(String.join(" ",
                    row.text("forename").orElse(""), row.text("surname").orElse("")));
            if (name.isEmpty()) {
                return Outcome.<Role.BeneficialOwner>dropped("beneficial_owners.missing_name");
            }
            Party.NaturalPerson person = Parties.person(name.get(), row.text("latvian_identity_number_masked"), row.text("birth_date"));
            return Outcome.kept(new Role.BeneficialOwner(company.get(), person, nationalities(row), residence(row),
                    row.date("registered_on", "beneficial_owners.invalid_date")), row);
        }));
    }

    /** {@code LV;US} → {LV, US}; values that are not alpha-2 codes are flagged and left out. */
    private static Set<String> nationalities(Row row) {
        Set<String> codes = new LinkedHashSet<>();
        row.code("nationality").ifPresent(value -> {
            for (String part : value.split(";")) {
                String code = part.strip();
                if (COUNTRY.matcher(code).matches()) {
                    codes.add(code);
                } else if (!code.isEmpty()) {
                    row.flag("beneficial_owners.invalid_nationality");
                }
            }
        });
        return codes;
    }

    private static Optional<String> residence(Row row) {
        Optional<String> code = row.code("residence");
        if (code.isPresent() && !COUNTRY.matcher(code.get()).matches()) {
            row.flag("beneficial_owners.invalid_residence");
            return Optional.empty();
        }
        return code;
    }

    private static List<String> concat(List<String> base, String... more) {
        return Stream.concat(base.stream(), Stream.of(more)).distinct().toList();
    }
}
