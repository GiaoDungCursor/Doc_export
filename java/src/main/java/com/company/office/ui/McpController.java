package com.company.office.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.List;
import java.util.Map;

public class McpController {
    @FXML private ListView<String> toolsListView;
    @FXML private Label toolDescLabel;
    @FXML private TextArea toolArgsArea;
    @FXML private TextArea toolResponseArea;
    @FXML private Button runToolButton;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private List<Map<String, Object>> tools;

    @FXML
    public void initialize() {
        AppContext ctx = AppContext.getInstance();
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
