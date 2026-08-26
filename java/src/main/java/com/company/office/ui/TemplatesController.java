package com.company.office.ui;

import com.company.office.model.TemplateEntity;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.beans.property.SimpleStringProperty;
import javafx.stage.FileChooser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.time.Instant;

public class TemplatesController {
    private final ObjectMapper objectMapper = new ObjectMapper();
    @FXML private TableView<TemplateEntity> templatesTable;
    @FXML private TableColumn<TemplateEntity, String> colId;
    @FXML private TableColumn<TemplateEntity, String> colName;
    @FXML private TableColumn<TemplateEntity, String> colType;
    @FXML private TableColumn<TemplateEntity, String> colPath;

    @FXML private TextArea schemaJsonArea;
    @FXML private TableView<MappingRow> mappingTable;
    @FXML private TableColumn<MappingRow, String> colTarget;
    @FXML private TableColumn<MappingRow, String> colSource;
    @FXML private TableColumn<MappingRow, String> colOrigin;
    @FXML private TableColumn<MappingRow, String> colConfidence;
    @FXML private Label statusLabel;

    @FXML
    public void initialize() {
        colId.setCellValueFactory(new PropertyValueFactory<>("id"));
        colName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colType.setCellValueFactory(new PropertyValueFactory<>("templateType"));
        colPath.setCellValueFactory(new PropertyValueFactory<>("filePath"));
        colTarget.setCellValueFactory(v -> v.getValue().targetProperty());
        colSource.setCellValueFactory(v -> v.getValue().sourceProperty());
        colOrigin.setCellValueFactory(v -> v.getValue().originProperty());
        colConfidence.setCellValueFactory(v -> v.getValue().confidenceProperty());
        colSource.setCellFactory(TextFieldTableCell.forTableColumn());
        colSource.setOnEditCommit(e -> e.getRowValue().setSource(e.getNewValue()));

        templatesTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                var schema = AppContext.getInstance().getTemplateSchemaService().ensureVersionedSchema(newVal);
                schemaJsonArea.setText(schema.getSchemaJson() != null ? schema.getSchemaJson() : "{}");
                loadMappings(schema.getSchemaJson());
                statusLabel.setText("Schema v" + schema.getVersion() + " • " + schema.getStatus());
            } else {
                schemaJsonArea.clear();
            }
        });

        refreshTemplates();
    }

    public void refreshTemplates() {
        AppContext ctx = AppContext.getInstance();
        List<TemplateEntity> list = ctx.getTemplateService().listTemplates();
        templatesTable.setItems(FXCollections.observableArrayList(list));
    }

    @FXML
    public void onImportTemplate() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn mẫu biểu Excel hoặc Word");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Office Templates", "*.xlsx", "*.docx", "*.xlsm", "*.doc"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = fileChooser.showOpenDialog(templatesTable.getScene().getWindow());
        if (file != null) {
            statusLabel.setText("Importing & parsing template placeholders...");
            try {
                AppContext.getInstance().getTemplateService().importTemplate(file, file.getName(), "Custom Template")
                        .thenAccept(tpl -> Platform.runLater(() -> {
                            statusLabel.setText("Template imported: " + tpl.getName());
                            refreshTemplates();
                        }))
                        .exceptionally(ex -> {
                            Platform.runLater(() -> statusLabel.setText("Template import error: " + ex.getMessage()));
                            return null;
                        });
            } catch (Exception e) {
                statusLabel.setText("Error: " + e.getMessage());
            }
        }
    }

    @FXML
    public void onDeleteTemplate() {
        TemplateEntity selected = templatesTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            AppContext.getInstance().getTemplateService().deleteTemplate(selected.getId());
            refreshTemplates();
            statusLabel.setText("Deleted template: " + selected.getId());
        }
    }

    @FXML
    public void onApproveSchema() {
        TemplateEntity selected = templatesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("Vui lòng chọn một template.");
            return;
        }
        try {
            Map<String, Object> schema = objectMapper.readValue(
                    schemaJsonArea.getText(), new TypeReference<Map<String, Object>>() {});
            Map<String, String> mappings = new java.util.LinkedHashMap<>();
            for (MappingRow row : mappingTable.getItems()) {
                if (row.getSource() != null && !row.getSource().isBlank()) mappings.put(row.getTarget(), row.getSource());
            }
            schema.put("mappings", mappings);
            schema.put("unmapped", mappingTable.getItems().stream()
                    .filter(r -> r.getSource() == null || r.getSource().isBlank()).map(MappingRow::getTarget).toList());
            schema.put("approved", true);
            schema.put("status", "approved");
            schema.put("approved_at", Instant.now().toString());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
            selected.setSchemaJson(json); // compatibility mirror during migration
            AppContext.getInstance().getTemplateService().updateTemplate(selected);
            var approved = AppContext.getInstance().getTemplateSchemaService().approve(selected, json);
            schemaJsonArea.setText(json);
            statusLabel.setText("Đã phê duyệt schema v" + approved.getVersion() + ": " + selected.getName());
        } catch (Exception e) {
            statusLabel.setText("Schema JSON không hợp lệ: " + e.getMessage());
        }
    }

    private void loadMappings(String json) {
        mappingTable.getItems().clear();
        if (json == null || json.isBlank()) return;
        try {
            Map<String, Object> schema = objectMapper.readValue(json, new TypeReference<>() {});
            Map<?, ?> mappings = schema.get("mappings") instanceof Map<?, ?> m ? m : Map.of();
            List<?> placeholders = schema.get("placeholders") instanceof List<?> p ? p : List.of();
            for (Object placeholder : placeholders) {
                String target = String.valueOf(placeholder);
                Object source = mappings.get(target);
                mappingTable.getItems().add(new MappingRow(target, source == null ? "" : String.valueOf(source),
                        source == null ? "CHƯA MAP" : "AUTO/RULE", source == null ? "0%" : "100%"));
            }
        } catch (Exception e) { statusLabel.setText("Không đọc được mappings: " + e.getMessage()); }
    }

    public static class MappingRow {
        private final SimpleStringProperty target, source, origin, confidence;
        MappingRow(String target, String source, String origin, String confidence) {
            this.target = new SimpleStringProperty(target); this.source = new SimpleStringProperty(source);
            this.origin = new SimpleStringProperty(origin); this.confidence = new SimpleStringProperty(confidence);
        }
        public String getTarget() { return target.get(); }
        public String getSource() { return source.get(); }
        public void setSource(String value) { source.set(value); }
        public SimpleStringProperty targetProperty() { return target; }
        public SimpleStringProperty sourceProperty() { return source; }
        public SimpleStringProperty originProperty() { return origin; }
        public SimpleStringProperty confidenceProperty() { return confidence; }
    }
}
