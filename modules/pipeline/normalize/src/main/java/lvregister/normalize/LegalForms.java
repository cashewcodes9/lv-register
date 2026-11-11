package lvregister.normalize;

import static lvregister.domain.LegalFamily.*;

import java.util.Map;
import java.util.Optional;
import lvregister.domain.LegalFamily;
import lvregister.domain.LegalForm;

/**
 * Maps the UR legal form code ({@code register.type}) to a North-Data-style family. The table
 * holds every code found in the 2026-10-31 capture (source assessment 3.1). A code not in the
 * table maps to {@link LegalFamily#OTHER} and is flagged, never dropped.
 */
public final class LegalForms {

    public static final String UNMAPPED = "register.unmapped_legal_form";

    static final Map<String, LegalFamily> FAMILIES = Map.ofEntries(
            // companies
            Map.entry("SIA", LLC),                     // Sabiedrība ar ierobežotu atbildību
            Map.entry("AS", CORPORATION),              // Akciju sabiedrība
            Map.entry("SE", CORPORATION),              // Eiropas komercsabiedrība
            Map.entry("PS", GENERAL_PARTNERSHIP),      // Pilnsabiedrība
            Map.entry("LIG", GENERAL_PARTNERSHIP),     // Līgumsabiedrība ar pilnu atbildību (legacy)
            Map.entry("KS", LIMITED_PARTNERSHIP),      // Komandītsabiedrība
            Map.entry("IK", SOLE_PROPRIETORSHIP),      // Individuālais komersants
            Map.entry("IND", SOLE_PROPRIETORSHIP),     // Individuālais uzņēmums (legacy)
            Map.entry("ZEM", SOLE_PROPRIETORSHIP),     // Zemnieku saimniecība (farm)
            Map.entry("ZVJ", SOLE_PROPRIETORSHIP),     // Zvejnieku saimniecība (fishery)
            Map.entry("KB", COOPERATIVE),              // Kooperatīvā sabiedrība
            Map.entry("KBS", COOPERATIVE),             // Kooperatīvo biedrību savienība
            Map.entry("PAJ", COOPERATIVE),             // Paju sabiedrība (legacy)
            Map.entry("AKF", BRANCH),                  // Ārvalsts komersanta filiāle
            Map.entry("FIL", BRANCH),                  // Filiāle
            Map.entry("PAR", REPRESENTATION),          // Ārvalsts komersanta pārstāvniecība
            Map.entry("POR", REPRESENTATION),          // Ārvalsts organizācijas pārstāvniecība
            Map.entry("PRV", REPRESENTATION),          // Pārstāvis
            Map.entry("VU", PUBLIC_ENTERPRISE),        // Valsts uzņēmums (legacy)
            Map.entry("PSV", PUBLIC_ENTERPRISE),       // Pašvaldības uzņēmums (legacy)
            // associations, foundations, public organisations (register S), trade unions
            Map.entry("BDR", ASSOCIATION),             // Biedrība
            Map.entry("SAB", ASSOCIATION),             // Sabiedriskā organizācija
            Map.entry("SPO", ASSOCIATION),             // register S, no label
            Map.entry("ASF", ASSOCIATION),             // register S, no label
            Map.entry("KOR", ASSOCIATION),             // register S, no label
            Map.entry("SAA", ASSOCIATION),             // register S, no label
            Map.entry("SPA", ASSOCIATION),             // register S, no label
            Map.entry("PRO", ASSOCIATION),             // register S, no label
            Map.entry("ARB", ASSOCIATION),             // Arodbiedrība
            Map.entry("ARV", ASSOCIATION),             // Arodbiedrības patstāvīgā vienība
            Map.entry("ARA", ASSOCIATION),             // Arodbiedrību apvienība
            Map.entry("NOD", FOUNDATION),              // Nodibinājums
            // religious (register R)
            Map.entry("DRZ", RELIGIOUS_ORGANISATION),  // Draudze
            Map.entry("KAT", RELIGIOUS_ORGANISATION),  // Katoļu baznīcas publisko tiesību juridiskā persona
            Map.entry("BAZ", RELIGIOUS_ORGANISATION),  // Baznīca
            Map.entry("DIE", RELIGIOUS_ORGANISATION),  // Diecēze
            Map.entry("KLO", RELIGIOUS_ORGANISATION),  // Klosteris
            Map.entry("MIS", RELIGIOUS_ORGANISATION),  // Misija
            Map.entry("SAV", RELIGIOUS_ORGANISATION),  // Savienība
            Map.entry("ROI", RELIGIOUS_ORGANISATION),  // Iestāde
            Map.entry("RON", RELIGIOUS_ORGANISATION),  // Nodaļa
            // political
            Map.entry("PP", POLITICAL_ORGANISATION),   // Politiskā partija
            Map.entry("POL", POLITICAL_ORGANISATION),  // Politiska organizācija (partija)
            Map.entry("PPA", POLITICAL_ORGANISATION),  // Politisko partiju apvienība
            // known codes without a matching family
            Map.entry("MIL", OTHER),                   // Masu informācijas līdzeklis (mass media)
            Map.entry("SKT", OTHER),                   // Šķīrējtiesa (arbitration court)
            Map.entry("EIG", OTHER),                   // Eiropas ekonomisko interešu grupa (EEIG)
            Map.entry("GIM", OTHER),                   // Ģimenes uzņēmums (legacy)
            Map.entry("PAP", OTHER),                   // Sabiedrība ar papildu atbildību (legacy)
            Map.entry("KBU", OTHER),                   // Kooperatīvo biedrību uzņēmums (legacy)
            Map.entry("KSS", OTHER),                   // Kooperatīvo biedrību savienības uzņēmums (legacy)
            Map.entry("UZN", OTHER),                   // Uzņēmējsabiedrības uzņēmums (legacy)
            Map.entry("SOU", OTHER),                   // Sabiedriskās organizācijas uzņēmums (legacy)
            Map.entry("REL", OTHER));                  // Reliģiskas organizācijas uzņēmums (legacy)

    private LegalForms() {}

    /** The legal form for a cleaned, upper-case code; empty when the code is malformed. */
    public static Optional<LegalForm> of(String code, Row row) {
        if (!code.matches("[A-Z]{2,4}")) {
            return Optional.empty();
        }
        LegalFamily family = FAMILIES.get(code);
        if (family == null) {
            row.flag(UNMAPPED);
            return Optional.of(new LegalForm(code, OTHER));
        }
        return Optional.of(new LegalForm(code, family));
    }
}
