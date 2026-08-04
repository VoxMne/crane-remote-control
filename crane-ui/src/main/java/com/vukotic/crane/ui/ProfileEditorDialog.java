package com.vukotic.crane.ui;

import com.vukotic.crane.core.model.AxisSpec;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfileWriter;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.converter.DoubleStringConverter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds or edits a crane as data: a name and a table of axes with their limits
 * and speeds. Saving writes the JSON a {@code profiles/} folder is loaded from,
 * so a manufacturer can see their own machine driving this software within a
 * minute — which is the most persuasive thing the product can do.
 *
 * <p>Validation happens where it already lives: the values are handed to
 * {@link AxisSpec} and {@link CraneProfile}, whose constructors reject inverted
 * limits, duplicate ids and non-positive speeds. The dialog reports whatever
 * they complain about rather than duplicating the rules.
 */
public final class ProfileEditorDialog {

    /** One editable row. Mutable on purpose: a TableView edits it in place. */
    public static final class AxisRow {
        private final SimpleStringProperty id;
        private final SimpleStringProperty label;
        private final SimpleStringProperty unit;
        private final SimpleDoubleProperty minPosition;
        private final SimpleDoubleProperty maxPosition;
        private final SimpleDoubleProperty maxVelocity;
        private final SimpleDoubleProperty rampRate;

        AxisRow(String id, String label, String unit,
                double minPosition, double maxPosition, double maxVelocity, double rampRate) {
            this.id = new SimpleStringProperty(id);
            this.label = new SimpleStringProperty(label);
            this.unit = new SimpleStringProperty(unit);
            this.minPosition = new SimpleDoubleProperty(minPosition);
            this.maxPosition = new SimpleDoubleProperty(maxPosition);
            this.maxVelocity = new SimpleDoubleProperty(maxVelocity);
            this.rampRate = new SimpleDoubleProperty(rampRate);
        }

        public SimpleStringProperty idProperty() {
            return id;
        }

        public SimpleStringProperty labelProperty() {
            return label;
        }

        public SimpleStringProperty unitProperty() {
            return unit;
        }

        public SimpleDoubleProperty minPositionProperty() {
            return minPosition;
        }

        public SimpleDoubleProperty maxPositionProperty() {
            return maxPosition;
        }

        public SimpleDoubleProperty maxVelocityProperty() {
            return maxVelocity;
        }

        public SimpleDoubleProperty rampRateProperty() {
            return rampRate;
        }

        AxisSpec toSpec() {
            return new AxisSpec(id.get().trim(), label.get().trim(), unit.get().trim(),
                    minPosition.get(), maxPosition.get(), maxVelocity.get(), rampRate.get());
        }

        static AxisRow from(AxisSpec spec) {
            return new AxisRow(spec.id(), spec.label(), spec.unit(),
                    spec.minPosition(), spec.maxPosition(),
                    spec.maxVelocity(), spec.commandRampRate());
        }
    }

    private final Dialog<CraneProfile> dialog = new Dialog<>();
    private final TextField idField = new TextField();
    private final TextField nameField = new TextField();
    private final ObservableList<AxisRow> rows = FXCollections.observableArrayList();
    private final Label error = new Label();
    private final Path profileDirectory;

    /**
     * @param template   profile to start from, or {@code null} for a new machine
     * @param directory  folder the JSON is written into
     * @param stylesheet the app stylesheet, so the dialog matches the cockpit
     */
    public ProfileEditorDialog(CraneProfile template, Path directory, String stylesheet) {
        this.profileDirectory = directory;

        if (template == null) {
            idField.setText("my-crane");
            nameField.setText("My Crane");
            rows.addAll(
                    new AxisRow("slew", "Slew (rotation)", "deg", -180, 180, 12, 2),
                    new AxisRow("boom", "Main boom", "deg", -5, 75, 8, 2),
                    new AxisRow("winch", "Winch (rope out)", "m", 0, 20, 1.0, 2));
        } else {
            idField.setText(template.id());
            nameField.setText(template.name());
            template.axes().forEach(axis -> rows.add(AxisRow.from(axis)));
        }

        dialog.setTitle(template == null ? "New crane profile" : "Edit crane profile");
        dialog.setHeaderText("A crane is data: describe its axes and it can be driven.");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.getDialogPane().setContent(buildContent());
        dialog.getDialogPane().setPrefSize(760, 460);
        if (stylesheet != null) {
            dialog.getDialogPane().getStylesheets().add(stylesheet);
        }

        // Keep the dialog open when the values do not make a valid machine.
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (buildProfile().isEmpty()) {
                event.consume();
            }
        });

        dialog.setResultConverter(button ->
                button == ButtonType.OK ? buildProfile().orElse(null) : null);
    }

    private VBox buildContent() {
        TableView<AxisRow> table = new TableView<>(rows);
        table.setEditable(true);
        table.getColumns().addAll(List.of(
                textColumn("Axis id", 110, AxisRow::idProperty),
                textColumn("Label", 170, AxisRow::labelProperty),
                textColumn("Unit", 60, AxisRow::unitProperty),
                numberColumn("Min", 80, AxisRow::minPositionProperty),
                numberColumn("Max", 80, AxisRow::maxPositionProperty),
                numberColumn("Max speed /s", 110, AxisRow::maxVelocityProperty),
                numberColumn("Ramp /s", 90, AxisRow::rampRateProperty)));
        VBox.setVgrow(table, Priority.ALWAYS);

        Button add = new Button("Add axis");
        add.setOnAction(event ->
                rows.add(new AxisRow("axis" + (rows.size() + 1), "New axis", "deg", 0, 90, 10, 2)));
        Button remove = new Button("Remove selected");
        remove.setOnAction(event -> {
            AxisRow selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                rows.remove(selected);
            }
        });

        error.getStyleClass().add("caption");
        wrapping(error);

        Label hint = new Label("""
                Axis ids slew, boom, jib, extension and winch are drawn by the \
                visualisation; any other id still drives and is shown on the panels. \
                Saved as JSON in %s.""".formatted(profileDirectory));
        hint.getStyleClass().add("caption");
        wrapping(hint);

        HBox idRow = new HBox(8, new Label("Id"), idField, new Label("Name"), nameField);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        HBox buttons = new HBox(8, add, remove);

        VBox content = new VBox(10, idRow, table, buttons, hint, error);
        content.setPadding(new Insets(12));
        return content;
    }

    /**
     * A wrapping label that is actually allowed to be two lines tall. Without the
     * min-height override the VBox sizes it from one line and the text is cut off
     * with an ellipsis instead of wrapping.
     */
    private static void wrapping(Label label) {
        label.setWrapText(true);
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
    }

    private TableColumn<AxisRow, String> textColumn(
            String title, double width, java.util.function.Function<AxisRow, SimpleStringProperty> getter) {
        TableColumn<AxisRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> getter.apply(data.getValue()));
        column.setCellFactory(TextFieldTableCell.forTableColumn());
        column.setPrefWidth(width);
        return column;
    }

    private TableColumn<AxisRow, Double> numberColumn(
            String title, double width, java.util.function.Function<AxisRow, SimpleDoubleProperty> getter) {
        TableColumn<AxisRow, Double> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> getter.apply(data.getValue()).asObject());
        column.setCellFactory(TextFieldTableCell.forTableColumn(new DoubleStringConverter()));
        column.setOnEditCommit(event -> {
            Double value = event.getNewValue();
            if (value != null && Double.isFinite(value)) {
                getter.apply(event.getRowValue()).set(value);
            }
        });
        column.setPrefWidth(width);
        return column;
    }

    /** Validates by construction; the message shown is the model's own complaint. */
    private Optional<CraneProfile> buildProfile() {
        try {
            List<AxisSpec> axes = new ArrayList<>();
            for (AxisRow row : rows) {
                axes.add(row.toSpec());
            }
            CraneProfile profile =
                    new CraneProfile(idField.getText().trim(), nameField.getText().trim(), axes);
            error.setText("");
            return Optional.of(profile);
        } catch (RuntimeException e) {
            error.setText("Cannot save: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Shows the dialog and, on OK, writes the profile.
     *
     * @return the file written, or empty if cancelled
     */
    public Optional<Path> showAndSave() {
        Optional<CraneProfile> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new CraneProfileWriter().write(result.get(), profileDirectory));
        } catch (IOException e) {
            System.err.println("[profiles] could not be written: " + e.getMessage());
            return Optional.empty();
        }
    }
}
