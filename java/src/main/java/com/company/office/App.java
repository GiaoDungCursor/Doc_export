package com.company.office;

import com.company.office.ui.AppContext;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App extends Application {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    @Override
    public void start(Stage primaryStage) {
        try {
            logger.info("Initializing Office Automation Desktop App...");
            AppContext.getInstance().init();

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
            Parent root = loader.load();

            Scene scene = new Scene(root, 1200, 780);
            if (getClass().getResource("/css/style.css") != null) {
                scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
            }

            // Set modern application icons
            try {
                if (getClass().getResourceAsStream("/icons/app-icon-16.png") != null) {
                    primaryStage.getIcons().addAll(
                            new Image(getClass().getResourceAsStream("/icons/app-icon-16.png")),
                            new Image(getClass().getResourceAsStream("/icons/app-icon-32.png")),
                            new Image(getClass().getResourceAsStream("/icons/app-icon-64.png")),
                            new Image(getClass().getResourceAsStream("/icons/app-icon-128.png")),
                            new Image(getClass().getResourceAsStream("/icons/app-icon-256.png")),
                            new Image(getClass().getResourceAsStream("/icons/app-icon.png"))
                    );
                }
            } catch (Exception e) {
                logger.warn("Could not load application icons: {}", e.getMessage());
            }

            primaryStage.setTitle("Office Studio AI - OCR & Bóc tách Văn bản Tự động");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(1000);
            primaryStage.setMinHeight(650);

            primaryStage.setOnCloseRequest(event -> {
                logger.info("Application closing, terminating sidecar process...");
                AppContext.getInstance().shutdown();
            });

            primaryStage.show();
            logger.info("Desktop App window displayed successfully.");
        } catch (Exception e) {
            logger.error("Failed to start application", e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
