package com.vukotic.crane.core.telemetry;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.core.model.CraneState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recording is the assessment artifact for a training rig, so it has to be
 * readable on its own and reducible to the handful of numbers a marker uses.
 */
class SessionSummaryTest {

    @TempDir
    Path tempDir;

    private final CraneProfile profile = CraneProfiles.demoKnuckleBoom();

    private CraneState frame(long timestamp, double slewVelocity,
                             boolean estop, List<String> alarms) {
        return new CraneState(timestamp,
                Map.of("slew", 0.0, "boom", 0.0, "jib", 0.0, "extension", 0.0, "winch", 0.0),
                Map.of("slew", slewVelocity, "boom", 0.0, "jib", 0.0,
                        "extension", 0.0, "winch", 0.0),
                estop, !estop, false, alarms);
    }

    @Test
    void anEmergencyStopHeldDownIsOneTripNotFiveHundred() {
        // Counting frames would make a trainee who latched once and thought about
        // it look far worse than one who latched repeatedly and carried on.
        List<CraneState> frames = new java.util.ArrayList<>();
        long t = 1_000;
        for (int i = 0; i < 5; i++, t += 20) {
            frames.add(frame(t, 1.0, false, List.of()));
        }
        for (int i = 0; i < 500; i++, t += 20) {
            frames.add(frame(t, 0.0, true, List.of("E-STOP latched")));
        }
        SessionSummary summary =
                SessionSummary.of(new TelemetryCsvReader.Recording(frames));

        assertEquals(1, summary.estopTrips());
    }

    @Test
    void limitHitsAreCountedPerAxisAndPerEvent() {
        List<CraneState> frames = List.of(
                frame(1_000, 1.0, false, List.of()),
                frame(1_020, 1.0, false, List.of("slew at limit")),
                frame(1_040, 1.0, false, List.of("slew at limit")),   // same event
                frame(1_060, 1.0, false, List.of()),
                frame(1_080, 1.0, false, List.of("slew at limit")),   // a second one
                frame(1_100, 1.0, false, List.of("boom at limit")));

        SessionSummary summary =
                SessionSummary.of(new TelemetryCsvReader.Recording(frames));

        assertEquals(3, summary.limitHits());
        assertEquals(2, summary.limitHitsByAxis().get("slew"));
        assertEquals(1, summary.limitHitsByAxis().get("boom"));
    }

    @Test
    void dutyCycleReflectsTimeActuallyMoving() {
        List<CraneState> frames = List.of(
                frame(1_000, 0.0, false, List.of()),
                frame(1_100, 1.0, false, List.of()),   // moving for this step
                frame(1_200, 0.0, false, List.of()));

        SessionSummary summary =
                SessionSummary.of(new TelemetryCsvReader.Recording(frames));

        assertEquals(200, summary.durationMillis());
        assertEquals(100, summary.movingMillis());
        assertEquals(0.5, summary.dutyCycle(), 1e-9);
    }

    @Test
    void anEmptyRecordingSummarisesToNothingRatherThanThrowing() {
        SessionSummary summary =
                SessionSummary.of(new TelemetryCsvReader.Recording(List.of()));
        assertEquals(0, summary.frames());
        assertEquals(0.0, summary.dutyCycle());
    }

    @Test
    void aRecordingSaysWhichMachineAndWhoWasDriving() throws IOException {
        Path file = tempDir.resolve("run.csv");
        try (TelemetryCsvLogger logger =
                     new TelemetryCsvLogger(file, profile, "V. Vukotic")) {
            logger.accept(frame(1_000, 1.0, false, List.of()));
            logger.accept(frame(1_020, 1.0, false, List.of()));
        }

        TelemetryCsvReader reader = new TelemetryCsvReader();
        TelemetryCsvReader.Recording recording = reader.read(file);

        assertEquals(2, recording.frames().size(), "the numbers still parse");
        assertEquals(profile.id(), reader.metadata().get("profile"));
        assertEquals(profile.name(), reader.metadata().get("craneName"));
        assertEquals("V. Vukotic", reader.metadata().get("operator"));
        assertTrue(reader.metadata().containsKey("recorded"));
        assertTrue(reader.metadata().get("axis").contains("slew"),
                "axis units and travel travel with the file: " + reader.metadata().get("axis"));
    }

    @Test
    void aRecordingWithoutMetadataStillReads() throws IOException {
        // v1 files carried nothing but numbers; they must not stop opening.
        Path file = tempDir.resolve("old.csv");
        java.nio.file.Files.writeString(file, String.join("\n",
                "timestampMillis,pos_slew,vel_slew,estopLatched,deadmanHeld,"
                        + "watchdogTripped,alarms",
                "1000,0.0000,0.0000,false,true,false,\"\"",
                "1020,1.0000,0.5000,false,true,false,\"\""));

        TelemetryCsvReader reader = new TelemetryCsvReader();
        assertEquals(2, reader.read(file).frames().size());
        assertTrue(reader.metadata().isEmpty());
    }
}
