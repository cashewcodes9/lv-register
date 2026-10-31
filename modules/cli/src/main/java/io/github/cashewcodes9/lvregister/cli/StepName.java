package io.github.cashewcodes9.lvregister.cli;

/** Pipeline steps in run order (PLAN.md section 6). */
public enum StepName {
    DOWNLOAD, LAKE, DIFF, QUALITY, INDEX, PRUNE;

    public String cliName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
