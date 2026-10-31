package lvregister.diff;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lvregister.storage.ObjectStore;

/** The capture dates in the lake, from the keys of the register file (one per capture). */
public final class LakeCaptures {

    private static final Pattern KEY = Pattern.compile("register/date=(\\d{4}-\\d{2}-\\d{2})/register\\.parquet");

    private LakeCaptures() {}

    public static List<LocalDate> dates(ObjectStore lake) throws IOException {
        return lake.list("register/").stream()
                .map(KEY::matcher)
                .filter(Matcher::matches)
                .map(m -> LocalDate.parse(m.group(1)))
                .sorted()
                .toList();
    }

    /** The latest capture before {@code date}: the one {@code diff} compares with. */
    public static Optional<LocalDate> previous(List<LocalDate> captures, LocalDate date) {
        return captures.stream().filter(d -> d.isBefore(date)).max(LocalDate::compareTo);
    }
}
