package lvregister.serving;

import java.time.LocalDate;

/** A change event as written by the diff step; the payload is its canonical JSON object. */
public record EventView(String id, String type, String regNumber, LocalDate previous, LocalDate current, String payloadJson) {}
