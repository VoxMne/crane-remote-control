package com.vukotic.crane.core.telemetry;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Records every published {@link CraneState} as one CSV row. Register it as a
 * control-loop state listener to start recording, remove it and {@link #close()}
 * to stop. Columns: timestamp, position and velocity per profile axis, safety
 * flags, active alarms (';'-joined).
 *
 * <p>Numbers are written with {@link Locale#ROOT} so the file parses the same
 * everywhere regardless of the OS decimal separator. Rows are flushed once per
 * {@value #FLUSH_EVERY_ROWS} rows (~1 s at 50 Hz). Thread-safe: the control loop
 * writes rows while the UI may close concurrently; writes after close are ignored.
 */
public final class TelemetryCsvLogger implements Consumer<CraneState>, AutoCloseable {

    private static final int FLUSH_EVERY_ROWS = 50;

    private final List<String> axisIds;
    private final BufferedWriter writer;
    private int rowsSinceFlush;
    private boolean closed;

    /** Creates the file (parent directories included) and writes the header row. */
    public TelemetryCsvLogger(Path file, CraneProfile profile) throws IOException {
        this(file, profile, null);
    }

    /**
     * As above, naming the person at the controls.
     *
     * @param operator trainee or operator name, or null to omit it
     */
    public TelemetryCsvLogger(Path file, CraneProfile profile, String operator)
            throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(profile, "profile");
        this.axisIds = profile.axisIds();
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);

        // Metadata first, on '#' lines. A recording is the assessment artifact for
        // a training rig, so it has to say on its own which machine it came from,
        // in what units, and who was driving — a file that only carries numbers is
        // unmarkable a week later, and cannot be checked against the profile it was
        // recorded on. Readers skip lines starting with '#'.
        writeLine("# crane-remote-control telemetry v2");
        writeLine("# profile=" + profile.id());
        writeLine("# craneName=" + profile.name());
        if (operator != null && !operator.isBlank()) {
            writeLine("# operator=" + operator.replace('\n', ' ').trim());
        }
        writeLine("# recorded=" + java.time.ZonedDateTime.now()
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        for (var axis : profile.axes()) {
            writeLine("# axis=%s unit=%s min=%s max=%s maxVelocity=%s"
                    .formatted(axis.id(), axis.unit(), axis.minPosition(),
                            axis.maxPosition(), axis.maxVelocity()));
        }

        StringBuilder header = new StringBuilder("timestampMillis");
        for (String id : axisIds) {
            header.append(",pos_").append(id).append(",vel_").append(id);
        }
        header.append(",estopLatched,deadmanHeld,watchdogTripped,alarms");
        writeLine(header.toString());
    }

    @Override
    public synchronized void accept(CraneState state) {
        if (closed) {
            return;
        }
        StringBuilder row = new StringBuilder();
        row.append(state.timestampMillis());
        for (String id : axisIds) {
            row.append(',').append(num(state.position(id)))
               .append(',').append(num(state.velocity(id)));
        }
        row.append(',').append(state.estopLatched())
           .append(',').append(state.deadmanHeld())
           .append(',').append(state.watchdogTripped())
           .append(',').append('"').append(String.join(";", state.activeAlarms())).append('"');
        writeLine(row.toString());
        if (++rowsSinceFlush >= FLUSH_EVERY_ROWS) {
            rowsSinceFlush = 0;
            try {
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("telemetry flush failed", e);
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            writer.close();
        }
    }

    private void writeLine(String line) {
        try {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException("telemetry write failed", e);
        }
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
