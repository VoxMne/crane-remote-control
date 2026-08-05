package com.vukotic.crane.ui;

import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.MonotonicClock;
import com.vukotic.crane.core.assist.AutoSequencer;
import com.vukotic.crane.core.driver.CraneDriver;
import com.vukotic.crane.core.model.CraneState;
import com.vukotic.crane.core.telemetry.TelemetryCsvLogger;
import com.vukotic.crane.core.telemetry.TelemetryCsvReader;
import com.vukotic.crane.driver.serial.SerialCraneDriver;
import com.vukotic.crane.driver.serial.SerialPorts;
import com.vukotic.crane.sim.SimulatedCraneDriver;
import com.vukotic.crane.ui.backend.ControlLoopBackend;
import com.vukotic.crane.ui.input.KeyBindings;
import com.vukotic.crane.ui.input.OperatorInput;
import com.vukotic.crane.ui.render.CameraMode;
import com.vukotic.crane.ui.render.CargoType;
import com.vukotic.crane.ui.render.Crane3DView;
import com.vukotic.crane.ui.render.CraneSceneView;
import com.vukotic.crane.ui.render.Schematic2DView;
import com.vukotic.crane.ui.sound.SoundEngine;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operator HMI: dark industrial cockpit with a per-axis control panel,
 * schematic visualization canvas, and status panel.
 *
 * <p>Every frame, an {@link AnimationTimer} snapshots {@link OperatorInput}
 * into a {@link CraneCommand} for the backend and renders the latest
 * {@link CraneState}. The {@link ControlLoopBackend} (real 50 Hz control loop +
 * safety layer over the simulator driver) is the single integration seam.
 *
 * <p>Universality (M3): the profile selector rebuilds the whole cockpit for any
 * {@link CraneProfile} from {@link ProfileCatalog} — controls, readouts and
 * visualization all derive from profile data. Telemetry can be recorded to CSV
 * with the REC toggle.
 */
public final class CraneRemoteApp extends Application {

    // ---- palette ----
    private static final String BG = "#14181d";
    private static final String PANEL_BG = "#1c232b";
    private static final String AMBER = "#e8b716";
    private static final String ALARM_RED = "#d64541";
    private static final String OK_GREEN = "#3fae6a";
    private static final String TEXT = "#d7dee4";
    private static final String TEXT_DIM = "#9aa7b0";
    private static final Color LAMP_OFF = Color.web("#39434c");

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter ALARM_STAMP =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int ALARM_HISTORY_LIMIT = 100;

    private final KeyBindings keyBindings = KeyBindings.defaults();
    /** Mutable: the profile editor can add to it without a restart. */
    private final List<CraneProfile> catalog = new ArrayList<>(ProfileCatalog.available());

    /**
     * One crane session: the profile, the input sampler bound to its axes, and the
     * backend driving it. They are swapped together, as one reference.
     *
     * <p>They used to be three independent volatile fields, and the command thread
     * read them one at a time. A profile switch landing between two of those reads
     * paired held input from the old session with the new session's backend for one
     * tick — the operator's fingers driving a crane they had just switched away
     * from. Making the trio immutable and swapping a single reference removes the
     * window entirely rather than narrowing it.
     */
    private record Session(CraneProfile profile, OperatorInput input,
                           ControlLoopBackend backend) {
    }

    /**
     * Swapped on the FX thread, read by the command thread. Never partially
     * updated: the command thread reads this reference and nothing else, so it
     * always sees one coherent session.
     */
    private volatile Session session;

    // The same three, for FX-thread code that only ever touches one of them.
    // Kept in step with `session` by activateProfile().
    private volatile CraneProfile profile;
    private volatile OperatorInput operatorInput;
    private volatile ControlLoopBackend backend;

    /**
     * The operator's standing E-STOP request — the mushroom button's position.
     *
     * <p>Held here rather than read back off the {@code estopButton}, because the
     * control panel (and with it that button) is rebuilt on every profile or driver
     * switch. The rebuilt button came up unselected, so the first switch left the
     * backend latched under an un-pressed button and the second switch believed the
     * button and built an unlatched controller: an emergency stop cleared by
     * changing a dropdown twice.
     */
    private volatile boolean estopRequested;

    /** Fixed-rate operator-command thread — see startCommandThread(). */
    private static final long COMMAND_PERIOD_MILLIS = 20; // 50 Hz
    /**
     * How long the UI may go silent before the command thread stops trusting the
     * cached operator input. Generous enough for a slow frame, short enough that
     * a frozen UI cannot keep a crane moving.
     */
    private static final long UI_HEARTBEAT_TIMEOUT_MILLIS = 400;
    private Thread commandThread;
    private volatile boolean commandThreadRunning;
    private volatile CraneCommand lastCommand;
    /** Stamped by the JavaFX frame loop; proves the UI is still running. */
    private volatile long uiHeartbeatMillis = MonotonicClock.millis();

    private boolean isUiAlive() {
        return MonotonicClock.millis() - uiHeartbeatMillis < UI_HEARTBEAT_TIMEOUT_MILLIS;
    }

    /**
     * The operator's safety flags with every axis demand zeroed. Used for the
     * driver-mode lockout and for failing closed when the UI goes quiet.
     */
    private static CraneCommand neutralWithFlags(CraneProfile profile, CraneCommand command,
                                                 boolean deadmanHeld) {
        return new CraneCommand(command.timestampMillis(),
                CraneCommand.neutral(profile).axisDemands(),
                deadmanHeld, command.estopRequested(), command.resetRequested());
    }

    private final Map<String, Label> demandReadouts = new HashMap<>();
    private final Map<String, Label> positionReadouts = new HashMap<>();
    private final ObservableList<String> alarmItems = FXCollections.observableArrayList();
    private final ObservableList<String> alarmHistory = FXCollections.observableArrayList();
    private Set<String> previousAlarms = Set.of();

    private BorderPane root;
    private ToggleButton estopButton;
    private Label deadmanIndicator;
    private Circle estopLamp;
    private Circle deadmanLamp;
    private Circle watchdogLamp;
    private ToggleButton recordButton;
    private Label recordInfo;
    private AnimationTimer frameTimer;
    private TelemetryCsvLogger telemetryLogger;

    // Center visualization: recreated per profile; the 2D/3D choice survives switches.
    private Schematic2DView view2d;
    private Crane3DView view3d;
    private CraneSceneView activeView;
    private StackPane viewStack;
    private boolean use3d;
    /** 3D camera/cargo choices: kept here so they survive profile switches. */
    private CameraMode cameraChoice = CameraMode.ORBIT;
    private ComboBox<CameraMode> cameraSelector;
    private CargoType cargoChoice = CargoType.NONE;
    private ComboBox<CargoType> cargoSelector;

    // Weather: survives profile/driver switches; only the simulator models it.
    private volatile SimulatedCraneDriver simulator;
    private double windSpeed;
    private double windFromDeg;
    private Label windInfo;

    // Driver mode: crane locked out, truck drivable with the arrow keys.
    // Volatile: set on the JavaFX thread, read by the command thread — a hard
    // interlock must never be invisible to the thread that sends the commands.
    private volatile boolean driverMode;
    private ToggleButton driverModeButton;
    private Label driverInfo;
    private Button releaseButton;
    private final Set<KeyCode> drivingKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Assists: toggle choices survive profile switches; the sequencer never does.
    private final AutoSequencer foldSequencer = new AutoSequencer();
    private boolean smoothingOn;
    private boolean antiSwayOn;
    private ToggleButton foldButton;
    private Label foldStatus;

    // Crane back-end selection ("Simulator" or "Serial: COMx"), survives profile switches.
    private static final String DRIVER_SIMULATOR = "Simulator";
    private static final String DRIVER_SERIAL_PREFIX = "Serial: ";
    private String driverChoice = DRIVER_SIMULATOR;

    // HMI 2.0: resizable shell, gauges, synthesized cockpit audio.
    private Stage stage;
    private String version;
    private StackPane appStack;
    private Label statusPill;
    private Label headerSubtitle;
    private Node welcomeOverlay;
    private SplitPane mainSplit;
    private final SoundEngine soundEngine = new SoundEngine();
    private final UiSettings settings = new UiSettings();
    private ToggleButton muteButton;
    private Canvas slewDial;
    private final Map<String, ProgressBar> positionBars = new HashMap<>();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        version = getClass().getPackage().getImplementationVersion();
        CraneProfile startProfile = restoreSettings();

        root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG + ";");
        mainSplit = new SplitPane();
        mainSplit.setStyle("-fx-background-color: " + BG + "; -fx-background-insets: 0;"
                + " -fx-padding: 4;");
        root.setTop(buildHeaderBar());
        root.setCenter(mainSplit);
        // The three panes are (re)built per profile in activateProfile().

        // The welcome card sits over the cockpit until dismissed, so the first
        // thing a visitor sees is what the product is, not a wall of controls.
        appStack = new StackPane(root, buildWelcomeOverlay());

        Scene scene = new Scene(appStack, settings.windowWidth(), settings.windowHeight());
        scene.getStylesheets().add(getClass().getResource("/hmi.css").toExternalForm());
        installKeyHandlers(scene);

        activateProfile(startProfile);

        stage.setTitle("Crane Remote Control" + (version == null ? " (dev)" : " " + version));
        stage.setMinWidth(1060);
        stage.setMinHeight(680);
        stage.setMaximized(settings.maximised());
        // Losing focus means we will never see the matching key releases, so treat
        // it as the operator letting go of everything.
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                operatorInput.releaseAllKeys();
                drivingKeys.clear();
            }
        });
        stage.iconifiedProperty().addListener((obs, was, iconified) -> {
            if (iconified) {
                operatorInput.releaseAllKeys();
                drivingKeys.clear();
            }
        });

        stage.setScene(scene);
        stage.show();

        String snapshotDir = System.getProperty("crane.devSnapshotDir");
        if (DIAG) {
            runStressProbe();
        } else if (snapshotDir != null) {
            runDevSnapshotProbe(stage, Path.of(snapshotDir));
        }

        startCommandThread();
        openRecordingFromCommandLine();

        // The frame timer only DRAWS. It never produces commands, so a slow or
        // stalled renderer can no longer starve the control path.
        frameTimer = new AnimationTimer() {
            @Override
            public void handle(long frameNanos) {
                uiHeartbeatMillis = MonotonicClock.millis();   // "the UI is alive"
                CraneCommand command = lastCommand;
                CraneState state = displayState();
                if (command == null) {
                    return;
                }
                updateDriving();
                // The 3D view owns the truck and load physics, so it is stepped
                // every frame whether or not it is the one on screen. Only the
                // active view used to be updated, so switching to 2D froze the
                // truck mid-drive, froze falling loads, and left the interference
                // guard reading obstacle positions that had stopped moving.
                view3d.update(profile, state);
                backend.setLoadObstacles(view3d.loadObstacles());
                updateReadouts(command, state);
                if (activeView != view3d) {
                    activeView.update(profile, state);
                }
            }
        };
        frameTimer.start();
    }

    /**
     * {@code crane-remote-control run.csv} opens straight into that recording.
     * A telemetry file is then a self-contained thing you can send someone: they
     * see the machine move without owning one.
     */
    private void openRecordingFromCommandLine() {
        getParameters().getRaw().stream()
                .filter(argument -> argument.toLowerCase(java.util.Locale.ROOT).endsWith(".csv"))
                .findFirst()
                .ifPresent(argument -> beginReplay(Path.of(argument).toAbsolutePath()));
    }

    /**
     * Puts back the choices the operator left behind — crane, back-end, view,
     * camera, load, assists, weather, sound — before anything is built, so every
     * panel is constructed already showing the restored value.
     *
     * <p>Nothing safety-relevant is restored. Driver mode, the E-STOP latch and
     * the deadman always start from their safe state.
     *
     * @return the crane to activate
     */
    private CraneProfile restoreSettings() {
        driverChoice = settings.driverChoice(DRIVER_SIMULATOR);
        use3d = settings.use3d();
        cameraChoice = enumOrDefault(CameraMode.values(),
                settings.cameraMode(cameraChoice.name()), cameraChoice);
        cargoChoice = enumOrDefault(CargoType.values(),
                settings.cargoType(cargoChoice.name()), cargoChoice);
        smoothingOn = settings.smoothing();
        antiSwayOn = settings.antiSway();
        windSpeed = Math.clamp(settings.windSpeed(), 0, 20);
        windFromDeg = ((settings.windFromDeg() % 360) + 360) % 360;
        soundEngine.setMuted(settings.muted());

        String savedProfile = settings.profileId(catalog.get(0).id());
        return catalog.stream()
                .filter(candidate -> candidate.id().equals(savedProfile))
                .findFirst()
                .orElse(catalog.get(0));   // the saved crane may have been deleted
    }

    /** Enum lookup that tolerates a stored name from an older version. */
    private static <E extends Enum<E>> E enumOrDefault(E[] values, String name, E fallback) {
        for (E value : values) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return fallback;
    }

    private void saveSettings() {
        if (stage == null) {
            return;
        }
        // While maximised the stage reports the screen size; storing that would
        // make "restore down" on the next run fill the screen anyway. Keep the
        // last windowed size instead.
        boolean maximised = stage.isMaximized();
        double width = maximised ? settings.windowWidth() : stage.getWidth();
        double height = maximised ? settings.windowHeight() : stage.getHeight();
        settings.save(new UiSettings.Snapshot(
                width, height, maximised,
                profile.id(), driverChoice,
                use3d, cameraChoice.name(), cargoChoice.name(),
                smoothingOn, antiSwayOn,
                windSpeed, windFromDeg, soundEngine.isMuted()));
    }

    /**
     * Produces one operator command every {@value #COMMAND_PERIOD_MILLIS} ms on a
     * dedicated thread: samples {@link OperatorInput}, lets the auto-sequencer
     * override it, submits it to the control loop and feeds the sound engine.
     *
     * <p>Why not in the frame loop: the safety layer's watchdog stops the crane
     * when commands go stale (250 ms). Rendering the 3D scene can occasionally
     * take longer than that, which used to freeze the machine for no reason —
     * the operator's intent has nothing to do with how busy the GPU is.
     */
    private void startCommandThread() {
        commandThreadRunning = true;
        commandThread = new Thread(() -> {
            while (commandThreadRunning) {
                long started = System.nanoTime();
                try {
                    // One read of one reference: profile, input and backend can
                    // never be mismatched halfway through a session switch.
                    Session active = session;
                    if (active != null) {
                        OperatorInput input = active.input();
                        ControlLoopBackend activeBackend = active.backend();
                        CraneProfile activeProfile = active.profile();
                        CraneCommand command = input.snapshot(MonotonicClock.millis());

                        // Fail closed if the UI thread stops proving it is alive.
                        // Without this the cached key state would keep producing
                        // freshly-timestamped commands after a UI freeze or a lost
                        // key-release, and the watchdog would never notice.
                        if (!isUiAlive() || replaying || driverMode) {
                            // Motion suppressed, E-STOP still gets through, and the
                            // reset is dropped. A reset must never ride inside a
                            // command the program synthesised because the UI stalled,
                            // a recording is on screen, or the crane is locked out —
                            // in all three the operator is not looking at the live
                            // machine with the controls verifiably at neutral.
                            command = command.withMotionSuppressed(activeProfile);
                        } else if (foldSequencer.isActive()) {
                            command = foldSequencer.next(activeBackend.latestState(), command);
                        }
                        activeBackend.submitCommand(command);
                        lastCommand = command;
                        CraneState state = activeBackend.latestState();
                        if (DIAG) {
                            diagTick(state);
                        }
                        // Audio hears only effective demands: deadman released = pump idle.
                        soundEngine.update(state.deadmanHeld()
                                ? command : CraneCommand.neutral(activeProfile), state);
                    }
                } catch (RuntimeException e) {
                    System.err.println("[command] tick failed: " + e);
                }
                long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
                try {
                    Thread.sleep(Math.max(1, COMMAND_PERIOD_MILLIS - elapsedMillis));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "operator-command");
        commandThread.setDaemon(true);
        // The control path outranks the scenery: under load the OS should starve
        // a frame, never the command stream feeding the safety watchdog.
        commandThread.setPriority(Thread.MAX_PRIORITY);
        commandThread.start();
    }

    @Override
    public void stop() {
        saveSettings();
        if (frameTimer != null) {
            frameTimer.stop();
        }
        commandThreadRunning = false;
        if (commandThread != null) {
            commandThread.interrupt();
        }
        soundEngine.close();
        stopTelemetry();
        if (backend != null) {
            backend.stop();
        }
    }

    /**
     * Tears down the running session (backend, telemetry, panels) and rebuilds
     * the whole cockpit for the given profile. Runs on the FX thread; the frame
     * timer runs on the same thread, so no torn state is ever rendered.
     */
    private void activateProfile(CraneProfile newProfile) {
        stopTelemetry();
        // Ask the outgoing controller whether it was latched BEFORE stopping it.
        boolean latchWasEngaged = backend != null && backend.isEstopLatched();
        if (backend != null) {
            backend.stop();
        }

        profile = newProfile;
        operatorInput = new OperatorInput(keyBindings, profile.axisIds());
        // A latched emergency stop belongs to the operator and the machine, not to
        // a backend instance. Take it from the outgoing controller — the authority
        // — as well as from the operator's standing request. Reading it off the
        // E-STOP toggle was the bug: that button is rebuilt below, unselected.
        boolean carriedLatch = estopRequested || latchWasEngaged;
        operatorInput.setEstopRequested(estopRequested);
        foldSequencer.cancel(); // never carry an auto-sequence across cranes
        // The status panel (and with it the replay button) is rebuilt below, so
        // any running replay ends here rather than leaving the flag orphaned.
        recording = null;
        replaying = false;

        backend = new ControlLoopBackend(profile, createSelectedDriver());
        backend.configureAssists(smoothingOn, antiSwayOn);
        try {
            backend.start(); // connects the driver; serial handshake can fail here
        } catch (RuntimeException e) {
            recordEvent("driver '" + driverChoice + "' failed: " + e.getMessage()
                    + " — falling back to Simulator");
            driverChoice = DRIVER_SIMULATOR;
            SimulatedCraneDriver fallback = new SimulatedCraneDriver();
            // The field, not just a local: the weather controls and the guided demo
            // both key off it, and leaving it null after a failed serial connect
            // silently killed wind and made the demo refuse to run.
            simulator = fallback;
            fallback.setWind(windSpeed, windFromDeg);
            backend = new ControlLoopBackend(profile, fallback);
            backend.configureAssists(smoothingOn, antiSwayOn);
            backend.start();
        }
        if (carriedLatch) {
            backend.engageEstopLatch();
        }
        // Published last and as one reference: until this line the command thread
        // still sees the previous session, complete and consistent.
        session = new Session(profile, operatorInput, backend);

        demandReadouts.clear();
        positionReadouts.clear();
        alarmItems.clear();
        previousAlarms = Set.of();

        positionBars.clear();
        mainSplit.getItems().setAll(
                buildControlPanel(), buildCenterPane(), scrollable(buildStatusPanel(), 300));
        mainSplit.setDividerPositions(0.235, 0.75);
    }

    /**
     * Wraps a panel so its content scrolls instead of being clipped when the
     * window is short or the profile has many axes. Transparent chrome keeps the
     * panel's own rounded background visible.
     */
    private static ScrollPane scrollable(Region content, double minWidth) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);
        scroll.setMinWidth(minWidth);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        SplitPane.setResizableWithParent(scroll, false);
        return scroll;
    }

    // ---- TEMP stress diagnostics ----
    private static final boolean DIAG = System.getProperty("crane.devStress") != null;
    private long diagLast = System.nanoTime();
    private int diagTicks;

    private void diagTick(CraneState state) {
        diagTicks++;
        long now = System.nanoTime();
        if (now - diagLast >= 1_000_000_000L) {
            System.out.printf("[ctl] ticks/s=%d wd=%s dead=%s slew=%.1f boom=%.1f%n",
                    diagTicks, state.watchdogTripped(), state.deadmanHeld(),
                    state.position("slew"), state.position("boom"));
            diagTicks = 0;
            diagLast = now;
        }
    }

    /** TEMP: drives the crane hard in 3D to prove motion survives render stalls. */
    private void runStressProbe() {
        dismissWelcome();
        javafx.animation.Timeline script = new javafx.animation.Timeline(
                frameAt(0.5, () -> {
                    use3d = true;
                    activeView = view3d;
                    viewStack.getChildren().set(0, activeView.node());
                }),
                // At slew 0 the boom already points along the bed, so paying out
                // rope lowers the load straight onto the deck.
                frameAt(0.8, () -> {
                    cargoChoice = CargoType.CONTAINER;
                    applyCargo();
                }),
                frameAt(1.0, () -> {
                    operatorInput.keyPressed("SPACE");
                    operatorInput.keyPressed("W");   // raise the boom clear
                }),
                // Boom high enough that the hook comes down over the deck rather
                // than past the tail of the truck.
                frameAt(7.0, () -> {
                    operatorInput.keyReleased("W");
                    operatorInput.keyPressed("T");   // pay out: set it on the bed
                }),
                frameAt(13.0, () -> operatorInput.keyReleased("T")),
                // Then drive at the container yard: the truck must stop against it,
                // not pass through it.
                frameAt(18.0, () -> {
                    driverModeButton.setSelected(true);
                    drivingKeys.add(KeyCode.UP);
                    drivingKeys.add(KeyCode.LEFT);
                }),
                frameAt(23.0, () -> drivingKeys.remove(KeyCode.LEFT)),
                frameAt(90.0, javafx.application.Platform::exit));
        script.play();
    }

    // ---- dev snapshot probe (visual regression aid, -Dcrane.devSnapshotDir=<dir>) ----

    /**
     * Scripted self-test: drives the crane through the REAL input path (deadman +
     * axis keys), captures scene snapshots of the 2D view, the 3D view, and the
     * E-STOP state as PNGs into the given directory, then exits. Inert unless the
     * {@code crane.devSnapshotDir} system property is set.
     */
    private void runDevSnapshotProbe(Stage stage, Path dir) {
        dismissWelcome();   // the probe photographs the cockpit, not the front door
        javafx.animation.Timeline script = new javafx.animation.Timeline(
                frameAt(1.0, () -> snapshotScene(stage, dir, "01-2d-rest.png")),
                frameAt(1.2, () -> {
                    operatorInput.keyPressed("SPACE");
                    operatorInput.keyPressed("W");  // boom +
                    operatorInput.keyPressed("E");  // jib knuckle +
                    operatorInput.keyPressed("R");  // extension out
                    operatorInput.keyPressed("T");  // winch rope out
                }),
                frameAt(4.4, () -> {
                    operatorInput.keyReleased("W");
                    operatorInput.keyReleased("E");
                    operatorInput.keyReleased("R");
                    operatorInput.keyReleased("T");
                }),
                frameAt(4.5, () -> {
                    // BOAT on purpose: it exercises the hull mesh, the shape that
                    // rendered as stippled garbage before MeshFactory was fixed.
                    cargoChoice = CargoType.BOAT;
                    applyCargo();
                }),
                frameAt(4.9, () -> snapshotScene(stage, dir, "02-2d-articulated.png")),
                frameAt(5.0, () -> {
                    use3d = true;
                    activeView = view3d;
                    viewStack.getChildren().set(0, activeView.node());
                }),
                frameAt(5.9, () -> snapshotScene(stage, dir, "03-3d-orbit.png")),
                frameAt(6.8, () -> snapshotScene(stage, dir, "04-3d-cargo.png")),
                frameAt(6.9, () -> view3d.setCameraMode(CameraMode.HOOK)),
                frameAt(8.0, () -> snapshotScene(stage, dir, "05-3d-hook-cam.png")),
                frameAt(8.1, () -> view3d.setCameraMode(CameraMode.CAB)),
                frameAt(9.2, () -> snapshotScene(stage, dir, "06-3d-cab-cam.png")),
                frameAt(9.3, () -> {
                    view3d.setCameraMode(CameraMode.ORBIT);
                    operatorInput.setEstopRequested(true);
                }),
                frameAt(10.2, () -> snapshotScene(stage, dir, "07-3d-estop.png")),
                frameAt(10.5, javafx.application.Platform::exit));
        script.play();
    }

    private static javafx.animation.KeyFrame frameAt(double seconds, Runnable action) {
        return new javafx.animation.KeyFrame(
                javafx.util.Duration.seconds(seconds), event -> action.run());
    }

    /** Renders the scene off-screen and writes it as PNG (no javafx-swing needed). */
    private static void snapshotScene(Stage stage, Path dir, String fileName) {
        try {
            javafx.scene.image.WritableImage image = stage.getScene().snapshot(null);
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                    width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            javafx.scene.image.PixelReader reader = image.getPixelReader();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            java.nio.file.Files.createDirectories(dir);
            javax.imageio.ImageIO.write(out, "png", dir.resolve(fileName).toFile());
            System.out.println("[snapshot] wrote " + dir.resolve(fileName));
        } catch (Exception e) {
            System.err.println("[snapshot] failed for " + fileName + ": " + e);
        }
    }

    // ---- guided demo ----

    private boolean demoRunning;
    private javafx.animation.Timeline demoTimeline;
    private Label demoCaption;
    private ToggleButton demoButton;

    /** The scripted presentations available from the header. */
    private enum DemoScenario {
        LOADING("Loading a truck"),
        PRECISION("Precision placement"),
        SAFETY("Safety and emergency stop");

        private final String label;

        DemoScenario(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private DemoScenario demoScenario = DemoScenario.LOADING;

    private ComboBox<DemoScenario> buildScenarioSelector() {
        ComboBox<DemoScenario> box = new ComboBox<>(
                FXCollections.observableArrayList(DemoScenario.values()));
        box.setFocusTraversable(false);
        box.setPrefWidth(210);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(DemoScenario scenario) {
                return scenario == null ? "" : scenario.label();
            }

            @Override
            public DemoScenario fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        box.setValue(demoScenario);
        box.setOnAction(event -> demoScenario = box.getValue());
        box.setTooltip(new Tooltip("Which narrated sequence RUN DEMO plays."));
        return box;
    }

    private Node buildDemoButton() {
        demoButton = new ToggleButton(">  RUN DEMO");
        demoButton.getStyleClass().add("primary-button");
        demoButton.setFocusTraversable(false);
        demoButton.setTooltip(new Tooltip(
                "Plays a narrated sequence: hook a load, set it on the truck, drive away, "
                        + "and trip the emergency stop. It paces itself to the selected "
                        + "crane's axis speeds, so a slower machine simply takes longer."));
        demoButton.selectedProperty().addListener((obs, was, selected) -> {
            if (selected) {
                startDemo();
            } else {
                stopDemo();
            }
        });
        return demoButton;
    }

    private void showCaption(String text) {
        demoCaption.setText(text);
        demoCaption.setVisible(true);
    }

    /**
     * A scripted presentation of the product: every step narrated, driven through
     * the same input path an operator uses, so nothing on screen is faked.
     */
    private void startDemo() {
        if (demoRunning) {
            return;
        }
        // HARD RULE: the demo synthesises operator input, including the deadman.
        // It may only ever drive the simulator. Autonomous motion of a real
        // machine from a sales demo is not a feature, it is an accident.
        if (simulator == null) {
            recordEvent("Demo refused: it runs on the simulator only, not on "
                    + driverChoice);
            demoButton.setSelected(false);
            return;
        }
        if (replaying) {
            stopReplay();   // the demo needs the live simulator on screen
        }
        dismissWelcome();
        demoRunning = true;
        demoButton.setSelected(true);
        demoButton.setText("#  STOP DEMO");

        cargoChoice = CargoType.CONTAINER;
        applyCargo();
        applyCamera(CameraMode.ORBIT);
        if (!use3d) {
            showView3d();
        }

        demoTimeline = new javafx.animation.Timeline();
        demoTimeline.getKeyFrames().addAll(switch (demoScenario) {
            case PRECISION -> precisionScenario();
            case SAFETY -> safetyScenario();
            default -> loadingScenario();
        });
        demoTimeline.play();
    }

    /**
     * Boom angle that puts the jib tip over the middle of the deck. The arm reaches
     * 8 m from the slew axis (5 m boom + 3 m jib), so the tip lands 8·cos(angle)
     * out — about 4.4 m at 57°, comfortably inside the 6.15 m deck.
     */
    private static final double DECK_DROP_BOOM_DEG = 57.0;
    /** Rope paid out during the demo; the winch clamp stops it on the deck. */
    private static final double DEMO_ROPE_OUT_METRES = 9.0;

    /**
     * Seconds to hold a key to move an axis by {@code amount} of its own unit,
     * with an allowance for the command ramp.
     *
     * <p>The demo used to hold each key for a hard-coded number of seconds, which
     * silently assumed the demo crane's axis speeds. On the heavy profile — boom
     * 5°/s instead of 8°/s — six seconds of boom only reached 28°, the jib tip
     * stopped a metre behind the deck, and the load was set on the ground. The
     * truck then drove away while the caption said "with the load aboard". A demo
     * has to work on whichever crane the customer picked from the list.
     */
    private double holdSeconds(String axisId, double amount, double fallback) {
        return profile.axisById(axisId)
                .map(axis -> Math.clamp(
                        Math.abs(amount) / axis.maxVelocity() + 1.0 / axis.commandRampRate(),
                        1.0, 20.0))
                .orElse(fallback);
    }

    /** The original pitch: hook a load, set it on the deck, drive away. */
    private List<javafx.animation.KeyFrame> loadingScenario() {
        double boomNow = backend.latestState().position("boom");
        double boomHold = holdSeconds("boom", DECK_DROP_BOOM_DEG - boomNow, 6.0);
        double winchHold = holdSeconds("winch", DEMO_ROPE_OUT_METRES, 6.0);

        // Laid out on a running cursor rather than at fixed times, so a slower
        // crane simply takes longer instead of stopping short.
        double raise = 3.0;
        double payOut = raise + boomHold;
        double down = payOut + winchHold;
        double drive = down + 5.0;
        double estop = drive + 7.0;
        double closing = estop + 6.0;

        return List.of(
                frameAt(0.2, () -> showCaption(
                        "A container hangs on the hook. Nothing moves yet — the crane "
                                + "only responds while the operator holds the deadman.")),
                frameAt(raise, () -> {
                    showCaption("Deadman held. Raising the main boom.");
                    operatorInput.keyPressed("SPACE");
                    operatorInput.keyPressed("W");
                }),
                frameAt(payOut, () -> {
                    showCaption("Paying out the winch to set the load on the truck's own deck.");
                    operatorInput.keyReleased("W");
                    operatorInput.keyPressed("T");
                }),
                frameAt(down, () -> {
                    showCaption("Load down. The arm cannot be driven into the truck, the "
                            + "ground or anything standing nearby — interference protection "
                            + "stops the axis first.");
                    operatorInput.keyReleased("T");
                }),
                frameAt(drive, () -> {
                    showCaption("Driver mode: the crane is locked out completely and the "
                            + "truck can be driven away with the load aboard.");
                    operatorInput.keyReleased("SPACE");
                    driverModeButton.setSelected(true);
                    drivingKeys.add(KeyCode.UP);
                }),
                frameAt(estop, () -> {
                    showCaption("Emergency stop: latches instantly, and stays latched until "
                            + "it is reset with every control at neutral.");
                    drivingKeys.remove(KeyCode.UP);
                    driverModeButton.setSelected(false);
                    estopButton.setSelected(true);
                }),
                frameAt(closing, () -> showCaption(
                        "The same software drives a simulator or real hardware — the crane "
                                + "is reached only through a driver interface.")),
                frameAt(closing + 6.0, this::stopDemo));
    }

    /** Shows the assists earning their keep: wind, sway, anti-sway, hook camera. */
    private List<javafx.animation.KeyFrame> precisionScenario() {
        return List.of(
                frameAt(0.2, () -> {
                    showCaption("Placing a load precisely, in wind. First without any "
                            + "assistance.");
                    applyCamera(CameraMode.HOOK);
                    windSpeed = 12;
                    windFromDeg = 90;
                    applyWind();
                }),
                frameAt(3.0, () -> {
                    operatorInput.keyPressed("SPACE");
                    operatorInput.keyPressed("T");     // pay out rope: a long pendulum
                }),
                frameAt(8.0, () -> {
                    showCaption("A 12 m/s crosswind pushes the load off vertical and the "
                            + "slew excites it. Watch the swing.");
                    operatorInput.keyReleased("T");
                    operatorInput.keyPressed("Q");
                }),
                frameAt(13.0, () -> operatorInput.keyReleased("Q")),
                frameAt(18.0, () -> {
                    showCaption("Now with ANTI-SWAY: the crane corrects the slew against the "
                            + "measured swing and the load settles in roughly half the time.");
                    antiSwayOn = true;
                    backend.configureAssists(smoothingOn, true);
                    operatorInput.keyPressed("Q");
                }),
                frameAt(23.0, () -> operatorInput.keyReleased("Q")),
                frameAt(30.0, () -> showCaption(
                        "Smoothing and anti-sway are assists: they shape the operator's "
                                + "demand, but the safety layer still has the final word.")),
                frameAt(36.0, this::stopDemo));
    }

    /** The part a manufacturer actually buys: what happens when things go wrong. */
    private List<javafx.animation.KeyFrame> safetyScenario() {
        return List.of(
                frameAt(0.2, () -> showCaption(
                        "Every safety behaviour in this sequence is enforced by the control "
                                + "core, not by the screen.")),
                frameAt(4.0, () -> {
                    showCaption("Hold-to-run: the crane moves only while the deadman is held.");
                    operatorInput.keyPressed("SPACE");
                    operatorInput.keyPressed("W");
                }),
                frameAt(9.0, () -> {
                    showCaption("Deadman released — motion ramps down under control rather "
                            + "than stopping dead, which would shock the hydraulics.");
                    operatorInput.keyReleased("SPACE");
                }),
                frameAt(14.0, () -> {
                    showCaption("Interference protection: the arm will not be driven into "
                            + "the truck, the ground, or a load standing nearby.");
                    operatorInput.keyPressed("SPACE");
                    operatorInput.keyPressed("E");   // fold the jib toward the deck
                }),
                frameAt(20.0, () -> {
                    showCaption("Emergency stop latches instantly and stays latched. It "
                            + "cannot be cleared by software — only by a person, with every "
                            + "control at neutral.");
                    operatorInput.releaseAllKeys();
                    estopButton.setSelected(true);
                }),
                frameAt(27.0, () -> showCaption(
                        "A watchdog does the same job if commands stop arriving at all: a "
                                + "frozen program or a cut cable stops the machine.")),
                frameAt(33.0, this::stopDemo));
    }

    /** Ends the demo and hands a clean, safe machine back to the operator. */
    private void stopDemo() {
        if (demoTimeline != null) {
            demoTimeline.stop();
            demoTimeline = null;
        }
        demoRunning = false;
        operatorInput.releaseAllKeys();
        drivingKeys.clear();
        driverModeButton.setSelected(false);
        // The latch is NOT cleared here. An emergency stop stays latched until a
        // person presses RESET with the controls at neutral — a program must
        // never clear it on the operator's behalf, demo or not.
        if (estopButton.isSelected()) {
            showCaption("Emergency stop is still latched — press RESET to clear it.");
        } else {
            demoCaption.setVisible(false);
        }
        demoButton.setSelected(false);
        demoButton.setText(">  RUN DEMO");
    }

    private void showView3d() {
        use3d = true;
        activeView = view3d;
        viewStack.getChildren().set(0, activeView.node());
    }

    // ---- product chrome ----

    /**
     * Header bar: what the product is, what it is driving, and one status pill
     * that answers "is it safe and is it moving" from across a room.
     */
    private Node buildHeaderBar() {
        Label title = new Label("CRANE REMOTE CONTROL");
        title.getStyleClass().add("h1");

        Label versionLabel = new Label(version == null ? "dev build" : "v" + version);
        versionLabel.getStyleClass().add("caption");

        headerSubtitle = new Label();
        headerSubtitle.getStyleClass().add("caption");

        statusPill = new Label("READY");
        statusPill.getStyleClass().addAll("pill", "pill-idle");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox left = new HBox(10, title, versionLabel);
        left.setAlignment(Pos.CENTER_LEFT);

        HBox bar = new HBox(16, left, headerSubtitle, spacer,
                buildScenarioSelector(), buildDemoButton(), statusPill);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("app-header");
        return bar;
    }

    /** Keeps the header pill honest about what the machine is doing. */
    private void updateStatusPill(CraneState state) {
        String text;
        String style;
        // The latch is read from the LIVE machine, never from `state`: while a
        // recording is on screen `state` carries the recording's flags, and the
        // header would happily report the past as the present.
        boolean liveLatched = backend.latestState().estopLatched();
        if (liveLatched) {
            // Ahead of everything else, replay included: a latched machine is the
            // most important thing this header can say. The HMI used to be able to
            // show a green RUN ENABLED beside a latched E-STOP.
            text = "E-STOP LATCHED";
            style = "pill-alarm";
        } else if (replaying) {
            text = "REPLAY — RECORDED";
            style = "pill-warn";
        } else if (demoRunning) {
            text = "DEMO RUNNING";
            style = "pill-warn";
        } else if (driverMode) {
            text = "DRIVER MODE";
            style = "pill-warn";
        } else if (backend.isCollisionBlocking()) {
            text = "BLOCKED — OBSTACLE";
            style = "pill-warn";
        } else if (state.deadmanHeld()) {
            text = "RUNNING";
            style = "pill-ok";
        } else {
            text = "READY — HOLD SPACE";
            style = "pill-idle";
        }
        if (!text.equals(statusPill.getText())) {
            statusPill.setText(text);
            statusPill.getStyleClass().removeIf(s -> s.startsWith("pill-"));
            statusPill.getStyleClass().add(style);
        }
        headerSubtitle.setText(profile.name() + "  ·  " + backend.driverName());
    }

    /**
     * The welcome card. A visitor should learn what this is before being handed
     * the controls; the button is the only way past it.
     */
    private Node buildWelcomeOverlay() {
        Label title = new Label("Crane Remote Control");
        title.getStyleClass().add("welcome-title");

        Label pitch = new Label("Universal control software for hydraulic loader cranes.");
        pitch.getStyleClass().add("welcome-sub");

        Label detail = new Label("""
                One program drives any crane: the machine is described by a data file,
                not by code. A full safety layer — latching emergency stop, hold-to-run,
                watchdog, limits and interference protection — sits between the operator
                and the machine, whether that machine is this simulator or real hardware.""");
        detail.getStyleClass().add("welcome-note");
        detail.setWrapText(true);
        detail.setMaxWidth(560);

        Button demo = new Button("Watch the narrated demo");
        demo.getStyleClass().addAll("welcome-button", "primary-button");
        demo.setMaxWidth(320);
        demo.setOnAction(event -> {
            dismissWelcome();
            startDemo();
        });

        Button explore = new Button("Take the controls");
        explore.getStyleClass().add("welcome-button");
        explore.setMaxWidth(320);
        explore.setOnAction(event -> dismissWelcome());

        Label hint = new Label("Hold SPACE to enable motion · Q/A W/S E/D R/F T/G drive the axes");
        hint.getStyleClass().add("welcome-note");

        VBox card = new VBox(14, title, pitch, detail, demo, explore, hint);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(40));

        StackPane scrim = new StackPane(card);
        scrim.getStyleClass().add("welcome-scrim");
        welcomeOverlay = scrim;
        return scrim;
    }

    private void dismissWelcome() {
        if (welcomeOverlay != null) {
            appStack.getChildren().remove(welcomeOverlay);
            welcomeOverlay = null;
        }
    }

    /** The crane back-end for the current driver choice. */
    private CraneDriver createSelectedDriver() {
        if (driverChoice.startsWith(DRIVER_SERIAL_PREFIX)) {
            simulator = null;
            return new SerialCraneDriver(driverChoice.substring(DRIVER_SERIAL_PREFIX.length()));
        }
        SimulatedCraneDriver sim = new SimulatedCraneDriver();
        sim.setWind(windSpeed, windFromDeg);   // carry the weather across restarts
        simulator = sim;
        return sim;
    }

    /** Pushes the current weather to the simulator, if one is running. */
    private void applyWind() {
        SimulatedCraneDriver sim = simulator;
        if (sim != null) {
            sim.setWind(windSpeed, windFromDeg);
        }
    }

    /** Timestamped entry in the alarm history (driver failures, notable events). */
    private void recordEvent(String message) {
        alarmHistory.add(0, LocalTime.now().format(ALARM_STAMP) + "  " + message);
        if (alarmHistory.size() > ALARM_HISTORY_LIMIT) {
            alarmHistory.remove(ALARM_HISTORY_LIMIT, alarmHistory.size());
        }
    }

    // ---- left: controls ----

    private BorderPane buildControlPanel() {
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(14, 14, 4, 14));
        panel.setStyle("-fx-background-color: " + PANEL_BG + ";");

        panel.getChildren().add(sectionLabel("CONTROLS"));
        for (AxisSpec axis : profile.axes()) {
            panel.getChildren().add(buildAxisControl(axis));
        }

        panel.getChildren().add(sectionLabel("ASSIST"));
        panel.getChildren().add(buildAssistControls());

        panel.getChildren().add(sectionLabel("TRUCK & LOAD"));
        panel.getChildren().add(buildTruckControls());

        panel.getChildren().add(sectionLabel("WEATHER"));
        panel.getChildren().add(buildWindControls());

        estopButton = new ToggleButton("E-STOP");
        estopButton.setMaxWidth(Double.MAX_VALUE);
        estopButton.setFocusTraversable(false);
        estopButton.getStyleClass().add("estop-button");
        estopButton.setTooltip(new Tooltip(
                "Latches immediately: every demand is forced to zero and stays there "
                        + "until RESET, with all controls at neutral. Shortcut: Esc"));
        // Restored from the standing request BEFORE the listener is attached, so a
        // rebuilt panel shows the mushroom where the operator left it and does not
        // re-fire the request while doing so.
        estopButton.setSelected(estopRequested);
        estopButton.selectedProperty().addListener((obs, was, selected) -> {
            estopRequested = selected;
            operatorInput.setEstopRequested(selected);
        });

        Button resetButton = new Button("RESET");
        resetButton.setMaxWidth(Double.MAX_VALUE);
        resetButton.setFocusTraversable(false);
        resetButton.getStyleClass().add("primary-button");
        resetButton.setTooltip(new Tooltip(
                "Releases the emergency stop and asks the safety layer to clear the "
                        + "latch — accepted only with every control at neutral and the "
                        + "deadman released. If it is refused, the latch stays on."));
        resetButton.setOnAction(event -> {
            // Releasing the mushroom is the operator's act and takes effect now.
            // Clearing the LATCH is the core's decision, and the lamp keeps showing
            // the core's answer: a refused reset leaves the machine latched, and the
            // HMI has to keep saying so rather than looking cleared.
            estopRequested = false;
            estopButton.setSelected(false);
            operatorInput.requestReset();     // one-shot reset in the next command
            if (backend.latestState().estopLatched()) {
                recordEvent("RESET requested — the latch clears only with every "
                        + "control at neutral and the deadman released");
            }
        });

        deadmanIndicator = new Label("HOLD SPACE TO RUN");
        deadmanIndicator.setMaxWidth(Double.MAX_VALUE);
        deadmanIndicator.setAlignment(Pos.CENTER);
        deadmanIndicator.setMinHeight(34);
        deadmanIndicator.setStyle(deadmanStyle(false));

        Label fullscreenHint = new Label("F11 — fullscreen · drag panel edges to resize");
        fullscreenHint.setMaxWidth(Double.MAX_VALUE);
        fullscreenHint.setAlignment(Pos.CENTER);
        fullscreenHint.setStyle("-fx-text-fill: #566470; -fx-font-size: 10px;");

        // Safety controls are pinned to the bottom and never scroll out of reach;
        // only the axis/assist controls above them scroll on a short window.
        VBox safetyBox = new VBox(8, estopButton, resetButton, deadmanIndicator,
                fullscreenHint);
        safetyBox.setPadding(new Insets(12, 14, 14, 14));
        safetyBox.setStyle("-fx-background-color: " + PANEL_BG
                + "; -fx-background-radius: 0 0 8 8;");

        BorderPane column = new BorderPane();
        column.getStyleClass().add("panel");
        column.setCenter(scrollable(panel, 0));
        column.setBottom(safetyBox);
        column.setMinWidth(264);
        column.setPrefWidth(300);
        SplitPane.setResizableWithParent(column, false);
        return column;
    }

    private VBox buildAssistControls() {
        ToggleButton smoothToggle = new ToggleButton("SMOOTHING");
        ToggleButton antiSwayToggle = new ToggleButton("ANTI-SWAY");
        for (ToggleButton toggle : List.of(smoothToggle, antiSwayToggle)) {
            toggle.setMaxWidth(Double.MAX_VALUE);
            toggle.setFocusTraversable(false);
            HBox.setHgrow(toggle, Priority.ALWAYS);
        }
        smoothToggle.setSelected(smoothingOn);
        antiSwayToggle.setSelected(antiSwayOn);
        smoothToggle.selectedProperty().addListener((obs, was, selected) -> {
            smoothingOn = selected;
            backend.configureAssists(smoothingOn, antiSwayOn);
        });
        antiSwayToggle.selectedProperty().addListener((obs, was, selected) -> {
            antiSwayOn = selected;
            backend.configureAssists(smoothingOn, antiSwayOn);
        });

        foldButton = new ToggleButton("FOLD TO TRANSPORT");
        foldButton.setMaxWidth(Double.MAX_VALUE);
        foldButton.setFocusTraversable(false);
        foldButton.selectedProperty().addListener((obs, was, selected) -> {
            if (selected) {
                foldSequencer.start(profile);
            } else {
                foldSequencer.cancel();
            }
        });

        foldStatus = new Label("auto-fold idle");
        foldStatus.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 11px;");
        foldStatus.setWrapText(true);

        return new VBox(6, new HBox(6, smoothToggle, antiSwayToggle), foldButton, foldStatus);
    }

    /**
     * Driver mode and load release. Driver mode locks the crane out completely —
     * you are either operating the crane or driving the truck, never both, which
     * is exactly how the real machine is used.
     */
    private VBox buildTruckControls() {
        driverModeButton = new ToggleButton("DRIVER MODE");
        driverModeButton.setMaxWidth(Double.MAX_VALUE);
        driverModeButton.setFocusTraversable(false);
        driverModeButton.setSelected(driverMode);
        driverModeButton.selectedProperty().addListener((obs, was, selected) -> {
            driverMode = selected;
            view3d.setDriverMode(selected);
            if (selected) {
                foldSequencer.cancel();      // no automation while driving
            }
        });

        releaseButton = new Button("RELEASE LOAD");
        releaseButton.setMaxWidth(Double.MAX_VALUE);
        releaseButton.setMinHeight(34);
        releaseButton.setFocusTraversable(false);
        releaseButton.setStyle("-fx-background-color: " + BG + "; -fx-text-fill: " + TEXT + ";"
                + " -fx-border-color: " + TEXT_DIM + "; -fx-border-radius: 6;"
                + " -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 11px;");
        releaseButton.setOnAction(event -> view3d.releaseCargo());

        driverInfo = new Label("crane active · truck parked");
        driverInfo.setWrapText(true);
        driverInfo.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 11px;");

        return new VBox(6, driverModeButton, releaseButton, driverInfo);
    }

    /** Compass points, clockwise from north — the direction the wind blows FROM. */
    private static final String[] COMPASS_POINTS =
            {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /**
     * Wind speed and bearing. Wind pushes the hanging load off vertical and
     * excites the sway model, so it makes anti-sway visibly worth having.
     */
    private VBox buildWindControls() {
        Slider speed = new Slider(0, 20, windSpeed);
        speed.setFocusTraversable(false);
        speed.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> direction = new ComboBox<>(
                FXCollections.observableArrayList(COMPASS_POINTS));
        direction.setMaxWidth(Double.MAX_VALUE);
        direction.setFocusTraversable(false);
        direction.setValue(COMPASS_POINTS[(int) Math.round(windFromDeg / 45.0) % 8]);

        windInfo = new Label();
        windInfo.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 11px;");

        Runnable apply = () -> {
            windSpeed = speed.getValue();
            int index = java.util.Arrays.asList(COMPASS_POINTS).indexOf(direction.getValue());
            windFromDeg = Math.max(0, index) * 45.0;
            applyWind();
            windInfo.setText(windSpeed < 0.5
                    ? "still air"
                    : String.format("%.0f m/s from %s", windSpeed, direction.getValue()));
        };
        speed.valueProperty().addListener((obs, was, now) -> apply.run());
        direction.setOnAction(event -> apply.run());
        apply.run();

        return new VBox(4, speed, direction, windInfo);
    }

    private VBox buildAxisControl(AxisSpec axis) {
        Label name = new Label(axis.label());
        name.setStyle("-fx-text-fill: " + TEXT + "; -fx-font-size: 12px;");

        Label demand = new Label("+0.00");
        demand.setStyle(readoutStyle(TEXT_DIM));
        demandReadouts.put(axis.id(), demand);

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox header = new HBox(6, name, gap, demand);
        header.setAlignment(Pos.CENTER_LEFT);

        Slider slider = new Slider(-1.0, 1.0, 0.0);
        slider.setBlockIncrement(0.1);
        slider.setFocusTraversable(false);
        slider.valueProperty().addListener((obs, was, value) ->
                operatorInput.setSliderDemand(axis.id(), value.doubleValue()));
        // Spring back to neutral the moment the operator lets go.
        slider.setOnMouseReleased(event -> slider.setValue(0.0));

        return new VBox(2, header, slider);
    }

    // ---- center: visualization (switchable 2D schematic / 3D scene) ----

    private StackPane buildCenterPane() {
        view2d = new Schematic2DView();
        view3d = new Crane3DView();
        view3d.setCameraMode(cameraChoice);
        view3d.setDriverMode(driverMode);
        applyCargo();

        viewStack = new StackPane();
        activeView = use3d ? view3d : view2d;
        viewStack.getChildren().add(activeView.node());

        ToggleButton btn2d = new ToggleButton("2D");
        ToggleButton btn3d = new ToggleButton("3D");
        ToggleGroup group = new ToggleGroup();
        btn2d.setToggleGroup(group);
        btn3d.setToggleGroup(group);
        btn2d.setFocusTraversable(false);
        btn3d.setFocusTraversable(false);
        (use3d ? btn3d : btn2d).setSelected(true);
        btn2d.setStyle(viewToggleStyle(!use3d));
        btn3d.setStyle(viewToggleStyle(use3d));
        group.selectedToggleProperty().addListener((obs, was, selected) -> {
            if (selected == null) {
                was.setSelected(true); // one view is always active — no deselection
                return;
            }
            use3d = selected == btn3d;
            activeView = use3d ? view3d : view2d;
            viewStack.getChildren().set(0, activeView.node());
            btn2d.setStyle(viewToggleStyle(!use3d));
            btn3d.setStyle(viewToggleStyle(use3d));
        });

        HBox toggleBar = new HBox(6, btn2d, btn3d);
        toggleBar.setPadding(new Insets(8));
        toggleBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        toggleBar.setPickOnBounds(false);

        demoCaption = new Label();
        demoCaption.getStyleClass().add("demo-caption");
        demoCaption.setWrapText(true);
        demoCaption.setMaxWidth(620);
        demoCaption.setVisible(false);
        demoCaption.setMouseTransparent(true);

        StackPane center = new StackPane(viewStack, toggleBar, demoCaption);
        // Top-left: the top-right corner belongs to the 2D top-view inset.
        StackPane.setAlignment(toggleBar, Pos.TOP_LEFT);
        StackPane.setAlignment(demoCaption, Pos.BOTTOM_CENTER);
        StackPane.setMargin(demoCaption, new Insets(0, 0, 26, 0));
        return center;
    }

    private static String viewToggleStyle(boolean selected) {
        String color = selected ? AMBER : TEXT_DIM;
        return "-fx-background-color: " + PANEL_BG + "; -fx-text-fill: " + color + ";"
                + " -fx-border-color: " + color + "; -fx-border-radius: 6;"
                + " -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 12px;";
    }

    // ---- right: status ----

    private VBox buildStatusPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(14));
        panel.setPrefWidth(320);
        panel.setMinWidth(300);
        panel.setStyle("-fx-background-color: " + PANEL_BG + "; -fx-background-radius: 6;");
        SplitPane.setResizableWithParent(panel, false);

        panel.getChildren().add(sectionLabel("PROFILE"));
        panel.getChildren().add(buildProfileSelector());
        panel.getChildren().add(buildProfileEditorButtons());
        panel.getChildren().add(sectionLabel("DRIVER"));
        panel.getChildren().add(buildDriverSelector());
        panel.getChildren().add(sectionLabel("3D VIEW"));
        panel.getChildren().add(buildCameraSelector());
        panel.getChildren().add(buildCargoSelector());

        panel.getChildren().add(sectionLabel("AXIS POSITIONS"));
        for (AxisSpec axis : profile.axes()) {
            Label value = new Label("--");
            value.setStyle(readoutStyle(TEXT));
            positionReadouts.put(axis.id(), value);

            if (axis.id().equals("slew")) {
                // Radial dial for the slew ring; the numeric readout sits below it.
                slewDial = new Canvas(112, 96);
                Label name = new Label(axis.label());
                name.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 12px;");
                Region gap = new Region();
                HBox.setHgrow(gap, Priority.ALWAYS);
                HBox caption = new HBox(6, name, gap, value);
                caption.setAlignment(Pos.CENTER_LEFT);
                VBox dialBox = new VBox(2, slewDial, caption);
                dialBox.setAlignment(Pos.CENTER);
                panel.getChildren().add(dialBox);
            } else {
                // Bar meter: label | position-in-range bar | numeric readout.
                Label name = new Label(axis.label());
                name.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 11px;");
                name.setMinWidth(88);
                ProgressBar bar = new ProgressBar(0);
                bar.setMaxWidth(Double.MAX_VALUE);
                bar.setPrefHeight(10);
                bar.setFocusTraversable(false);
                HBox.setHgrow(bar, Priority.ALWAYS);
                positionBars.put(axis.id(), bar);
                value.setMinWidth(64);
                value.setAlignment(Pos.CENTER_RIGHT);
                HBox row = new HBox(6, name, bar, value);
                row.setAlignment(Pos.CENTER_LEFT);
                panel.getChildren().add(row);
            }
        }

        panel.getChildren().add(sectionLabel("SAFETY"));
        estopLamp = new Circle(7, LAMP_OFF);
        deadmanLamp = new Circle(7, LAMP_OFF);
        watchdogLamp = new Circle(7, LAMP_OFF);
        panel.getChildren().add(lampRow(estopLamp, "E-STOP LATCHED"));
        panel.getChildren().add(lampRow(deadmanLamp, "DEADMAN HELD"));
        panel.getChildren().add(lampRow(watchdogLamp, "WATCHDOG TRIPPED"));

        panel.getChildren().add(sectionLabel("TELEMETRY"));
        recordButton = new ToggleButton("REC");
        recordButton.setFocusTraversable(false);
        recordButton.setMaxWidth(Double.MAX_VALUE);
        recordButton.setStyle(recStyle(false));
        recordButton.selectedProperty().addListener((obs, was, selected) -> {
            recordButton.setStyle(recStyle(selected));
            if (selected) {
                startTelemetry();
            } else {
                stopTelemetry();
            }
        });
        recordInfo = new Label("not recording");
        recordInfo.setWrapText(true);
        recordInfo.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 11px;");
        panel.getChildren().addAll(recordButton, recordInfo, buildReplayButton());

        panel.getChildren().add(sectionLabel("SOUND"));
        muteButton = new ToggleButton("MUTE");
        muteButton.setFocusTraversable(false);
        muteButton.setMaxWidth(Double.MAX_VALUE);
        muteButton.setSelected(soundEngine.isMuted());
        muteButton.setStyle(recStyle(soundEngine.isMuted()));
        muteButton.selectedProperty().addListener((obs, was, selected) -> {
            soundEngine.setMuted(selected);
            muteButton.setStyle(recStyle(selected));
        });
        if (!soundEngine.isAvailable()) {
            muteButton.setDisable(true);
            muteButton.setText("NO AUDIO DEVICE");
        }
        panel.getChildren().add(muteButton);

        panel.getChildren().add(sectionLabel("ACTIVE ALARMS"));
        ListView<String> alarmList = alarmListView(alarmItems, ALARM_RED);
        alarmList.setPrefHeight(90);
        panel.getChildren().add(alarmList);

        panel.getChildren().add(sectionLabel("ALARM HISTORY"));
        ListView<String> historyList = alarmListView(alarmHistory, TEXT_DIM);
        historyList.setPrefHeight(150); // fixed: the panel scrolls as a whole
        panel.getChildren().add(historyList);
        return panel;
    }

    /**
     * Back-end picker: the simulator plus every COM port present on this machine.
     * Selecting a port reconnects the whole session through the serial driver
     * (docs/PROTOCOL.md); a failed handshake logs an event and falls back to the
     * simulator, so a wrong choice can never brick the cockpit.
     */
    private ComboBox<String> buildDriverSelector() {
        List<String> options = new java.util.ArrayList<>();
        options.add(DRIVER_SIMULATOR);
        SerialPorts.availablePortNames()
                .forEach(port -> options.add(DRIVER_SERIAL_PREFIX + port));

        ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(options));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFocusTraversable(false);
        box.setValue(options.contains(driverChoice) ? driverChoice : DRIVER_SIMULATOR);
        // Wired after setValue so the initial selection cannot re-trigger a rebuild.
        box.setOnAction(event -> {
            String selected = box.getValue();
            if (selected != null && !selected.equals(driverChoice)) {
                driverChoice = selected;
                activateProfile(profile); // full reconnect through the new back-end
            }
        });
        return box;
    }

    /** Viewpoint picker for the 3D scene; the choice survives profile switches. */
    private ComboBox<CameraMode> buildCameraSelector() {
        ComboBox<CameraMode> box = new ComboBox<>(
                FXCollections.observableArrayList(CameraMode.values()));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFocusTraversable(false);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(CameraMode mode) {
                return mode == null ? "" : "Camera: " + mode.label();
            }

            @Override
            public CameraMode fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        box.setValue(cameraChoice);
        box.setOnAction(event -> {
            cameraChoice = box.getValue();
            view3d.setCameraMode(cameraChoice);
        });
        cameraSelector = box;
        return box;
    }

    /**
     * Points the 3D view at a camera and keeps the selector in step. The demo
     * switches to the hook camera directly, and the panel went on reading
     * "Camera: Orbit" beside an obviously different viewpoint.
     */
    private void applyCamera(CameraMode mode) {
        cameraChoice = mode;
        view3d.setCameraMode(mode);
        if (cameraSelector != null && cameraSelector.getValue() != mode) {
            cameraSelector.setValue(mode);
        }
    }

    /** Load on the hook — visual only, it does not affect the simulation. */
    private ComboBox<CargoType> buildCargoSelector() {
        ComboBox<CargoType> box = new ComboBox<>(
                FXCollections.observableArrayList(CargoType.values()));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFocusTraversable(false);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(CargoType type) {
                return type == null ? "" : "Load: " + type.label();
            }

            @Override
            public CargoType fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        box.setValue(cargoChoice);
        box.setOnAction(event -> {
            cargoChoice = box.getValue();
            applyCargo();
        });
        cargoSelector = box;
        return box;
    }

    /**
     * Both views show the same load, so switching 2D/3D never changes the hook.
     * The selector is re-pointed too: the demo puts a container on the hook, and
     * a panel still reading "Load: None" next to a visible container is the kind
     * of detail a buyer notices.
     */
    private void applyCargo() {
        view2d.setCargo(cargoChoice);
        view3d.setCargo(cargoChoice);
        if (cargoSelector != null && cargoSelector.getValue() != cargoChoice) {
            cargoSelector.setValue(cargoChoice);
        }
    }

    /**
     * New/Edit buttons for crane profiles. Building a customer's own machine in
     * front of them, then driving it, is the demo that closes the sale.
     */
    private HBox buildProfileEditorButtons() {
        Button newProfile = new Button("New crane");
        newProfile.setFocusTraversable(false);
        newProfile.setMaxWidth(Double.MAX_VALUE);
        newProfile.setTooltip(new Tooltip(
                "Describe a crane by its axes and limits; it is saved as a JSON file "
                        + "and can be driven immediately."));
        newProfile.setOnAction(event -> openProfileEditor(null));

        Button editProfile = new Button("Edit");
        editProfile.setFocusTraversable(false);
        editProfile.setMaxWidth(Double.MAX_VALUE);
        editProfile.setTooltip(new Tooltip("Edit a copy of the crane now selected."));
        editProfile.setOnAction(event -> openProfileEditor(profile));

        HBox.setHgrow(newProfile, Priority.ALWAYS);
        HBox.setHgrow(editProfile, Priority.ALWAYS);
        return new HBox(6, newProfile, editProfile);
    }

    private void openProfileEditor(CraneProfile template) {
        String stylesheet = getClass().getResource("/hmi.css").toExternalForm();
        new ProfileEditorDialog(template, AppPaths.profiles(), stylesheet)
                .showAndSave()
                .ifPresent(file -> {
                    recordEvent("Saved crane profile " + file.getFileName());
                    // Select by the id inside the file, not by the file name. The
                    // writer sanitises the id into the name, so a crane called
                    // "my crane" was written as my_crane.json and then looked up
                    // under an id that does not exist — silently no selection.
                    reloadCatalogAndSelect(file);
                });
    }

    /**
     * Re-reads the profiles folder and switches to the named crane, so an edit is
     * driveable immediately without restarting.
     */
    private void reloadCatalogAndSelect(Path savedFile) {
        String profileId;
        try {
            profileId = new com.vukotic.crane.core.model.CraneProfileLoader()
                    .load(savedFile).id();
        } catch (RuntimeException e) {
            recordEvent("Saved profile could not be re-read: " + e.getMessage());
            profileId = profile.id();
        }
        catalog.clear();
        catalog.addAll(ProfileCatalog.available());
        String wanted = profileId;
        catalog.stream()
                .filter(candidate -> candidate.id().equals(wanted))
                .findFirst()
                .ifPresentOrElse(this::activateProfile, () -> activateProfile(profile));
    }

    private ComboBox<CraneProfile> buildProfileSelector() {
        ComboBox<CraneProfile> box = new ComboBox<>(FXCollections.observableArrayList(catalog));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFocusTraversable(false);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(CraneProfile p) {
                return p == null ? "" : p.name();
            }

            @Override
            public CraneProfile fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        box.setValue(profile);
        // Wired after setValue so the initial selection cannot re-trigger a rebuild.
        box.setOnAction(event -> {
            CraneProfile selected = box.getValue();
            if (selected != null && !selected.id().equals(profile.id())) {
                activateProfile(selected);
            }
        });
        return box;
    }

    private ListView<String> alarmListView(ObservableList<String> items, String textColor) {
        ListView<String> list = new ListView<>(items);
        list.setFocusTraversable(false);
        list.setStyle("-fx-background-color: " + BG + "; -fx-control-inner-background: " + BG + ";"
                + " -fx-control-inner-background-alt: " + BG + "; -fx-background-radius: 4;");
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setStyle("-fx-background-color: transparent; -fx-text-fill: " + textColor + ";"
                        + " -fx-font-size: 12px;");
            }
        });
        return list;
    }

    // ---- replay ----

    private TelemetryCsvReader.Recording recording;
    private long replayStartedMillis;
    private ToggleButton replayButton;
    /**
     * Volatile, and read by the command thread: while the screen is showing a
     * recording it must be impossible to drive the real back-end, or an operator
     * would be moving a machine they cannot see.
     */
    private volatile boolean replaying;

    /**
     * Plays a recorded run back through the views. Nothing is simulated: the
     * frames come straight from the CSV, so a laptop with no crane and no
     * simulator can show exactly what a machine did on site.
     */
    private ToggleButton buildReplayButton() {
        replayButton = new ToggleButton("REPLAY A RECORDING");
        replayButton.setFocusTraversable(false);
        replayButton.setMaxWidth(Double.MAX_VALUE);
        replayButton.setTooltip(new Tooltip(
                "Load a telemetry CSV and play it back. The crane is not simulated "
                        + "during replay - the frames are the recording."));
        replayButton.selectedProperty().addListener((obs, was, selected) -> {
            if (!selected) {
                stopReplay();
            } else if (!replaying) {
                // Guarded: beginReplay() selects the button itself, and that must
                // not bounce back into here and re-open the file chooser.
                startReplay();
            }
        });
        return replayButton;
    }

    private void startReplay() {
        if (demoRunning) {
            demoButton.setSelected(false);   // one thing at a time on the screen
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Open a telemetry recording");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("Telemetry CSV", "*.csv"));
        Path defaultDir = AppPaths.telemetry();
        if (java.nio.file.Files.isDirectory(defaultDir)) {
            chooser.setInitialDirectory(defaultDir.toFile());
        }
        java.io.File file = chooser.showOpenDialog(stage);
        if (file == null) {
            replayButton.setSelected(false);
            return;
        }
        beginReplay(file.toPath());
    }

    /**
     * Starts playing {@code file}. Also the entry point for a recording passed on
     * the command line, so a CSV mailed to a customer can be opened straight into
     * the cockpit without them owning a crane.
     */
    private void beginReplay(Path file) {
        try {
            recording = new TelemetryCsvReader().read(file);
            if (recording.isEmpty()) {
                recordEvent("Recording " + file.getFileName() + " has no usable frames");
                recording = null;
                replayButton.setSelected(false);
                return;
            }
            // Stop commanding anything while a recording drives the screen.
            operatorInput.releaseAllKeys();
            drivingKeys.clear();
            replaying = true;
            replayStartedMillis = MonotonicClock.millis();
            replayButton.setSelected(true);
            replayButton.setText("STOP REPLAY");
            dismissWelcome();
            showCaption("Replaying " + file.getFileName() + " ("
                    + (recording.durationMillis() / 1000) + " s of recorded motion)");
        } catch (IOException e) {
            recordEvent("Could not read recording: " + e.getMessage());
            recording = null;
            replayButton.setSelected(false);
        }
    }

    private void stopReplay() {
        recording = null;
        // Neutralise BEFORE re-arming. Key handlers stayed live during playback, so
        // an operator holding SPACE and an axis while watching a recording had that
        // command go live on the first tick after it ended — motion they never
        // asked the live machine for.
        if (operatorInput != null) {
            operatorInput.releaseAllKeys();
        }
        drivingKeys.clear();
        replaying = false;
        replayButton.setText("REPLAY A RECORDING");
        replayButton.setSelected(false);
        demoCaption.setVisible(false);
    }

    /** The state to draw this frame: a recorded one while replaying, else live. */
    private CraneState displayState() {
        TelemetryCsvReader.Recording active = recording;
        if (active == null) {
            return backend.latestState();
        }
        long elapsed = MonotonicClock.millis() - replayStartedMillis;
        if (elapsed > active.durationMillis() + 1_000) {
            stopReplay();
            return backend.latestState();
        }
        return active.frameAt(elapsed);
    }

    // ---- telemetry ----

    private void startTelemetry() {
        // Sanitised the same way profile files are: a crane id is operator-supplied
        // text, and it was going into a path unescaped, outside the try block that
        // would have caught the resulting failure.
        String safeId = profile.id().replaceAll("[^A-Za-z0-9._-]", "_");
        Path file = AppPaths.telemetry().resolve("telemetry-%s-%s.csv"
                .formatted(safeId.isBlank() ? "crane" : safeId,
                        LocalDateTime.now().format(FILE_STAMP)));
        try {
            telemetryLogger = new TelemetryCsvLogger(file, profile);
            backend.addStateListener(telemetryLogger);
            recordInfo.setText("recording → " + file);
        } catch (IOException e) {
            telemetryLogger = null;
            recordInfo.setText("cannot record: " + e.getMessage());
            recordButton.setSelected(false);
        }
    }

    private void stopTelemetry() {
        if (telemetryLogger == null) {
            return;
        }
        backend.removeStateListener(telemetryLogger);
        try {
            telemetryLogger.close();
        } catch (IOException e) {
            System.err.println("[telemetry] close failed: " + e.getMessage());
        }
        telemetryLogger = null;
        if (recordInfo != null) {
            recordInfo.setText("not recording");
        }
        if (recordButton != null && recordButton.isSelected()) {
            recordButton.setSelected(false); // re-entry is a no-op: logger is already null
        }
    }

    // ---- input wiring ----

    private void installKeyHandlers(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F11) {
                stage.setFullScreen(!stage.isFullScreen());
                event.consume();
                return;
            }
            if (welcomeOverlay != null) {
                event.consume();   // no hidden motion behind the welcome card
                return;
            }
            if (isDrivingKey(event.getCode())) {
                drivingKeys.add(event.getCode());
                event.consume();
                return;
            }
            String key = event.getCode().name();
            if (keyBindings.isEstopKey(key)) {
                estopButton.setSelected(true); // listener forwards to operatorInput
                event.consume();
            } else if (keyBindings.isBound(key)) {
                operatorInput.keyPressed(key);
                event.consume();
            }
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (isDrivingKey(event.getCode())) {
                drivingKeys.remove(event.getCode());
                event.consume();
                return;
            }
            String key = event.getCode().name();
            if (keyBindings.isBound(key)) {
                operatorInput.keyReleased(key);
                event.consume();
            }
        });
    }

    private static boolean isDrivingKey(KeyCode code) {
        return code == KeyCode.UP || code == KeyCode.DOWN
                || code == KeyCode.LEFT || code == KeyCode.RIGHT;
    }

    /** Feeds the arrow keys to the truck; only has an effect in driver mode. */
    private void updateDriving() {
        double throttle = (drivingKeys.contains(KeyCode.UP) ? 1 : 0)
                - (drivingKeys.contains(KeyCode.DOWN) ? 1 : 0);
        double steer = (drivingKeys.contains(KeyCode.RIGHT) ? 1 : 0)
                - (drivingKeys.contains(KeyCode.LEFT) ? 1 : 0);
        view3d.setDriveInput(throttle, steer);

        if (driverInfo != null) {
            driverInfo.setText(driverMode
                    ? String.format("driving · %.0f km/h · arrow keys", view3d.truckSpeedKmh())
                    : "crane active · truck parked");
        }
        if (releaseButton != null) {
            releaseButton.setDisable(!view3d.isCargoAttached());
        }
    }

    // ---- per-frame refresh ----

    private void updateReadouts(CraneCommand command, CraneState state) {
        for (AxisSpec axis : profile.axes()) {
            demandReadouts.get(axis.id()).setText(String.format("%+.2f", command.demand(axis.id())));
            double position = state.position(axis.id());
            positionReadouts.get(axis.id())
                    .setText(String.format("%.2f %s", position, axis.unit()));

            ProgressBar bar = positionBars.get(axis.id());
            if (bar != null) {
                double span = axis.maxPosition() - axis.minPosition();
                bar.setProgress(Math.clamp((position - axis.minPosition()) / span, 0.0, 1.0));
            }
        }
        if (slewDial != null) {
            profile.axisById("slew")
                    .ifPresent(axis -> drawSlewDial(axis, state.position("slew")));
        }
        estopLamp.setFill(state.estopLatched() ? Color.web(ALARM_RED) : LAMP_OFF);
        deadmanLamp.setFill(state.deadmanHeld() ? Color.web(OK_GREEN) : LAMP_OFF);
        watchdogLamp.setFill(state.watchdogTripped() ? Color.web(ALARM_RED) : LAMP_OFF);
        deadmanIndicator.setStyle(deadmanStyle(operatorInput.deadmanHeld()));
        deadmanIndicator.setText(operatorInput.deadmanHeld() ? "RUN ENABLED" : "HOLD SPACE TO RUN");

        if (!alarmItems.equals(state.activeAlarms())) {
            alarmItems.setAll(state.activeAlarms());
        }
        recordAlarmHistory(state);
        updateFoldStatus();
        updateStatusPill(state);
    }

    /**
     * Radial slew gauge: the axis range drawn as an arc with 0° at the top and
     * positive angles clockwise (same convention as the 2D top view), an amber
     * needle at the current angle, and end stops marked in red.
     */
    private void drawSlewDial(AxisSpec axis, double angleDeg) {
        GraphicsContext g = slewDial.getGraphicsContext2D();
        double w = slewDial.getWidth();
        double h = slewDial.getHeight();
        double cx = w / 2;
        double cy = h / 2 + 6;
        double radius = Math.min(w, h * 1.6) / 2 - 10;

        g.clearRect(0, 0, w, h);

        // JavaFX arcs: 0° = east, counter-clockwise. Our 0° = north, clockwise.
        double startFx = 90 - axis.minPosition();
        double extentFx = -(axis.maxPosition() - axis.minPosition());
        g.setStroke(Color.web("#39434c"));
        g.setLineWidth(6);
        g.strokeArc(cx - radius, cy - radius, radius * 2, radius * 2,
                startFx, extentFx, ArcType.OPEN);

        g.setStroke(Color.web(ALARM_RED));
        g.setLineWidth(2);
        for (double stop : new double[]{axis.minPosition(), axis.maxPosition()}) {
            double rad = Math.toRadians(90 - stop);
            g.strokeLine(cx + Math.cos(rad) * (radius - 6), cy - Math.sin(rad) * (radius - 6),
                    cx + Math.cos(rad) * (radius + 4), cy - Math.sin(rad) * (radius + 4));
        }

        g.setStroke(Color.web("#566470"));
        g.setLineWidth(1.5);
        g.strokeLine(cx, cy - radius - 4, cx, cy - radius + 3); // 0° reference tick

        double needleRad = Math.toRadians(90 - Math.clamp(angleDeg,
                axis.minPosition(), axis.maxPosition()));
        g.setStroke(Color.web(AMBER));
        g.setLineWidth(3);
        g.strokeLine(cx, cy,
                cx + Math.cos(needleRad) * (radius - 3), cy - Math.sin(needleRad) * (radius - 3));
        g.setFill(Color.web(AMBER));
        g.fillOval(cx - 3.5, cy - 3.5, 7, 7);
    }

    /** Keeps the FOLD button and its status line in sync with the sequencer. */
    private void updateFoldStatus() {
        if (foldButton == null) {
            return;
        }
        if (foldSequencer.isActive()) {
            String axis = foldSequencer.activeAxis();
            foldStatus.setText(operatorInput.deadmanHeld()
                    ? "auto-fold: driving '" + axis + "'"
                    : "auto-fold armed: hold SPACE to run ('" + axis + "')");
        } else {
            if (foldButton.isSelected()) {
                foldButton.setSelected(false); // finished or cancelled by input/E-STOP
            }
            foldStatus.setText(foldSequencer.isComplete()
                    ? "auto-fold: transport pose reached"
                    : "auto-fold idle");
        }
    }

    /** Prepends newly raised alarms (with a time stamp) to the history, capped. */
    private void recordAlarmHistory(CraneState state) {
        for (String alarm : state.activeAlarms()) {
            if (!previousAlarms.contains(alarm)) {
                alarmHistory.add(0, LocalTime.now().format(ALARM_STAMP) + "  " + alarm);
            }
        }
        if (alarmHistory.size() > ALARM_HISTORY_LIMIT) {
            alarmHistory.remove(ALARM_HISTORY_LIMIT, alarmHistory.size());
        }
        previousAlarms = Set.copyOf(state.activeAlarms());
    }

    // ---- style helpers ----

    private static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: " + AMBER + "; -fx-font-size: 12px; -fx-font-weight: bold;");
        label.setPadding(new Insets(6, 0, 0, 0));
        return label;
    }

    private static HBox infoRow(String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 12px;");
        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-text-fill: " + TEXT + "; -fx-font-size: 12px;");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(6, keyLabel, gap, valueLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static HBox lampRow(Circle lamp, String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: " + TEXT + "; -fx-font-size: 12px;");
        HBox row = new HBox(8, lamp, label);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String readoutStyle(String color) {
        return "-fx-text-fill: " + color + "; -fx-font-family: 'Consolas', monospace;"
                + " -fx-font-size: 12px;";
    }

    private static String estopStyle(boolean latched) {
        String background = latched ? "#8f2320" : ALARM_RED;
        String border = latched ? " -fx-border-color: white; -fx-border-width: 2;"
                + " -fx-border-radius: 8;" : "";
        return "-fx-background-color: " + background + "; -fx-text-fill: white;"
                + " -fx-font-size: 26px; -fx-font-weight: bold; -fx-background-radius: 8;" + border;
    }

    private static String deadmanStyle(boolean held) {
        if (held) {
            return "-fx-background-color: " + OK_GREEN + "; -fx-text-fill: #10241a;"
                    + " -fx-font-weight: bold; -fx-background-radius: 6; -fx-font-size: 13px;";
        }
        return "-fx-background-color: " + BG + "; -fx-text-fill: " + TEXT_DIM + ";"
                + " -fx-background-radius: 6; -fx-font-size: 13px;";
    }

    private static String recStyle(boolean recording) {
        String dot = recording ? ALARM_RED : TEXT_DIM;
        return "-fx-background-color: " + BG + "; -fx-text-fill: " + dot + ";"
                + " -fx-border-color: " + dot + "; -fx-border-radius: 6; -fx-background-radius: 6;"
                + " -fx-font-weight: bold;";
    }

    public static void main(String[] args) {
        launch(args);
    }
}


