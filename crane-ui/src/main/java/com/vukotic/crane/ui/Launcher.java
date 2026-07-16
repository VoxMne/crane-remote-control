package com.vukotic.crane.ui;

/**
 * Plain-classpath entry point for packaged distributions. The JVM launcher
 * refuses to start a {@code javafx.application.Application} subclass directly
 * when JavaFX sits on the classpath (as it does in the jpackage image), so the
 * main class must be this non-Application indirection.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        CraneRemoteApp.main(args);
    }
}
