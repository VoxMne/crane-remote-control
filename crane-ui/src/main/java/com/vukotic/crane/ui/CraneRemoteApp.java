package com.vukotic.crane.ui;

import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import com.vukotic.crane.core.model.CraneState;
import com.vukotic.crane.sim.SimulatedCraneDriver;
import com.vukotic.crane.ui.backend.ControlLoopBackend;
import com.vukotic.crane.ui.backend.CraneBackend;
import com.vukotic.crane.ui.input.KeyBindings;
import com.vukotic.crane.ui.input.OperatorInput;
import com.vukotic.crane.ui.render.CraneRenderer;
import com.vukotic.crane.ui.render.SchematicRenderer2D;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Operator HMI (M2a): dark industrial shell with a per-axis control panel,
 * schematic visualization canvas, and status panel.
 *
 * <p>Every frame, an {@link AnimationTimer} snapshots {@link OperatorInput}
 * into a {@link CraneCommand} and pushes it into {@code commandSink}; the
 * latest {@link CraneState} is polled from the {@link CraneBackend} and
 * rendered. The backend is the single integration seam: today it is the
 * throwaway {@link StubCraneBackend}, at M2b it becomes an adapter over the
 * real ControlLoop without any other UI change.
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

    private final CraneProfile profile = CraneProfiles.demoKnuckleBoom();
    private final KeyBindings keyBindings = KeyBindings.defaults();
    private final OperatorInput operatorInput = new OperatorInput(keyBindings, profile.axisIds());

    /** The ONLY place that decides what runs the crane (see class Javadoc). */
    private final ControlLoopBackend controlBackend =
            new ControlLoopBackend(profile, new SimulatedCraneDriver());
    private final CraneBackend backend = controlBackend;
    private final Consumer<CraneCommand> commandSink = backend::submitCommand;

    private final CraneRenderer renderer = new SchematicRenderer2D();

    private final Map<String, Label> demandReadouts = new HashMap<>();
    private final Map<String, Label> positionReadouts = new HashMap<>();
    private final ObservableList<String> alarmItems = FXCollections.observableArrayList();
    private ToggleButton estopButton;
    private Label deadmanIndicator;
    private Circle estopLamp;
    private Circle deadmanLamp;
    private Circle watchdogLamp;
    private Canvas canvas;
    private AnimationTimer frameTimer;

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG + ";");
        root.setLeft(buildControlPanel());
        root.setCenter(buildCanvasPane());
        root.setRight(buildStatusPanel());
        BorderPane.setMargin(root.getLeft(), new Insets(10, 0, 10, 10));
        BorderPane.setMargin(root.getCenter(), new Insets(10));
        BorderPane.setMargin(root.getRight(), new Insets(10, 10, 10, 0));

        Scene scene = new Scene(root, 1280, 800);
        installKeyHandlers(scene);

        stage.setTitle("Crane Remote Control");
        stage.setMinWidth(1000);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.show();

        controlBackend.start();
        frameTimer = new AnimationTimer() {
            @Override
            public void handle(long frameNanos) {
                CraneCommand command = operatorInput.snapshot(System.currentTimeMillis());
                commandSink.accept(command);
                CraneState state = backend.latestState();
                updateReadouts(command, state);
                renderer.render(canvas.getGraphicsContext2D(),
                        canvas.getWidth(), canvas.getHeight(), profile, state);
            }
        };
        frameTimer.start();
    }

    @Override
    public void stop() {
        if (frameTimer != null) {
            frameTimer.stop();
        }
        controlBackend.stop();
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

    // ---- center: visualization ----

    private Pane buildCanvasPane() {
        Pane holder = new Pane();
        holder.setStyle("-fx-background-color: " + BG + ";");
        canvas = new Canvas();
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        holder.getChildren().add(canvas);
        return holder;
    }

    // ---- right: status ----

    private VBox buildStatusPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(14));
        panel.setPrefWidth(320);
        panel.setStyle("-fx-background-color: " + PANEL_BG + "; -fx-background-radius: 6;");

        panel.getChildren().add(sectionLabel("STATUS"));
        panel.getChildren().add(infoRow("PROFILE", profile.name()));
        panel.getChildren().add(infoRow("DRIVER", controlBackend.driverName()));

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

        panel.getChildren().add(sectionLabel("ALARMS"));
        ListView<String> alarmList = new ListView<>(alarmItems);
        alarmList.setFocusTraversable(false);
        alarmList.setStyle("-fx-background-color: " + BG + "; -fx-control-inner-background: " + BG + ";"
                + " -fx-control-inner-background-alt: " + BG + "; -fx-background-radius: 4;");
        alarmList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setStyle("-fx-background-color: transparent; -fx-text-fill: " + ALARM_RED + ";"
                        + " -fx-font-size: 12px;");
            }
        });
        VBox.setVgrow(alarmList, Priority.ALWAYS);
        panel.getChildren().add(alarmList);
        return panel;
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

    public static void main(String[] args) {
        launch(args);
    }
}
