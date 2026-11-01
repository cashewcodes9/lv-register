package lvregister.pipes;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * One Dagster Pipes session of a CLI process: sends {@code opened} on start, reports, and
 * {@code closed} on {@link #close()}. Messages are single JSON lines with
 * {@code "__dagster_pipes_version": "0.1"}; Dagster ignores every other line (our logs).
 */
public final class PipesSession implements AutoCloseable {

    public static final String CONTEXT_ENV = "DAGSTER_PIPES_CONTEXT";
    public static final String MESSAGES_ENV = "DAGSTER_PIPES_MESSAGES";
    static final String VERSION_FIELD = "__dagster_pipes_version";
    static final String VERSION = "0.1";

    private final PipesContext context;
    private final Consumer<String> channel;
    private boolean closed;

    PipesSession(PipesContext context, Consumer<String> channel) {
        this.context = context;
        this.channel = channel;
        write("opened", Map.of("extras", Map.of()));
    }

    /**
     * Opens a session if Dagster launched the process (both bootstrap variables are set),
     * else returns empty: the CLI then runs as a plain command.
     */
    public static Optional<PipesSession> open(Map<String, String> env, PrintStream stdout, PrintStream stderr) {
        String contextParam = env.get(CONTEXT_ENV);
        String messagesParam = env.get(MESSAGES_ENV);
        if (contextParam == null || contextParam.isBlank() || messagesParam == null || messagesParam.isBlank()) {
            return Optional.empty();
        }
        PipesContext context = PipesContext.fromJson(loadContext(PipesParams.decode(contextParam)));
        return Optional.of(new PipesSession(context, channel(PipesParams.decode(messagesParam), stdout, stderr)));
    }

    public PipesContext context() {
        return context;
    }

    /** Reports the materialization of the step's single asset. */
    public void reportMaterialization(Map<String, Object> metadata) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("asset_key", context.singleAssetKey());
        params.put("data_version", null);
        params.put("metadata", PipesMetadata.encode(metadata));
        write("report_asset_materialization", params);
    }

    /** Reports an asset check of the step's single asset. The check must be declared in Dagster. */
    public void reportCheck(String checkName, boolean passed, Severity severity, Map<String, Object> metadata) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("asset_key", context.singleAssetKey());
        params.put("check_name", checkName);
        params.put("passed", passed);
        params.put("metadata", PipesMetadata.encode(metadata));
        params.put("severity", severity.name());
        write("report_asset_check", params);
    }

    /** A line in the Dagster event log. Never pass person data. */
    public void log(String message, String level) {
        write("log", Map.of("message", message, "level", level));
    }

    @Override
    public void close() {
        if (!closed) {
            write("closed", Map.of());
            closed = true;
        }
    }

    private void write(String method, Map<String, Object> params) {
        if (closed) {
            throw new IllegalStateException("Pipes session is closed");
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put(VERSION_FIELD, VERSION);
        message.put("method", method);
        message.put("params", params);
        channel.accept(PipesParams.JSON.writeValueAsString(message));
    }

    /** {@code {"data": {...}}} inline (env injector) or {@code {"path": "..."}} (file injector). */
    private static JsonNode loadContext(JsonNode params) {
        if (params.has("data")) {
            return params.get("data");
        }
        if (params.has("path")) {
            try {
                return PipesParams.JSON.readTree(Files.readString(Path.of(params.get("path").asString())));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read the Pipes context file", e);
            }
        }
        throw new IllegalArgumentException("unsupported Pipes context parameters: " + params.propertyNames());
    }

    /** {@code {"stdio": "stderr"|"stdout"}} (Docker logs reader) or {@code {"path": "..."}} (file reader). */
    private static Consumer<String> channel(JsonNode params, PrintStream stdout, PrintStream stderr) {
        if (params.has("stdio")) {
            PrintStream stream = switch (params.get("stdio").asString()) {
                case "stdout" -> stdout;
                case "stderr" -> stderr;
                default -> throw new IllegalArgumentException("unsupported Pipes stdio: " + params.get("stdio"));
            };
            return line -> {
                synchronized (stream) {
                    stream.println(line);
                    stream.flush();
                }
            };
        }
        if (params.has("path")) {
            Path path = Path.of(params.get("path").asString());
            return line -> {
                try {
                    Files.writeString(path, line + "\n", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new IllegalStateException("cannot write the Pipes message file", e);
                }
            };
        }
        throw new IllegalArgumentException("unsupported Pipes message parameters: " + List.copyOf(params.propertyNames()));
    }
}
