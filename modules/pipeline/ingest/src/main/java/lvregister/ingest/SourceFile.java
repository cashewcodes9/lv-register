package lvregister.ingest;

import java.util.Locale;

/** The UR files of the MVP (source assessment section 7). The CSV name is the CKAN file name. */
public enum SourceFile {
    REGISTER,
    OFFICERS,
    MEMBERS,
    MEMBERS_JOINT_OWNERS,
    STOCKHOLDERS,
    STOCKHOLDERS_JOINT_OWNERS,
    BENEFICIAL_OWNERS,
    EQUITY_CAPITALS,
    LIQUIDATIONS,
    INSOLVENCY_LEGAL_PERSON_PROCEEDING,
    REORGANIZATIONS;

    /** Base name, e.g. {@code members_joint_owners}; also the lake table name. */
    public String baseName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String csvName() {
        return baseName() + ".csv";
    }
}
