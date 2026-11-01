package lvregister.cli;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import lvregister.diff.DiffStep;
import lvregister.diff.LakeCaptures;
import lvregister.index.IndexStep;
import lvregister.index.MartReader;
import lvregister.ingest.CkanCatalog;
import lvregister.ingest.Downloader;
import lvregister.ingest.Http;
import lvregister.ingest.Manifest;
import lvregister.ingest.ManifestEntry;
import lvregister.ingest.RawLayout;
import lvregister.ingest.SourceFile;
import lvregister.lake.CaptureCounts;
import lvregister.lake.DuckDb;
import lvregister.lake.DuckDbSettings;
import lvregister.lake.LakeReader;
import lvregister.lake.LakeWriter;
import lvregister.lake.SnapshotLoader;
import lvregister.pipes.PipesMetadata;
import lvregister.quality.Checks;
import lvregister.quality.PruneStep;
import lvregister.quality.QualityReport;
import lvregister.quality.QualityStep;
import lvregister.quality.Thresholds;
import lvregister.serving.postgres.PostgresServing;
import lvregister.serving.postgres.PostgresSettings;
import lvregister.storage.ObjectStore;
import lvregister.storage.S3ObjectStore;
import lvregister.storage.S3Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Manual wiring of the real steps from environment variables (see {@code .env.example}).
 * Clients are created when a step runs, so {@code --help} needs no configuration.
 */
final class Wiring {

    private static final Logger LOG = LoggerFactory.getLogger(Wiring.class);

    private Wiring() {}

    static Steps production(Map<String, String> env, RunContext context) {
        return production(env, context, Clock.systemUTC());
    }

    /** As {@link #production(Map, RunContext)} with the clock of the download step (tests). */
    static Steps production(Map<String, String> env, RunContext context, Clock clock) {
        Thresholds thresholds = Thresholds.fromEnvironment(env);
        Map<StepName, Step> steps = new EnumMap<>(Steps.notImplemented().byName());
        steps.put(StepName.DOWNLOAD, date -> {
            try (S3Client s3 = S3ObjectStore.client(S3Settings.fromEnvironment(env))) {
                ObjectStore raw = raw(env, s3);
                Downloader downloader = new Downloader(Http.defaults(), catalogUrl(env), raw,
                        workDir(env).resolve("download"), clock);
                Downloader.Result result = downloader.run(date);
                LOG.info("download: {} files stored ({} bytes), {} already in the capture",
                        result.downloaded(), result.bytesDownloaded(), result.skipped());
                Manifest manifest = Manifest.fromJson(raw.read(RawLayout.manifestKey(date)));
                Optional<LocalDate> previous = LakeCaptures.previous(RawLayout.captures(raw), date);
                Optional<Manifest> previousManifest = previous.isEmpty() ? Optional.empty()
                        : Optional.of(Manifest.fromJson(raw.read(RawLayout.manifestKey(previous.get()))));
                Map<String, Object> sha256 = new TreeMap<>();
                manifest.files().forEach(e -> sha256.put(e.file(), e.sha256()));
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("files", (long) manifest.files().size());
                metadata.put("downloaded", (long) result.downloaded());
                metadata.put("skipped", (long) result.skipped());
                metadata.put("bytes_downloaded", result.bytesDownloaded());
                metadata.put("total_bytes", manifest.files().stream().mapToLong(ManifestEntry::size).sum());
                metadata.put("sha256", sha256);
                metadata.put("uri", new PipesMetadata.Url(raw.location(RawLayout.manifestKey(date))));
                return new StepOutcome(metadata, Checks.rawFiles(manifest, previousManifest, thresholds));
            }
        });
        steps.put(StepName.LAKE, date -> {
            S3Settings settings = S3Settings.fromEnvironment(env);
            try (S3Client s3 = S3ObjectStore.client(settings);
                    DuckDb db = DuckDb.open(DuckDbSettings.fromEnvironment(env), Optional.of(settings))) {
                ObjectStore lake = lake(env, s3);
                var results = new LakeWriter(db, raw(env, s3), lake, workDir(env).resolve("lake")).run(date);
                LOG.info("lake: {} files written, {} unchanged",
                        results.stream().filter(LakeWriter.FileResult::written).count(),
                        results.stream().filter(r -> !r.written()).count());
                LakeReader reader = new LakeReader(db, lake);
                Optional<LocalDate> previous = LakeCaptures.previous(LakeCaptures.dates(lake), date);
                Map<String, Object> rows = new TreeMap<>();
                results.forEach(r -> rows.put(r.file().baseName(), r.rows()));
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("rows", rows);
                metadata.put("files_written", results.stream().filter(LakeWriter.FileResult::written).count());
                metadata.put("previous_capture", previous.map(LocalDate::toString).orElse("none"));
                metadata.put("uri", new PipesMetadata.Url(lake.location("register/date=" + date + "/register.parquet")));
                return new StepOutcome(metadata, List.of(Checks.schema(columns(reader, date),
                        previous.isEmpty() ? Optional.empty() : Optional.of(columns(reader, previous.get())))));
            }
        });
        steps.put(StepName.DIFF, date -> {
            S3Settings settings = S3Settings.fromEnvironment(env);
            try (S3Client s3 = S3ObjectStore.client(settings);
                    DuckDb db = DuckDb.open(DuckDbSettings.fromEnvironment(env), Optional.of(settings))) {
                ObjectStore lake = lake(env, s3);
                List<LocalDate> inLake = LakeCaptures.dates(lake);
                TreeSet<LocalDate> made = new TreeSet<>(RawLayout.captures(raw(env, s3)));
                made.addAll(inLake);
                LakeCaptures.requirePreviousInLake(inLake, List.copyOf(made), date);
                DiffStep.Result result = new DiffStep(lake, new SnapshotLoader(new LakeReader(db, lake))).run(date);
                LOG.info("diff: {} events against {}", result.total(),
                        result.previous().map(Object::toString).orElse("nothing (baseline)"));
                long companies = CaptureCounts.fromJson(lake.read(CaptureCounts.key(date))).companies();
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("events", result.total());
                metadata.put("events_by_type", new TreeMap<String, Object>(result.eventsByType()));
                metadata.put("previous_capture", result.previous().map(LocalDate::toString).orElse("none (baseline)"));
                result.previous().ifPresent(p -> metadata.put("gap_days", ChronoUnit.DAYS.between(p, date)));
                metadata.put("companies", companies);
                metadata.put("uri", new PipesMetadata.Url(lake.location(DiffStep.eventsKey(date))));
                return new StepOutcome(metadata, List.of(Checks.events(result.eventsByType(), companies, thresholds)));
            }
        });
        steps.put(StepName.QUALITY, date -> {
            S3Settings settings = S3Settings.fromEnvironment(env);
            try (S3Client s3 = S3ObjectStore.client(settings);
                    DuckDb db = DuckDb.open(DuckDbSettings.fromEnvironment(env), Optional.of(settings))) {
                ObjectStore raw = raw(env, s3);
                ObjectStore lake = lake(env, s3);
                QualityReport report = new QualityStep(db, raw, lake, buckets(env, s3), thresholds, Clock.systemUTC())
                        .run(date, context.runId(), context.dagsterRunId());
                LOG.info("quality: {}", report.status());
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("status", report.status().name());
                metadata.put("warnings", (long) report.warnings().size());
                metadata.put("warning_codes", report.warnings().stream().map(QualityReport.Warning::code).distinct().toList());
                metadata.put("companies", report.snapshot().companies());
                metadata.put("events", report.eventsTotal());
                metadata.put("uri", new PipesMetadata.Url(lake.location(QualityReport.key(date))));
                return new StepOutcome(metadata, Checks.report(report));
            }
        });
        steps.put(StepName.INDEX, date -> {
            S3Settings settings = S3Settings.fromEnvironment(env);
            try (S3Client s3 = S3ObjectStore.client(settings);
                    DuckDb db = DuckDb.open(DuckDbSettings.fromEnvironment(env), Optional.of(settings));
                    var serving = servingStore(env)) {
                ObjectStore lake = lake(env, s3);
                MartReader marts = new MartReader(db, new S3ObjectStore(s3, bucket(env, "S3_BUCKET_MARTS", "lv-marts")));
                IndexStep.Result result = new IndexStep(lake, new SnapshotLoader(new LakeReader(db, lake)), serving, marts).run(date);
                long indexed = result.companies().written() + result.companies().unchanged();
                LocalDate latest = LakeCaptures.dates(lake).getLast();
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("companies_written", result.companies().written());
                metadata.put("companies_unchanged", result.companies().unchanged());
                metadata.put("events_written", result.events().written());
                metadata.put("events_unchanged", result.events().unchanged());
                metadata.put("serving_backend", env.getOrDefault("SERVING_BACKEND", "postgres"));
                return new StepOutcome(metadata, List.of(Checks.indexCounts(date, latest, indexed, marts.companies())));
            }
        });
        steps.put(StepName.PRUNE, date -> {
            try (S3Client s3 = S3ObjectStore.client(S3Settings.fromEnvironment(env))) {
                PruneStep.Result result = PruneStep.fromEnvironment(raw(env, s3), lake(env, s3), env).run();
                Map<String, Long> bucketBytes = new TreeMap<>();
                for (var bucket : buckets(env, s3).entrySet()) {
                    bucketBytes.put(bucket.getKey(), bucket.getValue().totalSize(""));
                }
                Map<String, Object> kept = new TreeMap<>();
                result.kept().forEach((d, why) -> kept.put(d.toString(), why));
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("raw_pruned", result.rawPruned().stream().map(LocalDate::toString).toList());
                metadata.put("lake_pruned", result.lakePruned().stream().map(LocalDate::toString).toList());
                metadata.put("kept_by_safety_rules", kept);
                metadata.put("bytes_freed", result.bytesFreed());
                metadata.put("bucket_bytes", new TreeMap<String, Object>(bucketBytes));
                return new StepOutcome(metadata, List.of(Checks.storage(bucketBytes, thresholds)));
            }
        });
        return new Steps(steps);
    }

    private static Map<String, List<String>> columns(LakeReader reader, LocalDate date) throws Exception {
        Map<String, List<String>> columns = new TreeMap<>();
        for (SourceFile file : SourceFile.values()) {
            columns.put(file.baseName(), reader.columns(file, date));
        }
        return columns;
    }

    /** Bucket name to store, for the storage figures (raw, lake, marts). */
    private static Map<String, ObjectStore> buckets(Map<String, String> env, S3Client s3) {
        return Map.of(
                bucket(env, "S3_BUCKET_RAW", "lv-raw"), raw(env, s3),
                bucket(env, "S3_BUCKET_LAKE", "lv-lake"), lake(env, s3),
                bucket(env, "S3_BUCKET_MARTS", "lv-marts"), new S3ObjectStore(s3, bucket(env, "S3_BUCKET_MARTS", "lv-marts")));
    }

    /** The serving backend behind the port, chosen by {@code SERVING_BACKEND} (PLAN.md section 4). */
    static PostgresServing servingStore(Map<String, String> env) {
        String backend = env.getOrDefault("SERVING_BACKEND", "postgres");
        if (!backend.equals("postgres")) {
            throw new IllegalArgumentException("unknown SERVING_BACKEND '" + backend + "': only postgres is implemented");
        }
        return PostgresServing.connect(PostgresSettings.fromEnvironment(env), 2);
    }

    private static String bucket(Map<String, String> env, String variable, String fallback) {
        return env.getOrDefault(variable, fallback);
    }

    private static ObjectStore raw(Map<String, String> env, S3Client s3) {
        return new S3ObjectStore(s3, env.getOrDefault("S3_BUCKET_RAW", "lv-raw"));
    }

    private static ObjectStore lake(Map<String, String> env, S3Client s3) {
        return new S3ObjectStore(s3, env.getOrDefault("S3_BUCKET_LAKE", "lv-lake"));
    }

    private static URI catalogUrl(Map<String, String> env) {
        return Optional.ofNullable(env.get("UR_CKAN_URL")).filter(s -> !s.isBlank()).map(URI::create)
                .orElse(CkanCatalog.DEFAULT_URL);
    }

    private static Path workDir(Map<String, String> env) {
        return Path.of(env.getOrDefault("LV_WORK_DIR", System.getProperty("java.io.tmpdir") + "/lv-register"));
    }
}
