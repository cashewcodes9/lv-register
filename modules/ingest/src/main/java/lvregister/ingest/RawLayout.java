package lvregister.ingest;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lvregister.storage.ObjectStore;

/** Keys in the raw bucket: {@code date=YYYY-MM-DD/<file>.csv} and {@code date=YYYY-MM-DD/manifest.json}. */
public final class RawLayout {

    private static final Pattern MANIFEST = Pattern.compile("date=(\\d{4}-\\d{2}-\\d{2})/manifest\\.json");

    private RawLayout() {}

    public static String prefix(LocalDate date) {
        return "date=" + date + "/";
    }

    public static String csvKey(LocalDate date, SourceFile file) {
        return prefix(date) + file.csvName();
    }

    public static String manifestKey(LocalDate date) {
        return prefix(date) + "manifest.json";
    }

    /** Every capture ever made, oldest first: manifests are never pruned. */
    public static List<LocalDate> captures(ObjectStore raw) throws IOException {
        return raw.list("date=").stream().map(MANIFEST::matcher).filter(Matcher::matches)
                .map(m -> LocalDate.parse(m.group(1))).sorted().toList();
    }
}
