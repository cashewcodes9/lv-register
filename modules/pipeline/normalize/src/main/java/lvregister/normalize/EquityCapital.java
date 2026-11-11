package lvregister.normalize;

import lvregister.domain.Money;
import lvregister.domain.RegNumber;

/** One row of {@code equity_capitals.csv}: a capital amount of a given type. */
public record EquityCapital(RegNumber regNumber, String type, Money amount) {}
