package lvregister.normalize;

import java.time.LocalDate;
import java.util.Optional;
import lvregister.domain.RegNumber;

/** An open liquidation process ({@code liquidations.csv} lists only open ones). */
public record Liquidation(RegNumber regNumber, String type, Optional<LocalDate> since) {}
