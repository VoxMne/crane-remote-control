package com.vukotic.crane.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Where the app keeps the files a user creates: crane profiles and telemetry
 * recordings.
 *
 * <p>These used to be plain relative paths, which resolve against the process
 * working directory. That is the repo when you run from Gradle, but for an
 * installed copy started from the Start menu it is wherever Windows felt like —
 * often a folder the user cannot write to. Saving a profile would then fail, or
 * succeed somewhere nobody can find.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code ./<name>} if it already exists — a repo checkout or a portable
 *       folder someone deliberately keeps their profiles in stays working;</li>
 *   <li>otherwise a per-user data directory: {@code %LOCALAPPDATA%} on Windows,
 *       {@code ~/.crane-remote-control} elsewhere.</li>
 * </ol>
 */
public final class AppPaths {

    private static final String APP_FOLDER = "CraneRemoteControl";

    private AppPaths() {
    }

    public static Path profiles() {
        return resolve("profiles");
    }

    public static Path telemetry() {
        return resolve("telemetry");
    }

    private static Path resolve(String name) {
        Path local = Path.of(name);
        if (Files.isDirectory(local)) {
            return local.toAbsolutePath();
        }
        return userDataDirectory().resolve(name);
    }

    private static Path userDataDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData, APP_FOLDER);
            }
        }
        return Path.of(System.getProperty("user.home", "."), "." + APP_FOLDER.toLowerCase(Locale.ROOT));
    }
}
