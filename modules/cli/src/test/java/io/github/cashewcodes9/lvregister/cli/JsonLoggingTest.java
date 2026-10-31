package io.github.cashewcodes9.lvregister.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The log lines of a step are JSON and carry the run context from the MDC (logback.xml). */
class JsonLoggingTest {

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach
    void attach() {
        captured.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        captured.start();
        root.addAppender(captured);
    }

    @AfterEach
    void detach() {
        root.detachAppender(captured);
    }

    @Test
    void stepLogLinesAreJsonWithRunContext() {
        Map<StepName, Step> steps = new EnumMap<>(StepName.class);
        for (StepName name : StepName.values()) {
            steps.put(name, date -> { });
        }
        RunContext context = new RunContext("run-42", Optional.of("dagster-7"));

        Main.newCommandLine(new Steps(steps), context).execute("download", "--date", "2026-11-02");

        List<String> lines = captured.list.stream().map(this::encode).toList();
        assertThat(lines).hasSize(2).allSatisfy(line -> assertThat(line)
                .startsWith("{")
                .contains("\"run_id\":\"run-42\"")
                .contains("\"dagster_run_id\":\"dagster-7\"")
                .contains("\"step\":\"download\"")
                .contains("\"partition_date\":\"2026-11-02\"")
                .contains("\"ts\":"));
    }

    /** Encodes an event with the encoder configured for STDOUT in logback.xml. */
    private String encode(ILoggingEvent event) {
        @SuppressWarnings("unchecked")
        OutputStreamAppender<ILoggingEvent> stdout = (OutputStreamAppender<ILoggingEvent>) root.getAppender("STDOUT");
        Encoder<ILoggingEvent> encoder = stdout.getEncoder();
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }
}
