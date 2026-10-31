package lvregister.domain;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** An amount with an ISO 4217 currency (UR uses EUR and the former LVL). */
public record Money(BigDecimal amount, String currency) {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public Money {
        Checks.notNull(amount, "amount");
        Checks.notNull(currency, "currency");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code: " + currency);
        }
        amount = amount.stripTrailingZeros();
    }
}
