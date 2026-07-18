package com.vukotic.crane.ui;

import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.assist.AutoSequencer;
import com.vukotic.crane.core.model.CraneState;
import com.vukotic.crane.core.telemetry.TelemetryCsvLogger;
import com.vukotic.crane.sim.SimulatedCraneDriver;
import com.vukotic.crane.ui.backend.ControlLoopBackend;
import com.vukotic.crane.ui.input.KeyBindings;
import com.vukotic.crane.ui.input.OperatorInput;
import com.vukotic.crane.ui.render.Crane3DView;
import com.vukotic.crane.ui.render.CraneSceneView;
import com.vukotic.crane.ui.render.Schematic2DView;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
    private final List<CraneProfile> catalog = ProfileCatalog.available();

    // ---- per-profile session, rebuilt by activateProfile() ----
    private CraneProfile profile;
    private OperatorInput operatorInput;
    private ControlLoopBackend backend;

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

    // Assists: toggle choices survive profile switches; the sequencer never does.
    private final AutoSequencer foldSequencer = new AutoSequencer();
    private boolean smoothingOn;
    private boolean antiSwayOn;
    private ToggleButton foldButton;
    private Label foldStatus;

    @Override
    public void start(Stage stage) {
        root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG + ";");
        // The center view is built per profile in activateProfile().

        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(getClass().getResource("/hmi.css").toExternalForm());
        installKeyHandlers(scene);

        activateProfile(catalog.get(0));

        stage.setTitle("Crane Remote Control");
        stage.setMinWidth(1000);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.show();

        frameTimer = new AnimationTimer() {
            @Override
            public void handle(long frameNanos) {
                CraneCommand command = operatorInput.snapshot(System.currentTimeMillis());
                if (foldSequencer.isActive()) {
                    command = foldSequencer.next(backend.latestState(), command);
                }
                backend.submitCommand(command);
                CraneState state = backend.latestState();
                updateReadouts(command, state);
                activeView.update(profile, state);
            }
        };
        frameTimer.start();
    }

    @Override
    public void stop() {
        if (frameTimer != null) {
            frameTimer.stop();
        }
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
        if (backend != null) {
            backend.stop();
        }

        profile = newProfile;
        operatorInput = new OperatorInput(keyBindings, profile.axisIds());
        backend = new ControlLoopBackend(profile, new SimulatedCraneDriver());
        foldSequencer.cancel(); // never carry an auto-sequence across cranes
        backend.configureAssists(smoothingOn, antiSwayOn);

        demandReadouts.clear();
        positionReadouts.clear();
        alarmItems.clear();
        previousAlarms = Set.of();

        root.setLeft(buildControlPanel());
        root.setCenter(buildCenterPane());
        root.setRight(buildStatusPanel());
        BorderPane.setMargin(root.getLeft(), new Insets(10, 0, 10, 10));
        BorderPane.setMargin(root.getCenter(), new Insets(10));
        BorderPane.setMargin(root.getRight(), new Insets(10, 10, 10, 0));

        backend.start();
    }

    // ---- left: controls ----

    private VBox buildControlPanel() {
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(14));
        panel.setPrefWidth(300);
        panel.setStyle("-fx-background-color: " + PANEL_BG + "; -fx-background-radius: 6;");

        panel.getChildren().add(sectionLabel("CONTROLS"));
        for (AxisSpec axis : profile.axes()) {
            panel.getChildren().add(buildAxisControl(axis));
        }

        panel.getChildren().add(sectionLabel("ASSIST"));
        panel.getChildren().add(buildAssistControls());

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        estopButton = new ToggleButton("E-STOP");
        estopButton.setMinHeight(84);
        estopButton.setMaxWidth(Double.MAX_VALUE);
        estopButton.setFocusTraversable(false);
        estopButton.setStyle(estopStyle(false));
        estopButton.selectedProperty().addListener((obs, was, selected) -> {
            operatorInput.setEstopRequested(selected);
            estopButton.setStyle(estopStyle(selected));
        });

        Button resetButton = new Button("RESET");
        resetButton.setMaxWidth(Double.MAX_VALUE);
        resetButton.setMinHeight(36);
        resetButton.setFocusTraversable(false);
        resetButton.setStyle("-fx-background-color: " + PANEL_BG + "; -fx-text-fill: " + AMBER + ";"
                + " -fx-border-color: " + AMBER + "; -fx-border-radius: 6; -fx-background-radius: 6;"
                + " -fx-font-weight: bold;");
        resetButton.setOnAction(event -> {
            estopButton.setSelected(false);   // clears the E-STOP request
            operatorInput.requestReset();     // one-shot reset in the next command
        });

        deadmanIndicator = new Label("HOLD SPACE TO RUN");
        deadmanIndicator.setMaxWidth(Double.MAX_VALUE);
        deadmanIndicator.setAlignment(Pos.CENTER);
        deadmanIndicator.setMinHeight(34);
        deadmanIndicator.setStyle(deadmanStyle(false));

        panel.getChildren().addAll(spacer, estopButton, resetButton, deadmanIndicator);
        return panel;
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
        smoothToggle.setStyle(assistStyle(smoothingOn));
        antiSwayToggle.setStyle(assistStyle(antiSwayOn));
        smoothToggle.selectedProperty().addListener((obs, was, selected) -> {
            smoothingOn = selected;
            smoothToggle.setStyle(assistStyle(selected));
            backend.configureAssists(smoothingOn, antiSwayOn);
        });
        antiSwayToggle.selectedProperty().addListener((obs, was, selected) -> {
            antiSwayOn = selected;
            antiSwayToggle.setStyle(assistStyle(selected));
            backend.configureAssists(smoothingOn, antiSwayOn);
        });

        foldButton = new ToggleButton("FOLD TO TRANSPORT");
        foldButton.setMaxWidth(Double.MAX_VALUE);
        foldButton.setFocusTraversable(false);
        foldButton.setStyle(assistStyle(false));
        foldButton.selectedProperty().addListener((obs, was, selected) -> {
            foldButton.setStyle(assistStyle(selected));
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

    private static String assistStyle(boolean on) {
        String color = on ? AMBER : TEXT_DIM;
        return "-fx-background-color: " + BG + "; -fx-text-fill: " + color + ";"
                + " -fx-border-color: " + color + "; -fx-border-radius: 6;"
                + " -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 11px;";
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

        StackPane center = new StackPane(viewStack, toggleBar);
        StackPane.setAlignment(toggleBar, Pos.TOP_RIGHT);
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
        panel.setStyle("-fx-background-color: " + PANEL_BG + "; -fx-background-radius: 6;");

        panel.getChildren().add(sectionLabel("PROFILE"));
        panel.getChildren().add(buildProfileSelector());
        panel.getChildren().add(infoRow("DRIVER", backend.driverName()));

        panel.getChildren().add(sectionLabel("AXIS POSITIONS"));
        for (AxisSpec axis : profile.axes()) {
            Label value = new Label("--");
            value.setStyle(readoutStyle(TEXT));
            positionReadouts.put(axis.id(), value);

            Label name = new Label(axis.label());
            name.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 12px;");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox row = new HBox(6, name, gap, value);
            row.setAlignment(Pos.CENTER_LEFT);
            panel.getChildren().add(row);
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
        panel.getChildren().addAll(recordButton, recordInfo);

        panel.getChildren().add(sectionLabel("ACTIVE ALARMS"));
        ListView<String> alarmList = alarmListView(alarmItems, ALARM_RED);
        alarmList.setPrefHeight(90);
        panel.getChildren().add(alarmList);

        panel.getChildren().add(sectionLabel("ALARM HISTORY"));
        ListView<String> historyList = alarmListView(alarmHistory, TEXT_DIM);
        VBox.setVgrow(historyList, Priority.ALWAYS);
        panel.getChildren().add(historyList);
        return panel;
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

    // ---- telemetry ----

    private void startTelemetry() {
        Path file = Path.of("telemetry", "telemetry-%s-%s.csv"
                .formatted(profile.id(), LocalDateTime.now().format(FILE_STAMP)));
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
            String key = event.getCode().name();
            if (keyBindings.isBound(key)) {
                operatorInput.keyReleased(key);
                event.consume();
            }
        });
    }

    // ---- per-frame refresh ----

    private void updateReadouts(CraneCommand command, CraneState state) {
        for (AxisSpec axis : profile.axes()) {
            demandReadouts.get(axis.id()).setText(String.format("%+.2f", command.demand(axis.id())));
            positionReadouts.get(axis.id())
                    .setText(String.format("%.2f %s", state.position(axis.id()), axis.unit()));
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
