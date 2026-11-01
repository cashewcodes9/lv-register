package lvregister.normalize;

import java.time.LocalDate;
import java.util.Optional;
import lvregister.domain.RegNumber;

/** An insolvency or legal-protection proceeding; open while {@code ended} is empty. */
public record Proceeding(RegNumber regNumber, String form, Optional<LocalDate> started, Optional<LocalDate> ended) {

    public boolean isOpen() {
        return ended.isEmpty();
    }
}
