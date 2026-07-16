package com.vukotic.crane.ui;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfileLoader;
import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.core.model.ProfileLoadException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * All crane profiles the app can drive: the built-in demo, the bundled JSON
 * profiles, and any {@code *.json} the user drops into a {@code profiles/}
 * directory next to the app — a new crane without touching code. A profile that
 * fails to load is skipped with a stderr note; it never takes the app down.
 */
public final class ProfileCatalog {

    private static final List<String> BUNDLED =
            List.of("/profiles/compact-3.json", "/profiles/heavy-5.json");

    private ProfileCatalog() {
    }

    public static List<CraneProfile> available() {
        CraneProfileLoader loader = new CraneProfileLoader();
        List<CraneProfile> profiles = new ArrayList<>();
        profiles.add(CraneProfiles.demoKnuckleBoom());

        for (String resource : BUNDLED) {
            try (InputStream in = ProfileCatalog.class.getResourceAsStream(resource)) {
                if (in == null) {
                    System.err.println("[profiles] bundled resource missing: " + resource);
                } else {
                    profiles.add(loader.load(in));
                }
            } catch (IOException | ProfileLoadException e) {
                System.err.println("[profiles] skipping bundled " + resource + ": " + e.getMessage());
            }
        }

        Path userDir = Path.of("profiles");
        if (Files.isDirectory(userDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(userDir, "*.json")) {
                for (Path file : stream) {
                    try {
                        profiles.add(loader.load(file));
                    } catch (ProfileLoadException e) {
                        System.err.println("[profiles] skipping " + file + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                System.err.println("[profiles] cannot scan " + userDir + ": " + e.getMessage());
            }
        }
        return List.copyOf(profiles);
    }
}
