package io.github.cashewcodes9.lvregister.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** The two capture dates an event was seen between: previous capture and this capture. */
public record ObservedBetween(LocalDate previous, LocalDate current) {

    public ObservedBetween {
        Checks.notNull(previous, "previous");
        Checks.notNull(current, "current");
        if (!previous.isBefore(current)) {
            throw new IllegalArgumentException("previous capture must be before the current one");
        }
    }

    public long gapDays() {
        return ChronoUnit.DAYS.between(previous, current);
    }
}
