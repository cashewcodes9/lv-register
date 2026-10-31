package lvregister.normalize;

import java.time.LocalDate;
import java.util.Optional;
import lvregister.domain.RegNumber;

/** A merger, transformation, division etc.: {@code source} continues in {@code target}. */
public record Reorganization(String type, RegNumber source, RegNumber target, Optional<LocalDate> registered) {}
