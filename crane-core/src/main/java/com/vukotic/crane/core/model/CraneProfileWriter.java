package com.vukotic.crane.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a {@link CraneProfile} back out as JSON, so a machine defined in the
 * app is stored in exactly the format {@link CraneProfileLoader} reads. Round
 * tripping matters: a profile someone builds in the editor has to be a file they
 * can email, diff, or hand to a manufacturer.
 */
public final class CraneProfileWriter {

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /** File name a profile is stored under, sanitised so an id cannot escape the folder. */
    public static String fileNameFor(CraneProfile profile) {
        String safe = profile.id().replaceAll("[^A-Za-z0-9._-]", "_");
        return (safe.isBlank() ? "profile" : safe) + ".json";
    }

    /**
     * Writes the profile into {@code directory}, creating it if needed.
     *
     * @return the file written
     */
    public Path write(CraneProfile profile, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(fileNameFor(profile));
        mapper.writeValue(file.toFile(), profile);
        return file;
    }
}
