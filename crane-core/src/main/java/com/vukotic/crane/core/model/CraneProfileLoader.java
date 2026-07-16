package com.vukotic.crane.core.model;

import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Loads {@link CraneProfile}s from JSON — the "universal" mechanism: a new crane
 * is a data file, never code. Expected shape:
 *
 * <pre>{@code
 * {
 *   "id": "compact-3",
 *   "name": "Compact Loader (3-axis)",
 *   "axes": [
 *     { "id": "slew", "label": "Slew (rotation)", "unit": "deg",
 *       "minPosition": -135, "maxPosition": 135,
 *       "maxVelocity": 10, "commandRampRate": 1.5 }
 *   ]
 * }
 * }</pre>
 *
 * <p>Unknown fields are rejected (typo protection) and all {@link AxisSpec}/
 * {@link CraneProfile} record validations apply; every failure surfaces as a
 * {@link ProfileLoadException} with a readable message.
 */
public final class CraneProfileLoader {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    /** Reads a profile from an open stream (caller closes). */
    public CraneProfile load(InputStream in) {
        Objects.requireNonNull(in, "in");
        try {
            return mapper.readValue(in, CraneProfile.class);
        } catch (DatabindException e) {
            throw new ProfileLoadException("invalid crane profile: " + rootMessage(e), e);
        } catch (IOException e) {
            throw new ProfileLoadException("cannot read crane profile: " + e.getMessage(), e);
        }
    }

    /** Reads a profile from a JSON file. */
    public CraneProfile load(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            return mapper.readValue(file.toFile(), CraneProfile.class);
        } catch (DatabindException e) {
            throw new ProfileLoadException(
                    "invalid crane profile %s: %s".formatted(file, rootMessage(e)), e);
        } catch (IOException e) {
            throw new ProfileLoadException(
                    "cannot read crane profile %s: %s".formatted(file, e.getMessage()), e);
        }
    }

    /** Reads a profile from a JSON string (used by tests and future remote config). */
    public CraneProfile load(String json) {
        Objects.requireNonNull(json, "json");
        try {
            return mapper.readValue(json, CraneProfile.class);
        } catch (IOException e) {
            throw new ProfileLoadException("invalid crane profile: " + rootMessage(e), e);
        }
    }

    /** Validation errors from record constructors arrive wrapped; dig out the useful text. */
    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : t.getMessage();
    }
}
