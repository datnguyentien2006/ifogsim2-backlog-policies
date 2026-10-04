package org.fog.hk261backlog.disruption;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


public final class DisconnectEventLog {
    public static final String EVENT_HEADER = "edge_key,cycle,event,scheduled_at_sec,applied_at_sec,planned_downtime_sec";
    public static final String OUTAGE_HEADER = "edge_key,cycle,down_at_sec,up_at_sec,planned_downtime_sec,observed_downtime_sec,recovered";

    public record Row(int edgeKey, long cycle, ConnectionState event, double scheduledAt, double appliedAt, double plannedDowntime) {

        public String toCsv() {
            return edgeKey + "," + cycle + ","
                    + event + "," + scheduledAt
                    + "," + appliedAt + "," + plannedDowntime;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    public void recordApplied(Transition transition, double appliedAt) {
        Objects.requireNonNull(transition, "transition");
        if (!Double.isFinite(appliedAt) || appliedAt < transition.at())
            throw new IllegalArgumentException("appliedAt must be finite and >= scheduledAt");
        Outage outage = transition.outage();

        rows.add(new Row(
            outage.edgeKey(),
            outage.cycle(),
            transition.newState(),
            transition.at(),
            appliedAt,
            outage.plannedDuration()
        ));
    }

    public List<Row> rows() {
        return List.copyOf(rows);
    }

    public List<String> toCsvLines() {
        List<String> lines = new ArrayList<>();
        lines.add(EVENT_HEADER);
        rows.forEach(row -> lines.add(row.toCsv()));
        return lines;
    }

    public void writeCsv(Path file) throws IOException {
        writeLines(file, toCsvLines());
    }

    public static List<String> outageSummaryLines(DisruptionPlan plan) {
        List<String> lines = new ArrayList<>();

        lines.add(OUTAGE_HEADER);
        double horizon = plan.horizon();

        for (Outage outage : plan.allOutages()) {
            String line =
                outage.edgeKey() + "," +
                outage.cycle() + "," +
                outage.downAt() + "," +
                outage.upAt() + "," +
                outage.plannedDuration() + "," +
                outage.observedDuration(horizon) + "," +
                outage.recoversBefore(horizon);

            lines.add(line);
        }

        return lines;
    }

    public static void writeOutageSummaryCsv(DisruptionPlan plan, Path file) throws IOException {
        writeLines(file, outageSummaryLines(plan));
    }

    private static void writeLines(Path file, List<String> lines) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null)
            Files.createDirectories(parent);
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
