package lvregister.normalize;

import java.time.LocalDate;
import java.util.Optional;
import lvregister.domain.CompanyStatus;

/**
 * Combines {@code register.closed}, the termination date and the open proceedings into one
 * status (source assessment 4.1). Precedence, first match wins:
 * <ol>
 *   <li>{@code closed = L} → TERMINATED; {@code closed = R} → REORGANIZED</li>
 *   <li>a termination date without a closed flag (646 legacy rows) → TERMINATED, flagged</li>
 *   <li>open INSOLVENCY → INSOLVENT</li>
 *   <li>open LEGAL_PROTECTION or OUT_OF_COURT_LEGAL_PROTECTION → LEGAL_PROTECTION</li>
 *   <li>open liquidation → IN_LIQUIDATION</li>
 *   <li>otherwise ACTIVE</li>
 * </ol>
 * Insolvency comes before liquidation because an insolvent company is usually also being
 * wound up, and insolvency is the stronger signal. An unknown closed code or proceeding form is
 * flagged.
 */
public final class Statuses {

    public static final String TERMINATED_WITHOUT_FLAG = "register.terminated_without_closed_flag";
    public static final String UNMAPPED_CLOSED = "register.unmapped_closed_flag";
    public static final String UNMAPPED_FORM = "insolvency.unmapped_form";

    private Statuses() {}

    /** {@code closed}: the raw value, where a single space (or nothing) means active. */
    public static CompanyStatus of(Optional<String> closed, Optional<LocalDate> terminated, OpenProceedings open, Row row) {
        String flag = closed.map(String::strip).orElse("");
        switch (flag) {
            case "L" -> {
                return CompanyStatus.TERMINATED;
            }
            case "R" -> {
                return CompanyStatus.REORGANIZED;
            }
            case "" -> { }
            default -> row.flag(UNMAPPED_CLOSED);
        }
        if (terminated.isPresent()) {
            row.flag(TERMINATED_WITHOUT_FLAG);
            return CompanyStatus.TERMINATED;
        }
        if (open.insolvencyForms().contains("INSOLVENCY")) {
            return CompanyStatus.INSOLVENT;
        }
        if (open.insolvencyForms().contains("LEGAL_PROTECTION") || open.insolvencyForms().contains("OUT_OF_COURT_LEGAL_PROTECTION")) {
            return CompanyStatus.LEGAL_PROTECTION;
        }
        if (open.inLiquidation()) {
            return CompanyStatus.IN_LIQUIDATION;
        }
        return CompanyStatus.ACTIVE;
    }

    /** Whether a proceeding form is one this mapping knows. */
    static boolean knownForm(String form) {
        return form.equals("INSOLVENCY") || form.equals("LEGAL_PROTECTION") || form.equals("OUT_OF_COURT_LEGAL_PROTECTION");
    }
}
