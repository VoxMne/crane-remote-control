package com.vukotic.crane.core.telemetry;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.core.model.CraneState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryCsvLoggerTest {

    @TempDir
    Path tempDir;

    @Test
    void writesHeaderAndRows() throws IOException {
        CraneProfile profile = CraneProfiles.demoKnuckleBoom();
        Path file = tempDir.resolve("logs").resolve("run.csv");

        try (TelemetryCsvLogger logger = new TelemetryCsvLogger(file, profile)) {
            logger.accept(CraneState.initial(profile));
            logger.accept(new CraneState(1234L,
                    Map.of("slew", 12.5, "boom", 3.25, "jib", 0.0, "extension", 1.5, "winch", 4.0),
                    Map.of("slew", 1.5, "boom", 0.0, "jib", 0.0, "extension", 0.1, "winch", -0.5),
                    true, false, true, List.of("E-STOP latched", "slew at limit")));
        }

        List<String> lines = dataLines(file);
        assertEquals(3, lines.size());
        assertEquals("timestampMillis,pos_slew,vel_slew,pos_boom,vel_boom,pos_jib,vel_jib,"
                + "pos_extension,vel_extension,pos_winch,vel_winch,"
                + "estopLatched,deadmanHeld,watchdogTripped,alarms", lines.get(0));
        assertTrue(lines.get(2).startsWith("1234,12.5000,1.5000,"));
        assertTrue(lines.get(2).endsWith("true,false,true,\"E-STOP latched;slew at limit\""));
    }

    @Test
    void ignoresRowsAfterClose() throws IOException {
        CraneProfile profile = CraneProfiles.demoKnuckleBoom();
        Path file = tempDir.resolve("run.csv");
        TelemetryCsvLogger logger = new TelemetryCsvLogger(file, profile);
        logger.accept(CraneState.initial(profile));
        logger.close();
        logger.accept(CraneState.initial(profile)); // must not throw
        assertEquals(2, dataLines(file).size());
    }

    @Test
    void usesDotDecimalSeparatorRegardlessOfLocale() throws IOException {
        CraneProfile profile = CraneProfiles.demoKnuckleBoom();
        Path file = tempDir.resolve("locale.csv");
        try (TelemetryCsvLogger logger = new TelemetryCsvLogger(file, profile)) {
            logger.accept(CraneState.initial(profile));
        }
        String dataRow = dataLines(file).get(1);
        assertTrue(dataRow.contains("0.0000"), "expected dot-decimal numbers: " + dataRow);
    }

    /**
     * The CSV proper: metadata lines start with '#' and are the reader's business,
     * not these tests'. Added when recordings became self-describing so they could
     * be marked and checked against their profile a week later.
     */
    private static List<String> dataLines(Path file) throws IOException {
        return Files.readAllLines(file).stream()
                .filter(line -> !line.startsWith("#"))
                .toList();
    }
}