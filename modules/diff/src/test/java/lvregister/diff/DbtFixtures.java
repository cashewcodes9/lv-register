package lvregister.diff;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lvregister.ingest.SourceFile;

/**
 * Three synthetic captures for the dbt fixtures (transform/fixtures/lake). Invented names and
 * codes only. Together they produce every event type, plus formatting noise that must not
 * produce events, so the SQL diff in dbt can be checked against the Java diff.
 *
 * <pre>
 * 40003000001 A  D2: renamed, new address, officer added/removed, member shares changed
 * 40003000002 B  D2: liquidated (closed L, terminated): only TERMINATED, its officer vanishes
 * 40003000003 C  D2: registered
 * 40003000004 D  D2: name with double space and NBSP (no event)
 * 40003000005 E  D2: liquidation opened (STATUS_CHANGED); D3: insolvency opened (STATUS_CHANGED)
 * 40003000006 F  D2: legal form SIA to AS
 * 012345678   G  sole proprietor with a representative without position; D3: BO added
 * 40003000008 H  D3: missing from the register
 * 40003000009 R  D3: reorganized (closed R, no date): TERMINATED
 * </pre>
 */
final class DbtFixtures {

    static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    static final LocalDate D2 = LocalDate.of(2026, 11, 9);
    static final LocalDate D3 = LocalDate.of(2026, 11, 16);
    static final List<LocalDate> DATES = List.of(D1, D2, D3);

    private static final String REGISTER_HEADER =
            "regcode;sepa;name;name_before_quotes;name_in_quotes;name_after_quotes;without_quotes;regtype;regtype_text;"
                    + "type;type_text;registered;terminated;closed;address;index;addressid;region;city;atvk;reregistration_term\n";

    private DbtFixtures() {}

    private static String company(String reg, String name, String type, String registered, String terminated, String closed,
            String address, String index, String addressId) {
        return String.join(";", reg, "", "\"" + name.replace("\"", "\"\"") + "\"", "", "", "", "0", "K", "", type, "",
                registered, terminated, closed, address, index, addressId, "0", "0", "0001000", "") + "\n";
    }

    static Map<SourceFile, String> capture(LocalDate date) {
        boolean d2 = !date.isBefore(D2);
        boolean d3 = !date.isBefore(D3);
        StringBuilder register = new StringBuilder(REGISTER_HEADER);
        register.append(company("40003000001", d2 ? "SIA \"Alfa Plus\"" : "SIA \"Alfa\"", "SIA", "2010-01-02", "", " ",
                d2 ? "Rīga, Jaunā iela 5" : "Rīga, Testa iela 1", "1050", d2 ? "200" : "100"));
        register.append(company("40003000002", "SIA \"Beta\"", "SIA", "2011-01-02", d2 ? "2026-11-05" : "", d2 ? "L" : " ",
                "Rīga, Testa iela 2", "1050", "101"));
        if (d2) {
            register.append(company("40003000003", "SIA \"Gamma\"", "SIA", "2026-11-04", "", " ", "Jelgava, Lielā iela 3", "3001", "300"));
        }
        register.append(company("40003000004", d2 ? "SIA  \"Delta\" " : "SIA \"Delta\"", "SIA", "2012-05-06", "", " ",
                "Rīga, Testa iela 4", "1050", "104"));
        register.append(company("40003000005", "AS \"Epsilon\"", "AS", "2005-03-04", "", " ", "Rīga, Testa iela 5", "1050", "105"));
        register.append(company("40003000006", "Zeta", d2 ? "AS" : "SIA", "2015-01-01", "", " ", "", "", "0"));
        register.append(company("012345678", "IK \"Ķekava\"", "IK", "1995-02-03", "", " ", "Ķekava, Rīgas iela 7", "2123", "107"));
        if (!d3) {
            register.append(company("40003000008", "SIA \"Eta\"", "SIA", "2016-01-01", "", " ", "Rīga, Testa iela 8", "1050", "108"));
        }
        register.append(company("40003000009", "SIA \"Theta\"", "SIA", "2017-01-01", "", d3 ? "R" : " ", "Rīga, Testa iela 9", "1050", "109"));

        String officerHeader = "id;uri;at_legal_entity_registration_number;entity_type;position;governing_body;name;"
                + "latvian_identity_number_masked;birth_date;legal_entity_registration_number;rights_of_representation_type;"
                + "representation_with_at_least;registered_on;last_modified_at\n";
        StringBuilder officers = new StringBuilder(officerHeader);
        officers.append("o1;;40003000001;NATURAL_PERSON;BOARD_MEMBER;EXECUTIVE_BOARD;Test Person;010101-*****;;;INDIVIDUALLY;;2020-01-01;\n");
        if (!d2) {
            officers.append("o2;;40003000001;LEGAL_ENTITY;BOARD_MEMBER;EXECUTIVE_BOARD;SIA \"\"Board\"\";;;40003999999;INDIVIDUALLY;;2021-01-01;\n");
            officers.append("o3;;40003000002;NATURAL_PERSON;BOARD_MEMBER;EXECUTIVE_BOARD;Beta Person;030303-*****;;;INDIVIDUALLY;;2019-01-01;\n");
        } else {
            officers.append("o4;;40003000001;NATURAL_PERSON;CHAIR_OF_BOARD;EXECUTIVE_BOARD;Other Person;;1980-02-03;;INDIVIDUALLY;;2026-11-03;\n");
        }
        officers.append("o5;;012345678;NATURAL_PERSON;;;Owner Person;040404-*****;;;INDIVIDUALLY;;1995-02-03;\n");
        officers.append("o6;;40003000004;FOREIGN_ENTITY;BOARD_MEMBER;;Delta Ltd;;;;INDIVIDUALLY;;2012-05-06;\n");

        String holder = "entity_type;name;latvian_identity_number_masked;birth_date;legal_entity_registration_number";
        String members = "id;uri;at_legal_entity_registration_number;" + holder
                + ";number_of_shares;share_nominal_value;share_currency;date_from;registered_on;last_modified_at\n"
                + "m1;;40003000001;NATURAL_PERSON;Test Person;010101-*****;;;" + (d2 ? "60" : "100.00") + ";1;EUR;2015-01-01;;\n"
                + "m2;;40003000001;JOINT_OWNERS;;;;;40;1.00;EUR;2015-01-01;;\n"
                + "m3;;40003000004;LEGAL_ENTITY;SIA \"\"Holder\"\";;;40003999999;2000;1;EUR;2012-05-06;;\n";
        String jointMembers = "id;member_id;" + holder + "\n"
                + "j1;m2;NATURAL_PERSON;Joint One;050505-*****;;\n"
                + "j2;m2;FOREIGN_ENTITY;Joint Two Ltd;;;\n";
        String stockholders = "id;at_legal_entity_registration_number;" + holder
                + ";number_of_shares;share_nominal_value;share_currency;votes;stock_type;depository_registration_number;"
                + "depository_name;date_from;registered_on;last_modified_at\n"
                + "s1;40003000005;DEPOSITORY;;;;;1000000;1;EUR;;DEMATERIALISED_STOCKS;40003242879;Depository AS;2005-03-04;;\n"
                + "s2;40003000005;NATURAL_PERSON;Stock Person;060606-*****;;;100;1;EUR;;REGISTERED_STOCKS;;;2005-03-04;;\n";
        String jointStock = "id;member_id;" + holder + "\nj9;none;FOREIGN_ENTITY;Nobody Ltd;;;\n";
        StringBuilder owners = new StringBuilder("id;legal_entity_registration_number;forename;surname;"
                + "latvian_identity_number_masked;birth_date;nationality;residence;registered_on;last_modified_at\n");
        owners.append("b1;40003000001;Test;Person;010101-*****;;LV;LV;2018-01-01;\n");
        owners.append("b2;40003000004;Multi;Nation;;1975-03;LV;US;US;2019-01-01;\n".replace("LV;US;US", "\"LV;US\";US"));
        if (d3) {
            owners.append("b3;012345678;Owner;Person;040404-*****;;LV;LV;2026-11-10;\n");
        }
        String equity = "id;legal_entity_registration_number;equity_capital_type;equity_capital_type_text;amount;currency;date_from;last_modified_at\n"
                + "e1;40003000001;PAID_UP_EQUITY;;2800.00;EUR;2010-01-02;\n"
                + "e2;40003000005;REGISTERED_EQUITY;;35000;EUR;2005-03-04;\n";
        StringBuilder liquidations = new StringBuilder(
                "id;legal_entity_registration_number;liquidation_type;liquidation_type_text;date_from;grounds_for_liquidation;registered_on;last_modified_at\n");
        liquidations.append("l0;40003000099;LIQUIDATION;;2020-01-01;\"multi\nline\";;\n");
        if (d2) {
            liquidations.append("l1;40003000005;LIQUIDATION;;2026-11-06;;;\n");
        }
        StringBuilder insolvency = new StringBuilder("proceeding_id;debtor_name;debtor_registration_number;debtor_cleaned_name;"
                + "debtor_address;proceeding_started_on;proceeding_ended_on;proceeding_form;proceeding_type\n");
        insolvency.append("p0;Old;40003000006;;;2010-01-01;2011-01-01;INSOLVENCY;NATIONAL\n");
        if (d3) {
            insolvency.append("p1;Epsilon;40003000005;;;2026-11-12;;INSOLVENCY;NATIONAL\n");
        }
        String reorganizations = "id;reorganization_type;reorganization_type_text;source_entity_regcode;final_entity_regcode;registered\n"
                + "r1;ACQUISITION;;40003000098;40003000001;2015-01-01\n"
                + "r2;ACQUISITION;;1000123456;40003000001;1999-01-01\n";

        Map<SourceFile, String> files = new EnumMap<>(SourceFile.class);
        files.put(SourceFile.REGISTER, register.toString());
        files.put(SourceFile.OFFICERS, officers.toString());
        files.put(SourceFile.MEMBERS, members);
        files.put(SourceFile.MEMBERS_JOINT_OWNERS, jointMembers);
        files.put(SourceFile.STOCKHOLDERS, stockholders);
        files.put(SourceFile.STOCKHOLDERS_JOINT_OWNERS, jointStock);
        files.put(SourceFile.BENEFICIAL_OWNERS, owners.toString());
        files.put(SourceFile.EQUITY_CAPITALS, equity);
        files.put(SourceFile.LIQUIDATIONS, liquidations.toString());
        files.put(SourceFile.INSOLVENCY_LEGAL_PERSON_PROCEEDING, insolvency.toString());
        files.put(SourceFile.REORGANIZATIONS, reorganizations);
        return files;
    }
}
