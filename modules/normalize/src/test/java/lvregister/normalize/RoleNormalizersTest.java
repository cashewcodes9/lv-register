package lvregister.normalize;

import static lvregister.normalize.Rows.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lvregister.domain.Cleaned;
import lvregister.domain.Money;
import lvregister.domain.Party;
import lvregister.domain.RegNumber;
import lvregister.domain.Role;
import org.junit.jupiter.api.Test;

class RoleNormalizersTest {

    private static final String CO = "40003000001";
    private static final RegNumber COMPANY = new RegNumber(CO);

    @Test
    void officersOfEveryHolderType() {
        Cleaned<Role.Officer> cleaned = RoleNormalizers.officers(Stream.of(
                row("at_legal_entity_registration_number", CO, "entity_type", "NATURAL_PERSON", "name", "Test Person",
                        "latvian_identity_number_masked", "010101-*****", "position", "BOARD_MEMBER",
                        "governing_body", "EXECUTIVE_BOARD", "registered_on", "2020-01-02"),
                row("at_legal_entity_registration_number", CO, "entity_type", "LEGAL_ENTITY", "name", "SIA \"Owner\"",
                        "legal_entity_registration_number", "40003000002", "governing_body", "EXECUTIVE_BODY"),
                row("at_legal_entity_registration_number", CO, "entity_type", "FOREIGN_ENTITY", "name", "Foreign GmbH",
                        "position", "MEMBER")));

        assertThat(cleaned.rows()).hasSize(3);
        Role.Officer person = cleaned.rows().get(0);
        assertThat(person.holder()).isInstanceOfSatisfying(Party.NaturalPerson.class, p -> assertThat(p.key()).isPresent());
        assertThat(person.position()).contains("BOARD_MEMBER");
        assertThat(person.since()).contains(LocalDate.of(2020, 1, 2));
        assertThat(cleaned.rows().get(1).holder()).isEqualTo(new Party.LatvianEntity(new RegNumber("40003000002"), "SIA \"Owner\""));
        assertThat(cleaned.rows().get(1).position()).isEmpty();
        assertThat(cleaned.rows().get(2).holder()).isEqualTo(new Party.ForeignEntity("Foreign GmbH"));
    }

    @Test
    void officerRowsThatBreakARuleAreDropped() {
        Cleaned<Role.Officer> cleaned = RoleNormalizers.officers(Stream.of(
                row("at_legal_entity_registration_number", "1", "entity_type", "NATURAL_PERSON", "name", "A", "position", "MEMBER"),
                row("at_legal_entity_registration_number", CO, "entity_type", "NATURAL_PERSON", "name", "A"),
                row("at_legal_entity_registration_number", CO, "entity_type", "ROBOT", "name", "A", "position", "MEMBER"),
                row("at_legal_entity_registration_number", CO, "entity_type", "NATURAL_PERSON", "position", "MEMBER"),
                row("at_legal_entity_registration_number", CO, "entity_type", "LEGAL_ENTITY", "name", "A", "position", "MEMBER")));

        assertThat(cleaned.rows()).isEmpty();
        assertThat(cleaned.droppedByRule()).containsOnly(
                Map.entry("officers.invalid_company", 1L), Map.entry("officers.no_position", 1L),
                Map.entry("officers.unknown_entity_type", 1L), Map.entry("officers.missing_name", 1L),
                Map.entry("officers.invalid_holder_regcode", 1L));
    }

    @Test
    void personWithoutCodeOrBirthDateHasNoKey() {
        Cleaned<Role.Officer> cleaned = RoleNormalizers.officers(Stream.of(row("at_legal_entity_registration_number", CO,
                "entity_type", "NATURAL_PERSON", "name", "Test Person", "position", "MEMBER")));

        assertThat(cleaned.rows().getFirst().holder()).isInstanceOfSatisfying(Party.NaturalPerson.class,
                p -> assertThat(p.key()).isEmpty());
    }

    @Test
    void membersWithJointOwners() {
        Stream<Row> members = Stream.of(
                row("id", "1", "at_legal_entity_registration_number", CO, "entity_type", "JOINT_OWNERS",
                        "number_of_shares", "100.00", "share_nominal_value", "1", "share_currency", "EUR", "date_from", "2019-04-05"),
                row("id", "2", "at_legal_entity_registration_number", CO, "entity_type", "JOINT_OWNERS", "number_of_shares", "5"),
                row("id", "3", "at_legal_entity_registration_number", CO, "entity_type", "JOINT_OWNERS", "number_of_shares", "5"),
                row("id", "4", "at_legal_entity_registration_number", CO, "entity_type", "NATURAL_PERSON", "name", "Solo Owner",
                        "birth_date", "1980-02-03", "number_of_shares", "x"));
        Stream<Row> joint = Stream.of(
                row("member_id", "1", "entity_type", "NATURAL_PERSON", "name", "Owner One", "latvian_identity_number_masked", "010101-*****"),
                row("member_id", "1", "entity_type", "LEGAL_ENTITY", "name", "SIA \"Two\"", "legal_entity_registration_number", "40003000009"),
                row("member_id", "2", "entity_type", "FOREIGN_ENTITY", "name", "Only One Ltd"),
                row("member_id", "9", "entity_type", "ROBOT", "name", "R"));

        Cleaned<Role.Shareholder> cleaned = RoleNormalizers.members(members, joint);

        assertThat(cleaned.rows()).hasSize(2);
        Role.Shareholder jointShare = cleaned.rows().get(0);
        assertThat(jointShare.holder()).isInstanceOfSatisfying(Party.JointOwners.class, j -> assertThat(j.owners()).hasSize(2));
        assertThat(jointShare.shares()).isEqualTo(new BigDecimal("100"));
        assertThat(jointShare.nominalValue()).contains(new Money(BigDecimal.ONE, "EUR"));
        assertThat(jointShare.register()).isEqualTo(Role.ShareRegister.MEMBERS);
        assertThat(cleaned.rows().get(1).holder()).isEqualTo(new Party.ForeignEntity("Only One Ltd"));
        assertThat(cleaned.flaggedByRule()).containsOnly(Map.entry("members.single_joint_owner", 1L));
        assertThat(cleaned.droppedByRule()).containsOnly(Map.entry("members.joint_owners_missing", 1L),
                Map.entry("members.invalid_shares", 1L), Map.entry("members_joint_owners.unknown_entity_type", 1L));
    }

    @Test
    void depositoryStockholdersAreLatvianEntities() {
        Cleaned<Role.Shareholder> cleaned = RoleNormalizers.stockholders(Stream.of(
                row("id", "7", "at_legal_entity_registration_number", CO, "entity_type", "DEPOSITORY", "number_of_shares", "1000",
                        "depository_registration_number", "40003242879", "depository_name", "Depository AS")), Stream.empty());

        assertThat(cleaned.rows()).singleElement().satisfies(s -> {
            assertThat(s.holder()).isEqualTo(new Party.LatvianEntity(new RegNumber("40003242879"), "Depository AS"));
            assertThat(s.register()).isEqualTo(Role.ShareRegister.STOCKHOLDERS);
        });
    }

    @Test
    void beneficialOwnersWithSeveralNationalities() {
        Cleaned<Role.BeneficialOwner> cleaned = RoleNormalizers.beneficialOwners(Stream.of(
                row("legal_entity_registration_number", CO, "forename", "Test", "surname", "Person",
                        "latvian_identity_number_masked", "010101-*****", "nationality", "LV;US", "residence", "LV",
                        "registered_on", "2021-06-07"),
                row("legal_entity_registration_number", CO, "forename", "Other", "birth_date", "1975-03",
                        "nationality", "LV;Latvia", "residence", "Latvia"),
                row("legal_entity_registration_number", CO, "nationality", "LV")));

        assertThat(cleaned.rows()).hasSize(2);
        assertThat(cleaned.rows().get(0).nationalities()).containsExactlyInAnyOrder("LV", "US");
        assertThat(cleaned.rows().get(0).residence()).contains("LV");
        assertThat(cleaned.rows().get(1).holder().name()).isEqualTo("Other");
        assertThat(cleaned.rows().get(1).holder().key()).isPresent();
        assertThat(cleaned.rows().get(1).nationalities()).containsExactly("LV");
        assertThat(cleaned.flaggedByRule()).containsOnly(Map.entry("beneficial_owners.invalid_nationality", 1L),
                Map.entry("beneficial_owners.invalid_residence", 1L));
        assertThat(cleaned.droppedByRule()).containsOnly(Map.entry("beneficial_owners.missing_name", 1L));
    }

    @Test
    void theSamePersonGetsTheSameKeyInEveryFile() {
        Role.Officer officer = RoleNormalizers.officers(Stream.of(row("at_legal_entity_registration_number", CO,
                "entity_type", "NATURAL_PERSON", "name", "TEST  person", "latvian_identity_number_masked", "010101-*****",
                "position", "MEMBER"))).rows().getFirst();
        Role.BeneficialOwner owner = RoleNormalizers.beneficialOwners(Stream.of(row("legal_entity_registration_number", CO,
                "forename", "Test", "surname", "Person", "latvian_identity_number_masked", "010101-*****"))).rows().getFirst();

        assertThat(((Party.NaturalPerson) officer.holder()).key()).isEqualTo(owner.holder().key());
        assertThat(owner.company()).isEqualTo(COMPANY);
        assertThat(owner.nationalities()).isEqualTo(Set.of());
        assertThat(owner.residence()).isEqualTo(Optional.empty());
    }
}
