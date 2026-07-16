package com.vukotic.crane.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraneProfileLoaderTest {

    private final CraneProfileLoader loader = new CraneProfileLoader();

    private static final String VALID = """
            {
              "id": "test-2",
              "name": "Test Crane",
              "axes": [
                { "id": "slew", "label": "Slew", "unit": "deg",
                  "minPosition": -90, "maxPosition": 90,
                  "maxVelocity": 12, "commandRampRate": 2.0 },
                { "id": "boom", "label": "Boom", "unit": "deg",
                  "minPosition": 0, "maxPosition": 70,
                  "maxVelocity": 6, "commandRampRate": 1.5 }
              ]
            }
            """;

    @Test
    void loadsValidProfile() {
        CraneProfile profile = loader.load(VALID);
        assertEquals("test-2", profile.id());
        assertEquals(2, profile.axes().size());
        assertEquals(12.0, profile.axisById("slew").orElseThrow().maxVelocity());
    }

    @Test
    void rejectsMalformedJson() {
        ProfileLoadException e = assertThrows(ProfileLoadException.class,
                () -> loader.load("{ not json"));
        assertTrue(e.getMessage().contains("invalid crane profile"));
    }

    @Test
    void rejectsUnknownField() {
        String json = VALID.replace("\"name\"", "\"nmae\"");
        assertThrows(ProfileLoadException.class, () -> loader.load(json));
    }

    @Test
    void surfacesRecordValidation_invertedLimits() {
        String json = VALID.replace("\"maxPosition\": 90", "\"maxPosition\": -95");
        ProfileLoadException e = assertThrows(ProfileLoadException.class, () -> loader.load(json));
        assertTrue(e.getMessage().contains("greater than"),
                "expected AxisSpec validation message, got: " + e.getMessage());
    }

    @Test
    void surfacesRecordValidation_duplicateAxes() {
        String json = VALID.replace("\"id\": \"boom\"", "\"id\": \"slew\"");
        ProfileLoadException e = assertThrows(ProfileLoadException.class, () -> loader.load(json));
        assertTrue(e.getMessage().contains("duplicate"),
                "expected CraneProfile validation message, got: " + e.getMessage());
    }

    @Test
    void rejectsEmptyAxes() {
        String json = """
                { "id": "empty", "name": "Empty", "axes": [] }
                """;
        assertThrows(ProfileLoadException.class, () -> loader.load(json));
    }
}
