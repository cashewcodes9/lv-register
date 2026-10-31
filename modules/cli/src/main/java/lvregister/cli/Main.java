package lvregister.cli;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Entry point. One subcommand per pipeline step plus {@code run} for all Java steps.
 * Wiring is manual: no framework, no Spring.
 */
@Command(
        name = "lv-register",
        mixinStandardHelpOptions = true,
        version = "lv-register 0.1.0",
        description = "Latvian Register of Enterprises pipeline.",
        subcommands = {
            Main.Download.class, Main.Lake.class, Main.Diff.class, Main.Quality.class,
            Main.Index.class, Main.Prune.class, Main.Run.class
        })
public final class Main {

    public static void main(String[] args) {
        System.exit(newCommandLine(Wiring.production(System.getenv()), RunContext.fromEnvironment(System.getenv()))
                .execute(args));
    }

    /** Builds the command line with the given wiring; used by {@link #main} and by tests. */
    public static CommandLine newCommandLine(Steps steps, RunContext context) {
        StepRunner runner = new StepRunner(steps, context);
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> cls) throws Exception {
                if (StepCommand.class.isAssignableFrom(cls)) {
                    K command = CommandLine.defaultFactory().create(cls);
                    ((StepCommand) command).runner = runner;
                    return command;
                }
                return CommandLine.defaultFactory().create(cls);
            }
        };
        return new CommandLine(new Main(), factory);
    }

    /** Common {@code --date} option and execution for every step command. */
    abstract static class StepCommand implements Callable<Integer> {

        StepRunner runner;

        @Option(names = "--date", required = true, paramLabel = "YYYY-MM-DD", description = "Capture date.")
        LocalDate date;

        abstract List<StepName> steps();

        @Override
        public Integer call() {
            return runner.run(steps(), date);
        }
    }

    @Command(name = "download", description = "Download the UR files into s3://lv-raw (skip unchanged).")
    static final class Download extends StepCommand {
        @Override List<StepName> steps() { return List.of(StepName.DOWNLOAD); }
    }

    @Command(name = "lake", description = "Write the raw files of a capture as Parquet into s3://lv-lake.")
    static final class Lake extends StepCommand {
        @Override List<StepName> steps() { return List.of(StepName.LAKE); }
    }

    @Command(name = "diff", description = "Compare with the previous capture in the lake and write events.")
    static final class Diff extends StepCommand {
        @Override List<StepName> steps() { return List.of(StepName.DIFF); }
    }

    @Command(name = "quality", description = "Write the run record and quality report.")
    static final class Quality extends StepCommand {
        @Override List<StepName> steps() { return List.of(StepName.QUALITY); }
    }

    @Command(name = "index", description = "Upsert companies, events and runs into Elasticsearch.")
    static final class Index extends StepCommand {
        @Override List<StepName> steps() { return List.of(StepName.INDEX); }
    }

    @Command(name = "prune", description = "Apply retention to raw files and lake snapshots.")
    static final class Prune extends StepCommand {
        @Override List<StepName> steps() { return List.of(StepName.PRUNE); }
    }

    @Command(name = "run", description = "Run all Java steps for a capture, without Dagster.")
    static final class Run extends StepCommand {
        @Override List<StepName> steps() { return Arrays.asList(StepName.values()); }
    }
}
