package com.vukotic.crane.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A crane built in the editor has to come back as the same crane. If the writer
 * and the loader ever disagree, a profile someone spent an afternoon on stops
 * loading — and profiles are the whole "universal" claim of this product.
 */
class CraneProfileWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void aWrittenProfileLoadsBackIdentical() throws IOException, ProfileLoadException {
        CraneProfile original = new CraneProfile("test-crane", "Test Crane", List.of(
                new AxisSpec("slew", "Slew (rotation)", "deg", -180, 180, 12, 2),
                new AxisSpec("boom", "Main boom", "deg", -5, 75, 8, 2),
                new AxisSpec("winch", "Winch (rope out)", "m", 0, 20, 1.0, 2)));

        Path file = new CraneProfileWriter().write(original, tempDir);
        assertEquals("test-crane.json", file.getFileName().toString());

        assertEquals(original, new CraneProfileLoader().load(file));
    }

    @Test
    void theDirectoryIsCreatedIfMissing() throws IOException {
        Path nested = tempDir.resolve("does").resolve("not").resolve("exist");
        Path file = new CraneProfileWriter().write(CraneProfiles.demoKnuckleBoom(), nested);
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void anIdCannotEscapeTheProfilesFolder() throws IOException {
        CraneProfile sneaky = new CraneProfile("../../etc/passwd", "Sneaky", List.of(
                new AxisSpec("slew", "Slew", "deg", -180, 180, 12, 2)));

        assertEquals(".._.._etc_passwd.json", CraneProfileWriter.fileNameFor(sneaky),
                "separators must not survive; dots alone cannot traverse");

        Path file = new CraneProfileWriter().write(sneaky, tempDir);
        assertEquals(tempDir, file.getParent(), "the file must land inside the given folder");
    }
}
