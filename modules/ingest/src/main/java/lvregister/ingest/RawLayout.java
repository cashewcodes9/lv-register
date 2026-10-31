package lvregister.ingest;

import java.time.LocalDate;

/** Keys in the raw bucket: {@code date=YYYY-MM-DD/<file>.csv} and {@code date=YYYY-MM-DD/manifest.json}. */
public final class RawLayout {

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
}
