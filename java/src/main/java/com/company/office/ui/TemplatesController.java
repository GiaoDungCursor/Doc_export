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
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        fileChooser.setTitle("Bước 1/3 — Chọn template Word hoặc Excel");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Template được hỗ trợ", "*.docx", "*.xlsx", "*.xlsm")
        );

        File file = fileChooser.showOpenDialog(templatesTable.getScene().getWindow());
        if (file != null) {
            String defaultName = file.getName().replaceFirst("\\.[^.]+$", "").replace('_', ' ');
            TextInputDialog nameDialog = new TextInputDialog(defaultName);
            nameDialog.initOwner(templatesTable.getScene().getWindow());
            nameDialog.setTitle("Bước 2/3 — Đặt tên template");
            nameDialog.setHeaderText("Tên này sẽ hiển thị trong danh sách Mẫu xuất");
            nameDialog.setContentText("Tên template:");
            Optional<String> enteredName = nameDialog.showAndWait();
            if (enteredName.isEmpty() || enteredName.get().isBlank()) return;

            statusLabel.setText("Đang kiểm tra file và đọc placeholder…");
            try {
                AppContext.getInstance().getTemplateService().importTemplate(
                                file, enteredName.get().trim(), "Template tùy chỉnh do người dùng thêm")
                        .thenAccept(tpl -> Platform.runLater(() -> {
                            refreshTemplates();
                            TemplateEntity imported = templatesTable.getItems().stream()
                                    .filter(item -> item.getId().equals(tpl.getId())).findFirst().orElse(tpl);
                            templatesTable.getSelectionModel().select(imported);
                            templatesTable.scrollTo(imported);
                            int placeholderCount = mappingTable.getItems().size();
                            long unmapped = mappingTable.getItems().stream()
                                    .filter(row -> row.getSource() == null || row.getSource().isBlank()).count();
                            boolean autoAdapted = false;
                            int adaptationCount = 0;
                            try {
                                Map<String, Object> importedSchema = objectMapper.readValue(
                                        tpl.getSchemaJson(), new TypeReference<Map<String, Object>>() {});
                                autoAdapted = Boolean.TRUE.equals(importedSchema.get("auto_adapted"));
                                if (importedSchema.get("adaptations") instanceof List<?> changes) {
                                    adaptationCount = changes.size();
                                }
                            } catch (Exception ignored) {}
                            statusLabel.setText((autoAdapted ? "Đã tự chuyển biểu mẫu tĩnh • " : "Đã thêm • ")
                                    + "“" + tpl.getName() + "” • " + placeholderCount
                                    + " placeholder • " + unmapped + " chưa map");
                            Alert result = new Alert(placeholderCount > 0 && unmapped == 0
                                    ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
                            result.initOwner(templatesTable.getScene().getWindow());
                            result.setTitle("Bước 3/3 — Kiểm tra và duyệt");
                            result.setHeaderText(placeholderCount == 0
                                    ? "Không nhận diện được vùng dữ liệu tự động"
                                    : autoAdapted
                                    ? "Đã nhận diện và gắn tự động " + adaptationCount + " vùng dữ liệu"
                                    : unmapped == 0 ? "Template đã đọc thành công"
                                    : "Template còn placeholder chưa được mapping");
                            result.setContentText(placeholderCount == 0
                                    ? "File vẫn được lưu, nhưng hiện là mẫu tĩnh. Hãy thêm placeholder {{field_name}} vào Word/Excel rồi bấm Quét lại."
                                    : unmapped == 0
                                    ? "Kiểm tra lại bảng Mapping, sau đó nhấn “Lưu và duyệt schema” để sử dụng."
                                    : "Hãy nhập nguồn dữ liệu cho " + unmapped
                                      + " dòng trống trong cột “Nguồn dữ liệu”, rồi nhấn “Lưu và duyệt schema”.");
                            result.showAndWait();
                        }))
                        .exceptionally(ex -> {
                            Platform.runLater(() -> {
                                statusLabel.setText("Không thể thêm template: " + ex.getMessage());
                                Alert error = new Alert(Alert.AlertType.ERROR,
                                        "Không đọc được template. Hãy dùng file .docx/.xlsx hợp lệ và đóng file trong Office trước khi thử lại.",
                                        ButtonType.OK);
                                error.initOwner(templatesTable.getScene().getWindow());
                                error.setHeaderText("Thêm template thất bại");
                                error.showAndWait();
                            });
                            return null;
                        });
            } catch (Exception e) {
                statusLabel.setText("Không thể thêm template: " + e.getMessage());
            }
        }
    }

    @FXML
    public void onOpenTemplateFolder() {
        try {
            Path folder = AppContext.getInstance().getTemplateService().getCustomTemplatesDirectory();
            Files.createDirectories(folder);
            if (!Desktop.isDesktopSupported()) throw new IllegalStateException("Máy không hỗ trợ mở thư mục tự động");
            Desktop.getDesktop().open(folder.toFile());
            statusLabel.setText("Đã mở thư mục template tùy chỉnh: " + folder);
        } catch (Exception e) {
            statusLabel.setText("Không mở được thư mục template: " + e.getMessage());
        }
    }

    @FXML
    public void onRescanTemplates() {
        statusLabel.setText("Đang quét lại thư mục template…");
        AppContext.getInstance().getTemplateService().syncStorageCatalog()
                .thenRun(() -> Platform.runLater(() -> {
                    refreshTemplates();
                    statusLabel.setText("Đã quét lại thư mục template.");
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusLabel.setText("Quét template thất bại: " + ex.getMessage()));
                    return null;
                });
    }

    @FXML
    public void onScanAndMapSelected() {
        TemplateEntity selected = templatesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("Hãy chọn một template trong danh sách trước khi quét.");
            return;
        }
        statusLabel.setText("Đang quét cấu trúc và đề xuất mapping cho “" + selected.getName() + "”…");
        AppContext.getInstance().getSidecarService().inspectTemplateAsync(selected.getFilePath())
                .thenAccept(schema -> Platform.runLater(() -> {
                    try {
                        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
                        selected.setSchemaJson(json);
                        AppContext.getInstance().getTemplateService().updateTemplate(selected);
                        schemaJsonArea.setText(json);
                        loadMappings(json);
                        Object adaptations = schema.get("adaptations");
                        int adaptedCount = adaptations instanceof List<?> list ? list.size() : 0;
                        long unmapped = mappingTable.getItems().stream()
                                .filter(row -> row.getSource() == null || row.getSource().isBlank()).count();
                        statusLabel.setText("Quét xong • " + mappingTable.getItems().size()
                                + " placeholder • " + adaptedCount + " vùng tự nhận diện • "
                                + unmapped + " chưa map");
                    } catch (Exception e) {
                        statusLabel.setText("Không lưu được kết quả quét: " + e.getMessage());
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusLabel.setText("Quét template thất bại: " + ex.getMessage()));
                    return null;
                });
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
            statusLabel.setText("Đã lưu template “" + selected.getName()
                    + "” • sẵn sàng trong danh sách Mẫu xuất.");
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
