package lvregister.quality;

import java.util.List;
import java.util.Map;
import lvregister.ingest.SourceFile;

/** Columns a row cannot do without; the report counts their NULLs per capture. */
final class RequiredColumns {

    static final Map<SourceFile, List<String>> BY_FILE = Map.ofEntries(
            Map.entry(SourceFile.REGISTER, List.of("regcode", "name", "type")),
            Map.entry(SourceFile.OFFICERS, List.of("at_legal_entity_registration_number", "entity_type", "name")),
            Map.entry(SourceFile.MEMBERS, List.of("at_legal_entity_registration_number", "entity_type", "number_of_shares")),
            Map.entry(SourceFile.MEMBERS_JOINT_OWNERS, List.of("member_id", "entity_type", "name")),
            Map.entry(SourceFile.STOCKHOLDERS, List.of("at_legal_entity_registration_number", "entity_type", "number_of_shares")),
            Map.entry(SourceFile.STOCKHOLDERS_JOINT_OWNERS, List.of("member_id", "entity_type", "name")),
            Map.entry(SourceFile.BENEFICIAL_OWNERS, List.of("legal_entity_registration_number", "forename", "surname")),
            Map.entry(SourceFile.EQUITY_CAPITALS, List.of("legal_entity_registration_number", "equity_capital_type", "amount", "currency")),
            Map.entry(SourceFile.LIQUIDATIONS, List.of("legal_entity_registration_number")),
            Map.entry(SourceFile.INSOLVENCY_LEGAL_PERSON_PROCEEDING, List.of("debtor_registration_number", "proceeding_form")),
            Map.entry(SourceFile.REORGANIZATIONS, List.of("source_entity_regcode", "final_entity_regcode")));

    private RequiredColumns() {}
}
