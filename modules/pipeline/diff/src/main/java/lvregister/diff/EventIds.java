package lvregister.diff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import lvregister.domain.CompanyEvent;

/**
 * Event id = {@code sha256(regNumber | type | snapshotDate | canonicalPayload)} (PLAN.md
 * section 9), where the snapshot date is the current capture. Re-running a capture gives the
 * same ids.
 */
public final class EventIds {

    private EventIds() {}

    public static String of(CompanyEvent event) {
        String text = String.join("|", event.regNumber().value(), EventJson.type(event),
                event.observed().current().toString(), EventJson.write(EventJson.payload(event)));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The events with ids, sorted by id, as they are written. Fails on an id collision. */
    public static List<StoredEvent> store(List<CompanyEvent> events) {
        List<StoredEvent> stored = events.stream().map(e -> new StoredEvent(of(e), e))
                .sorted(Comparator.comparing(StoredEvent::id)).toList();
        for (int i = 1; i < stored.size(); i++) {
            if (stored.get(i).id().equals(stored.get(i - 1).id())) {
                throw new IllegalStateException("two events with the same id " + stored.get(i).id());
            }
        }
        return stored;
    }
}
