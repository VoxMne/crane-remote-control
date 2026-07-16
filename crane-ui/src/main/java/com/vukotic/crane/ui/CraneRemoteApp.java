package com.vukotic.crane.ui;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Application entry point. M0: placeholder window proving the toolchain; real HMI lands in M2. */
public final class CraneRemoteApp extends Application {

    @Override
    public void start(Stage stage) {
        CraneProfile demo = CraneProfiles.demoKnuckleBoom();

        Label title = new Label("CRANE REMOTE CONTROL");
        title.setStyle("-fx-text-fill: #e8b716; -fx-font-size: 30px; -fx-font-weight: bold;");

        Label subtitle = new Label("HMI skeleton (M0) — operator interface arrives in M2");
        subtitle.setStyle("-fx-text-fill: #9aa7b0; -fx-font-size: 14px;");

        Label profileInfo = new Label(
                "Loaded profile: %s (%d axes)".formatted(demo.name(), demo.axes().size()));
        profileInfo.setStyle("-fx-text-fill: #6f7d88; -fx-font-size: 12px;");

        VBox root = new VBox(12, title, subtitle, profileInfo);
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #14181d;");

        stage.setTitle("Crane Remote Control");
        stage.setScene(new Scene(root, 960, 560));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
