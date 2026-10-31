package lvregister.serving;

import java.util.List;
import java.util.stream.Stream;

/** Write side of the serving store, used by the index step. All writes are upserts by key. */
public interface ServingWriter {

    /** Creates or migrates the schema (tables, indexes, extensions). Safe to call every run. */
    void prepare();

    /** What an upsert did: rows written, and rows skipped because nothing changed or they were newer. */
    record Upserted(long written, long unchanged) {}

    /**
     * Upserts companies by registration number. A company is only replaced by a view with the
     * same or a later {@code asOf}, so indexing an older capture never rolls back newer data.
     */
    Upserted upsertCompanies(Stream<CompanyView> companies);

    /** Upserts events by id. */
    Upserted upsertEvents(List<EventView> events);

    /** Upserts a run by run id. */
    void upsertRun(RunView run);
}
