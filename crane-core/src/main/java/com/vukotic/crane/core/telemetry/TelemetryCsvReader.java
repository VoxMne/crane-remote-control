package com.vukotic.crane.core.telemetry;

import com.vukotic.crane.core.model.CraneState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads back what {@link TelemetryCsvLogger} wrote, so a recorded run can be
 * replayed without a crane, a simulator or a control loop — the whole point
 * being that you can show a real recording on a laptop.
 *
 * <p>The header names the axes, so a recording is self-describing and does not
 * need the profile it came from. Malformed rows are skipped rather than aborting
 * the load: a recording truncated by a crash is still worth watching.
 */
public final class TelemetryCsvReader {

    /** A recorded run: states in order, with their original timestamps. */
    public record Recording(List<CraneState> frames) {

        public Recording {
            frames = List.copyOf(frames);
        }

        public boolean isEmpty() {
            return frames.isEmpty();
        }

        /** Wall time from the first to the last frame, in milliseconds. */
        public long durationMillis() {
            return frames.isEmpty() ? 0
                    : frames.get(frames.size() - 1).timestampMillis() - frames.get(0).timestampMillis();
        }

        /**
         * The frame that was current {@code elapsedMillis} into the recording.
         * Returns the last frame once the recording has run out.
         *
         * <p>Binary search, not a scan. This is called once per rendered frame, so
         * scanning from the beginning made playback cost grow with how far into the
         * recording you were — a 50 Hz recording of a few minutes is hundreds of
         * thousands of comparisons per frame by the end.
         */
        public CraneState frameAt(long elapsedMillis) {
            if (frames.isEmpty()) {
                throw new IllegalStateException("empty recording");
            }
            long target = frames.get(0).timestampMillis() + elapsedMillis;
            int low = 0;
            int high = frames.size() - 1;
            int chosen = 0;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (frames.get(mid).timestampMillis() <= target) {
                    chosen = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return frames.get(chosen);
        }
    }

    public Recording read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return new Recording(List.of());
        }

        List<String> header = Arrays.asList(lines.get(0).split(","));
        List<String> axisIds = new ArrayList<>();
        for (String column : header) {
            if (column.startsWith("pos_")) {
                axisIds.add(column.substring("pos_".length()));
            }
        }

        List<CraneState> frames = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            parseRow(line, axisIds).ifPresent(frames::add);
        }
        return new Recording(frames);
    }

    private java.util.Optional<CraneState> parseRow(String line, List<String> axisIds) {
        // The alarms column is quoted and may contain ';' but never a comma.
        String[] fields = line.split(",");
        int expected = 1 + axisIds.size() * 2 + 4;
        if (fields.length < expected) {
            return java.util.Optional.empty();
        }
        try {
            long timestamp = Long.parseLong(fields[0].trim());
            Map<String, Double> positions = new LinkedHashMap<>();
            Map<String, Double> velocities = new LinkedHashMap<>();
            int index = 1;
            for (String axisId : axisIds) {
                positions.put(axisId, Double.parseDouble(fields[index++]));
                velocities.put(axisId, Double.parseDouble(fields[index++]));
            }
            boolean estop = Boolean.parseBoolean(fields[index++]);
            boolean deadman = Boolean.parseBoolean(fields[index++]);
            boolean watchdog = Boolean.parseBoolean(fields[index++]);

            String alarmField = fields[index].replace("\"", "").trim();
            List<String> alarms = alarmField.isEmpty() ? List.of()
                    : Arrays.asList(alarmField.split(";"));

            return java.util.Optional.of(new CraneState(timestamp, positions, velocities,
                    estop, deadman, watchdog, alarms));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();   // skip the bad row, keep the run
        }
    }
}
