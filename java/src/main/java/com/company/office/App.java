package com.company.office;

import com.company.office.ui.AppContext;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class App extends Application {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    @Override
    public void start(Stage primaryStage) {
        try {
            logger.info("Initializing Office Automation Desktop App...");
            exposeUninstallUtilities();
            AppContext appContext = AppContext.getInstance();

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

            CompletableFuture.runAsync(appContext::init).exceptionally(error -> {
                logger.error("Background services failed to start", error);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Không thể khởi động OCR");
                    alert.setHeaderText("Dịch vụ OCR cục bộ chưa khởi động được");
                    alert.setContentText("Ứng dụng vẫn có thể mở, nhưng chức năng bóc tách chưa sẵn sàng. "
                            + "Vui lòng khởi động lại ứng dụng hoặc cài lại phiên bản mới nhất.");
                    alert.show();
                });
                return null;
            });
        } catch (Exception e) {
            logger.error("Failed to start application", e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    private void exposeUninstallUtilities() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null || appPath.isBlank()) return;

        try {
            Path installRoot = Path.of(appPath).toAbsolutePath().getParent();
            if (installRoot == null) return;
            Path packagedApp = installRoot.resolve("app");
            Files.copy(packagedApp.resolve("fast-uninstall.bat"),
                    installRoot.resolve("Go-cai-dat-nhanh.bat"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(packagedApp.resolve("uninstall-app.ps1"),
                    installRoot.resolve("uninstall-app.ps1"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.warn("Could not expose uninstall utilities in the installation directory", e);
        }
    }
}
