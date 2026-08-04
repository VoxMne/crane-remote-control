package com.vukotic.crane.core.telemetry;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.core.model.CraneState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the logger writes, the reader must be able to replay. */
class TelemetryRoundTripTest {

    @TempDir
    Path tempDir;

    private final CraneProfile profile = CraneProfiles.demoKnuckleBoom();

    private CraneState frame(long timestamp, double slew, boolean estop) {
        return new CraneState(timestamp,
                Map.of("slew", slew, "boom", 10.0, "jib", 0.0, "extension", 0.0, "winch", 2.0),
                Map.of("slew", 1.5, "boom", 0.0, "jib", 0.0, "extension", 0.0, "winch", 0.0),
                estop, true, false, estop ? List.of("E-STOP latched") : List.of());
    }

    @Test
    void aRecordingSurvivesTheRoundTrip() throws IOException {
        Path file = tempDir.resolve("run.csv");
        try (TelemetryCsvLogger logger = new TelemetryCsvLogger(file, profile)) {
            logger.accept(frame(1_000, 0.0, false));
            logger.accept(frame(1_020, 12.5, false));
            logger.accept(frame(1_040, 25.0, true));
        }

        TelemetryCsvReader.Recording recording = new TelemetryCsvReader().read(file);
        assertEquals(3, recording.frames().size());
        assertEquals(40, recording.durationMillis());

        CraneState middle = recording.frames().get(1);
        assertEquals(12.5, middle.position("slew"));
        assertEquals(1.5, middle.velocity("slew"));
        assertTrue(recording.frames().get(2).estopLatched());
        assertEquals(List.of("E-STOP latched"), recording.frames().get(2).activeAlarms());
    }

    @Test
    void frameLookupTracksElapsedTime() throws IOException {
        Path file = tempDir.resolve("run.csv");
        try (TelemetryCsvLogger logger = new TelemetryCsvLogger(file, profile)) {
            logger.accept(frame(5_000, 0.0, false));
            logger.accept(frame(5_100, 10.0, false));
            logger.accept(frame(5_200, 20.0, false));
        }
        TelemetryCsvReader.Recording recording = new TelemetryCsvReader().read(file);

        assertEquals(0.0, recording.frameAt(0).position("slew"));
        assertEquals(10.0, recording.frameAt(150).position("slew"));
        assertEquals(20.0, recording.frameAt(10_000).position("slew"),
                "past the end it should hold the final frame");
    }

    @Test
    void aTruncatedRecordingStillLoads() throws IOException {
        Path file = tempDir.resolve("truncated.csv");
        try (TelemetryCsvLogger logger = new TelemetryCsvLogger(file, profile)) {
            logger.accept(frame(1_000, 0.0, false));
            logger.accept(frame(1_020, 5.0, false));
        }
        // Simulate a crash mid-write: append a half-written row.
        Files.writeString(file, "1040,3.5,0.2", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        TelemetryCsvReader.Recording recording = new TelemetryCsvReader().read(file);
        assertEquals(2, recording.frames().size(), "the good frames should survive");
    }
}
