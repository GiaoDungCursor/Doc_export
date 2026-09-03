package com.company.office.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainController {
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);

    @FXML private StackPane contentArea;
    @FXML private Label sidecarStatusLabel;
    @FXML private Label mcpStatusLabel;
    @FXML private Label dbStatusLabel;

    private final Map<String, Node> viewCache = new HashMap<>();

    @FXML
    public void initialize() {
        updateStatusBadges();
        showWorkbench();
    }

    private void updateStatusBadges() {
        AppContext ctx = AppContext.getInstance();
        boolean sidecarAlive = ctx.getSidecarProcess().isAlive();
        sidecarStatusLabel.setText(sidecarAlive ? "Sidecar: Online" : "Sidecar: Offline");
        sidecarStatusLabel.setStyle(sidecarAlive ? "-fx-text-fill: #10b981; -fx-font-weight: bold;" : "-fx-text-fill: #ef4444; -fx-font-weight: bold;");

        boolean mcpReady = ctx.getHttpMcpProcess().isHealthy();
        mcpStatusLabel.setText(mcpReady ? "MCP HTTP: 127.0.0.1:8765" : "MCP HTTP: Offline");
        mcpStatusLabel.setStyle(mcpReady ? "-fx-text-fill: #10b981; -fx-font-weight: bold;"
                : "-fx-text-fill: #ef4444; -fx-font-weight: bold;");

        dbStatusLabel.setText("SQLite: Connected");
        dbStatusLabel.setStyle("-fx-text-fill: #38bdf8; -fx-font-weight: bold;");
    }

    @FXML
    public void showWorkbench() {
        loadView("/fxml/workbench.fxml");
    }

    @FXML
    public void showDashboard() {
        loadView("/fxml/dashboard.fxml");
    }

    @FXML
    public void showDocuments() {
        loadView("/fxml/documents.fxml");
    }

    @FXML
    public void showTemplates() {
        loadView("/fxml/templates.fxml");
    }

    @FXML
    public void showJobs() {
        loadView("/fxml/jobs.fxml");
    }

    @FXML
    public void showMcp() {
        loadView("/fxml/mcp.fxml");
    }

    @FXML
    public void showGuide() {
        loadView("/fxml/guide.fxml");
    }

    private void loadView(String fxmlPath) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Node node = loader.load();
            contentArea.getChildren().clear();
            contentArea.getChildren().add(node);
        } catch (IOException e) {
            logger.error("Failed to load view: {}", fxmlPath, e);
        }
    }
}
