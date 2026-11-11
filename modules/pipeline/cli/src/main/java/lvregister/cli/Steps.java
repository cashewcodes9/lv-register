package lvregister.cli;

import java.util.EnumMap;
import java.util.Map;

/** The wired implementation of every step. Built once in {@link Main}. */
public record Steps(Map<StepName, Step> byName) {

    public Steps {
        byName = Map.copyOf(byName);
        for (StepName name : StepName.values()) {
            if (!byName.containsKey(name)) {
                throw new IllegalArgumentException("no implementation wired for step " + name);
            }
        }
    }

    public Step get(StepName name) {
        return byName.get(name);
    }

    /** Placeholder for steps that are not implemented yet (Milestones 4-7). */
    public static Steps notImplemented() {
        Map<StepName, Step> steps = new EnumMap<>(StepName.class);
        for (StepName name : StepName.values()) {
            steps.put(name, date -> {
                throw new UnsupportedOperationException("step '" + name.cliName() + "' is not implemented yet");
            });
        }
        return new Steps(steps);
    }
}
