package com.company.office.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class McpController {
    @FXML private ListView<String> toolsListView;
    @FXML private Label toolDescLabel;
    @FXML private TextArea toolArgsArea;
    @FXML private TextArea toolResponseArea;
    @FXML private Button runToolButton;
    @FXML private TextField mcpPortField;
    @FXML private Label mcpEndpointLabel;
    @FXML private Label httpMcpStatusLabel;
    @FXML private TextArea mcpConfigArea;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private List<Map<String, Object>> tools;

    @FXML
    public void initialize() {
        AppContext ctx = AppContext.getInstance();
        mcpPortField.setText(String.valueOf(ctx.getHttpMcpProcess().getPort()));
        refreshHttpMcpInfo();
        tools = ctx.getMcpService().getAvailableTools();

        List<String> names = tools.stream().map(t -> (String) t.get("name")).toList();
        toolsListView.setItems(FXCollections.observableArrayList(names));

        toolsListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                tools.stream().filter(t -> newVal.equals(t.get("name"))).findFirst().ifPresent(t -> {
                    toolDescLabel.setText((String) t.get("description"));
                    Map<?, ?> params = (Map<?, ?>) t.get("parameters");
                    try {
                        toolArgsArea.setText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(params));
                    } catch (Exception e) {
                        toolArgsArea.setText("{}");
                    }
                });
            }
        });

        if (!names.isEmpty()) {
            toolsListView.getSelectionModel().select(0);
        }
    }

    @FXML
    public void onApplyPort() {
        final int port;
        try {
            port = Integer.parseInt(mcpPortField.getText().trim());
            if (port < 1024 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            httpMcpStatusLabel.setText("Cổng phải từ 1024 đến 65535");
            httpMcpStatusLabel.setStyle("-fx-text-fill: #dc2626;");
            return;
        }
        httpMcpStatusLabel.setText("Đang chuyển sang cổng " + port + "...");
        CompletableFuture.runAsync(() -> {
            AppContext ctx = AppContext.getInstance();
            ctx.getHttpMcpProcess().restart(port);
            ctx.getSettingRepository().set("mcp.http.port", String.valueOf(port));
            updateAntigravityConfigs(port);
        }).thenRun(() -> Platform.runLater(this::refreshHttpMcpInfo))
          .exceptionally(error -> {
              Platform.runLater(() -> {
                  refreshHttpMcpInfo();
                  httpMcpStatusLabel.setText("Không thể dùng cổng " + port + ": " + rootMessage(error));
                  httpMcpStatusLabel.setStyle("-fx-text-fill: #dc2626;");
              });
              return null;
          });
    }

    @FXML
    public void onCheckHttpMcp() { refreshHttpMcpInfo(); }

    @FXML
    public void onCopyMcpConfig() {
        ClipboardContent content = new ClipboardContent();
        content.putString(mcpConfigArea.getText());
        Clipboard.getSystemClipboard().setContent(content);
        httpMcpStatusLabel.setText("Đã sao chép cấu hình");
    }

    private void refreshHttpMcpInfo() {
        AppContext ctx = AppContext.getInstance();
        int port = ctx.getHttpMcpProcess().getPort();
        String endpoint = ctx.getHttpMcpProcess().getMcpUrl();
        mcpPortField.setText(String.valueOf(port));
        mcpEndpointLabel.setText(endpoint);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("serverUrl", endpoint);
        entry.put("headers", Map.of());
        try {
            mcpConfigArea.setText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("mcpServers", Map.of("office-studio-ai", entry))));
        } catch (Exception ignored) { }
        boolean ready = ctx.getHttpMcpProcess().isHealthy();
        httpMcpStatusLabel.setText(ready ? "● Đang chạy" : "● Ngoại tuyến");
        httpMcpStatusLabel.setStyle(ready ? "-fx-text-fill: #059669; -fx-font-weight: bold;"
                : "-fx-text-fill: #dc2626; -fx-font-weight: bold;");
    }

    @SuppressWarnings("unchecked")
    private void updateAntigravityConfigs(int port) {
        String userHome = System.getProperty("user.home");
        List<Path> files = List.of(
                Path.of(userHome, ".gemini", "antigravity-ide", "mcp_config.json"),
                Path.of(userHome, ".gemini", "config", "mcp_config.json"));
        for (Path file : files) {
            try {
                Files.createDirectories(file.getParent());
                Map<String, Object> root = Files.isRegularFile(file)
                        ? objectMapper.readValue(Files.readString(file), new TypeReference<>() {})
                        : new LinkedHashMap<>();
                Object existing = root.get("mcpServers");
                Map<String, Object> servers = existing instanceof Map<?, ?>
                        ? (Map<String, Object>) existing : new LinkedHashMap<>();
                root.put("mcpServers", servers);
                servers.put("office-studio-ai", Map.of(
                        "serverUrl", "http://127.0.0.1:" + port + "/mcp", "headers", Map.of()));
                if (Files.isRegularFile(file)) {
                    Files.copy(file, Path.of(file + ".office-studio-backup"), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                        StandardCharsets.UTF_8);
            } catch (Exception error) {
                throw new RuntimeException("Không cập nhật được " + file + ": " + error.getMessage(), error);
            }
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage();
    }

    @FXML
    public void onRunTool() {
        String toolName = toolsListView.getSelectionModel().getSelectedItem();
        if (toolName == null) return;

        toolResponseArea.setText("Executing tool " + toolName + " via Application Layer...");
        runToolButton.setDisable(true);

        try {
            Map<String, Object> args = objectMapper.readValue(toolArgsArea.getText(), new TypeReference<Map<String, Object>>() {});
            AppContext.getInstance().getMcpService().callTool(toolName, args)
                    .thenAccept(res -> Platform.runLater(() -> {
                        try {
                            toolResponseArea.setText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(res));
                        } catch (Exception e) {
                            toolResponseArea.setText(res.toString());
                        }
                        runToolButton.setDisable(false);
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            toolResponseArea.setText("Error executing MCP tool: " + ex.getMessage());
                            runToolButton.setDisable(false);
                        });
                        return null;
                    });
        } catch (Exception e) {
            toolResponseArea.setText("Invalid JSON arguments format: " + e.getMessage());
            runToolButton.setDisable(false);
        }
    }
}
