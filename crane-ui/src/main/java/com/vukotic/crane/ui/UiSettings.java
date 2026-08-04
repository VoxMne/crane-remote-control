package com.vukotic.crane.ui;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Remembers how the operator left the application: window size, which crane and
 * driver were selected, the view and camera, which assists were on and what the
 * weather was.
 *
 * <p>Backed by {@link Preferences} rather than a file, so there is nothing to
 * install, nothing to lose when the app folder is replaced by an upgrade, and no
 * path handling to get wrong.
 *
 * <p>Deliberately <b>not</b> persisted: anything safety-relevant. Driver mode,
 * the E-STOP latch and the deadman always start from their safe state — a crane
 * that came back up in the mode you left it in would be a nasty surprise.
 */
public final class UiSettings {

    private static final String KEY_WIDTH = "window.width";
    private static final String KEY_HEIGHT = "window.height";
    private static final String KEY_MAXIMISED = "window.maximised";
    private static final String KEY_PROFILE = "profile.id";
    private static final String KEY_DRIVER = "driver.choice";
    private static final String KEY_VIEW_3D = "view.use3d";
    private static final String KEY_CAMERA = "view.camera";
    private static final String KEY_CARGO = "view.cargo";
    private static final String KEY_SMOOTHING = "assist.smoothing";
    private static final String KEY_ANTI_SWAY = "assist.antiSway";
    private static final String KEY_WIND_SPEED = "weather.windSpeed";
    private static final String KEY_WIND_FROM = "weather.windFrom";
    private static final String KEY_MUTED = "sound.muted";

    private final Preferences prefs = Preferences.userNodeForPackage(UiSettings.class);

    public double windowWidth() {
        return prefs.getDouble(KEY_WIDTH, 1280);
    }

    public double windowHeight() {
        return prefs.getDouble(KEY_HEIGHT, 820);
    }

    public boolean maximised() {
        return prefs.getBoolean(KEY_MAXIMISED, false);
    }

    public String profileId(String fallback) {
        return prefs.get(KEY_PROFILE, fallback);
    }

    public String driverChoice(String fallback) {
        return prefs.get(KEY_DRIVER, fallback);
    }

    public boolean use3d() {
        return prefs.getBoolean(KEY_VIEW_3D, false);
    }

    public String cameraMode(String fallback) {
        return prefs.get(KEY_CAMERA, fallback);
    }

    public String cargoType(String fallback) {
        return prefs.get(KEY_CARGO, fallback);
    }

    public boolean smoothing() {
        return prefs.getBoolean(KEY_SMOOTHING, false);
    }

    public boolean antiSway() {
        return prefs.getBoolean(KEY_ANTI_SWAY, false);
    }

    public double windSpeed() {
        return prefs.getDouble(KEY_WIND_SPEED, 0);
    }

    public double windFromDeg() {
        return prefs.getDouble(KEY_WIND_FROM, 0);
    }

    public boolean muted() {
        return prefs.getBoolean(KEY_MUTED, false);
    }

    /** Writes the whole snapshot; called once as the application shuts down. */
    public void save(Snapshot snapshot) {
        prefs.putDouble(KEY_WIDTH, snapshot.width());
        prefs.putDouble(KEY_HEIGHT, snapshot.height());
        prefs.putBoolean(KEY_MAXIMISED, snapshot.maximised());
        prefs.put(KEY_PROFILE, snapshot.profileId());
        prefs.put(KEY_DRIVER, snapshot.driverChoice());
        prefs.putBoolean(KEY_VIEW_3D, snapshot.use3d());
        prefs.put(KEY_CAMERA, snapshot.cameraMode());
        prefs.put(KEY_CARGO, snapshot.cargoType());
        prefs.putBoolean(KEY_SMOOTHING, snapshot.smoothing());
        prefs.putBoolean(KEY_ANTI_SWAY, snapshot.antiSway());
        prefs.putDouble(KEY_WIND_SPEED, snapshot.windSpeed());
        prefs.putDouble(KEY_WIND_FROM, snapshot.windFromDeg());
        prefs.putBoolean(KEY_MUTED, snapshot.muted());
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            System.err.println("[settings] could not be saved: " + e.getMessage());
        }
    }

    /** Everything worth remembering, gathered in one place. */
    public record Snapshot(
            double width, double height, boolean maximised,
            String profileId, String driverChoice,
            boolean use3d, String cameraMode, String cargoType,
            boolean smoothing, boolean antiSway,
            double windSpeed, double windFromDeg, boolean muted) {
    }
}
