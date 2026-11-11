package lvregister.normalize;

import java.util.Set;

/**
 * Open status processes of one company: a liquidation from {@code liquidations.csv} and the
 * forms of open proceedings from {@code insolvency_legal_person_proceeding.csv}.
 */
public record OpenProceedings(boolean inLiquidation, Set<String> insolvencyForms) {

    public static final OpenProceedings NONE = new OpenProceedings(false, Set.of());

    public OpenProceedings {
        insolvencyForms = Set.copyOf(insolvencyForms);
    }
}
