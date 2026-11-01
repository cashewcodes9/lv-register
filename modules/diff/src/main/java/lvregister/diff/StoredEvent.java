package lvregister.diff;

import java.util.regex.Pattern;
import lvregister.domain.CompanyEvent;

/** An event with its deterministic id. */
public record StoredEvent(String id, CompanyEvent event) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public StoredEvent {
        if (id == null || !SHA256.matcher(id).matches() || event == null) {
            throw new IllegalArgumentException("stored event needs a sha256 id and an event");
        }
    }
}
