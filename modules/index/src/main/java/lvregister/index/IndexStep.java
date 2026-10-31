package lvregister.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import lvregister.diff.DiffStep;
import lvregister.domain.Snapshot;
import lvregister.lake.SnapshotLoader;
import lvregister.quality.QualityReport;
import lvregister.serving.EventView;
import lvregister.serving.RunView;
import lvregister.serving.ServingWriter;
import lvregister.serving.Views;
import lvregister.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code index} step: upserts the companies of a capture (from its snapshot), its events
 * and its run report into the serving store, through {@link ServingWriter} only. The run is
 * written last, so a run in the store means its data is there. Re-running changes nothing.
 */
public final class IndexStep {

    private static final Logger LOG = LoggerFactory.getLogger(IndexStep.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectStore lake;
    private final SnapshotLoader loader;
    private final ServingWriter writer;

    public IndexStep(ObjectStore lake, SnapshotLoader loader, ServingWriter writer) {
        this.lake = lake;
        this.loader = loader;
        this.writer = writer;
    }

    /** Rows written and unchanged per kind. */
    public record Result(ServingWriter.Upserted companies, ServingWriter.Upserted events) {}

    public Result run(LocalDate date) throws IOException, SQLException {
        if (!lake.exists(QualityReport.key(date))) {
            throw new IllegalStateException("no quality report for " + date + ": run quality first");
        }
        byte[] reportJson = lake.read(QualityReport.key(date));
        QualityReport report = QualityReport.fromJson(reportJson);
        if (report.status() == QualityReport.Status.FAILED) {
            throw new IllegalStateException("quality report for " + date + " is FAILED: not indexing");
        }
        writer.prepare();
        Snapshot snapshot = loader.load(date).snapshot();
        ServingWriter.Upserted companies = writer.upsertCompanies(snapshot.companies().values().stream()
                .map(c -> Views.company(c, snapshot.rolesOf(c.regNumber()), date)));
        ServingWriter.Upserted events = writer.upsertEvents(events(date));
        writer.upsertRun(new RunView(report.runId(), date, report.status().name(),
                new String(reportJson, StandardCharsets.UTF_8)));
        LOG.info("index {}: companies {} written, {} unchanged; events {} written, {} already there; run {} ({})",
                date, companies.written(), companies.unchanged(), events.written(), events.unchanged(),
                report.runId(), report.status());
        return new Result(companies, events);
    }

    List<EventView> events(LocalDate date) throws IOException {
        String text = new String(lake.read(DiffStep.eventsKey(date)), StandardCharsets.UTF_8);
        return text.lines().filter(l -> !l.isBlank()).map(IndexStep::event).toList();
    }

    /** One JSON Lines record of the diff step; the payload keeps its canonical text. */
    static EventView event(String line) {
        JsonNode n = JSON.readTree(line);
        return new EventView(n.path("id").asString(), n.path("type").asString(), n.path("regNumber").asString(),
                LocalDate.parse(n.path("previous").asString()), LocalDate.parse(n.path("current").asString()),
                JSON.writeValueAsString(n.path("payload")));
    }
}
