package lvregister.domain;

import java.math.BigDecimal;

/** Canonical decimals: trailing zeros removed, so 100.00 equals 100, but never scientific (1E+2). */
final class Decimals {

    private Decimals() {}

    static BigDecimal canonical(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
