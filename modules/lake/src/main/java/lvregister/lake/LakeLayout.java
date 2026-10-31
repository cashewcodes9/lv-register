package lvregister.lake;

import java.time.LocalDate;
import lvregister.ingest.SourceFile;

/** Keys in the lake bucket: {@code <file>/date=YYYY-MM-DD/<file>.parquet} (Hive partitioning). */
public final class LakeLayout {

    private LakeLayout() {}

    public static String parquetKey(SourceFile file, LocalDate date) {
        return file.baseName() + "/date=" + date + "/" + file.baseName() + ".parquet";
    }
}
